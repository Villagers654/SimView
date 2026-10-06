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

    def test_draft_lookup_falls_back_to_release_list(self):
        draft = {'tag_name': 'v0.2.0', 'draft': True}
        with patch.object(release, 'gh_json', side_effect=[None, [draft]]):
            self.assertEqual(draft, release.find_release('owner/repo', 'v0.2.0'))

    def test_modtale_retry_refuses_different_existing_file(self):
        with patch.dict(os.environ, {'MODTALE_API_KEY': 'test-placeholder'}), \
             patch.object(release, 'artifact', return_value=(Path('jar'), 'digest')), \
             patch.object(release, 'modtale_request', side_effect=[
                 {'id': release.PROJECT, 'title': 'SimView', 'author': 'Villagers654'},
                 {'versions': [{'versionNumber': '0.2.0'}]}
             ]) as request, \
             patch.object(release, 'verify_modtale_file', side_effect=ValueError('different contents')):
            with self.assertRaisesRegex(ValueError, 'different contents'):
                release.modtale('0.2.0')
            self.assertEqual(request.call_count, 2)

    def test_modtale_retry_skips_identical_existing_file(self):
        with patch.dict(os.environ, {'MODTALE_API_KEY': 'test-placeholder'}), \
             patch.object(release, 'artifact', return_value=(Path('jar'), 'digest')), \
             patch.object(release, 'modtale_request', side_effect=[
                 {'id': release.PROJECT, 'title': 'SimView', 'author': 'Villagers654'},
                 {'versions': [{'versionNumber': '0.2.0'}]}
             ]) as request, \
             patch.object(release, 'verify_modtale_file') as verify:
            release.modtale('0.2.0')
            self.assertEqual(request.call_count, 2)
            verify.assert_called_once()

    def test_modtale_artifact_path_cannot_redirect_verification(self):
        item = {'gameVersions': release.GAME_VERSIONS, 'channel': 'RELEASE',
                'fileUrl': 'https://other.example/file.jar'}
        with self.assertRaisesRegex(ValueError, 'artifact path'):
            release.verify_modtale_file(item, Path('jar'), 'digest')

    def test_modtale_verification_checks_bytes_without_api_key(self):
        from unittest.mock import MagicMock
        item = {'gameVersions': release.GAME_VERSIONS, 'channel': 'RELEASE',
                'fileUrl': 'files/plugin/example.jar'}
        path = MagicMock()
        path.stat.return_value.st_size = 3
        response = MagicMock()
        response.__enter__.return_value.read.return_value = b'bad'
        with patch.object(release.urllib.request, 'build_opener') as opener:
            opener().open.return_value = response
            with self.assertRaisesRegex(ValueError, 'different contents'):
                release.verify_modtale_file(item, path, 'different-digest')
            request = opener().open.call_args.args[0]
            self.assertEqual('https://cdn.modtale.net/files/plugin/example.jar', request.full_url)
            self.assertIsNone(request.get_header('X-modtale-key'))
            response.__enter__().read.assert_called_once_with(4)

    def test_authenticated_redirects_are_refused(self):
        with self.assertRaisesRegex(RuntimeError, 'redirects are refused'):
            release.NoRedirect().redirect_request(None, None, 302, '', {}, 'https://other.example')

    def test_modtale_requests_identify_the_publisher(self):
        from unittest.mock import MagicMock
        response = MagicMock()
        response.__enter__.return_value.read.return_value = b'{}'
        with patch.object(release.urllib.request, 'build_opener') as opener:
            opener().open.return_value = response
            release.modtale_request('/meta/game-versions', 'test-placeholder')
            request = opener().open.call_args.args[0]
            self.assertIn('SimView-release', request.get_header('User-agent'))
            self.assertEqual('test-placeholder', request.get_header('X-modtale-key'))


if __name__ == '__main__':
    unittest.main()
