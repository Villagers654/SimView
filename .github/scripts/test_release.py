import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import mock_open, patch
import zipfile

import release


class ReleaseTests(unittest.TestCase):
    def test_existing_tag_cannot_move_to_another_commit(self):
        with patch.object(release, 'gh_json', return_value={'object': {'type': 'commit', 'sha': 'old'}}):
            with self.assertRaisesRegex(ValueError, 'different commit'):
                release.assert_tag('owner/repo', 'v0.2.0', 'new')

    def test_annotated_tag_is_resolved_to_its_commit(self):
        with patch.object(release, 'gh_json', side_effect=[
            {'object': {'type': 'tag', 'sha': 'annotation'}},
            {'object': {'type': 'commit', 'sha': 'same'}}
        ]):
            release.assert_tag('owner/repo', 'v0.2.0', 'same')

    def test_version_changed_and_unchanged_pushes(self):
        for previous, expected in [('0.1.0', 'true'), ('0.2.0', 'false')]:
            with patch.dict(os.environ, {'GITHUB_OUTPUT': 'output', 'BEFORE_SHA': 'a' * 40,
                                         'GITHUB_EVENT_NAME': 'push'}), \
                 patch.object(release, 'run', return_value=json.dumps({'Version': previous})), \
                 patch('builtins.open', mock_open()) as output:
                release.metadata('0.2.0')
                output().write.assert_called_once_with(f'version=0.2.0\nchanged={expected}\n')

    def test_draft_without_tag_cannot_publish_a_different_commit(self):
        with patch.dict(os.environ, {'GITHUB_REPOSITORY': 'owner/repo', 'GITHUB_SHA': 'new'}), \
             patch.object(release, 'artifact', return_value=(Path('jar'), 'digest')), \
             patch.object(release, 'assert_tag', return_value=False), \
             patch.object(release, 'gh_json', return_value={'draft': True, 'target_commitish': 'old'}), \
             patch.object(release, 'run') as mutate:
            with self.assertRaisesRegex(ValueError, 'different commit'):
                release.github('0.2.0')
            mutate.assert_not_called()

    def test_modtale_retry_refuses_different_existing_file(self):
        with patch.dict(os.environ, {'MODTALE_API_KEY': 'test-placeholder'}), \
             patch.object(release, 'artifact', return_value=(Path('jar'), 'digest')), \
             patch.object(release, 'modtale_request', side_effect=[
                 {'id': release.PROJECT, 'title': 'SimView', 'author': 'Villagers654'},
                 {'versions': [{'versionNumber': '0.2.0'}]}, None
             ]) as request:
            with self.assertRaisesRegex(ValueError, 'different contents'):
                release.modtale('0.2.0')
            self.assertEqual(request.call_count, 3)

    def test_modtale_retry_skips_identical_existing_file(self):
        with patch.dict(os.environ, {'MODTALE_API_KEY': 'test-placeholder'}), \
             patch.object(release, 'artifact', return_value=(Path('jar'), 'digest')), \
             patch.object(release, 'modtale_request', side_effect=[
                 {'id': release.PROJECT, 'title': 'SimView', 'author': 'Villagers654'},
                 {'versions': [{'versionNumber': '0.2.0'}]}, {'versionNumber': '0.2.0'}
             ]) as request:
            release.modtale('0.2.0')
            self.assertEqual(request.call_count, 3)

    def test_authenticated_redirects_are_refused(self):
        with self.assertRaisesRegex(RuntimeError, 'redirects are refused'):
            release.NoRedirect().redirect_request(None, None, 302, '', {}, 'https://other.example')


if __name__ == '__main__':
    unittest.main()
