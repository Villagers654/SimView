"""Publish the tested jar without replacing existing versions."""
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import urllib.error
import urllib.request
import uuid
import zipfile

PROJECT = 'edcf7eb8-7a08-4869-83a0-3a3d94e91ea4'
API = 'https://api.modtale.net/api/v1'
GAME_VERSIONS = ['0.6.8', '0.7.0-pre.5.1']


def run(*args):
    return subprocess.check_output(args, text=True).strip()


def version():
    value = json.loads(Path('src/main/resources/manifest.json').read_text())['Version']
    if not re.fullmatch(r'[0-9]+\.[0-9]+\.[0-9]+', value):
        raise ValueError('Release version must be major.minor.patch')
    gradle = re.search(r"^version = '([^']+)'$", Path('build.gradle').read_text(), re.M)
    if not gradle or gradle[1] != value:
        raise ValueError('Gradle and manifest versions differ')
    return value


def artifact(value):
    path = Path(f'build/libs/SimView-{value}.jar')
    with zipfile.ZipFile(path) as jar:
        manifest = json.loads(jar.read('manifest.json'))
        if manifest['Version'] != value or manifest['Name'] != 'SimView':
            raise ValueError('Jar manifest does not match release')
        if manifest['ServerVersion'] != '=0.6.8 || =0.7.0-pre.5.1':
            raise ValueError('Jar compatibility does not match tested APIs')
    return path, hashlib.sha256(path.read_bytes()).hexdigest()


def gh_json(endpoint):
    result = subprocess.run(['gh', 'api', endpoint], text=True, capture_output=True)
    if result.returncode:
        if 'HTTP 404' in result.stderr:
            return None
        raise RuntimeError('GitHub API lookup failed')
    return json.loads(result.stdout)


def assert_tag(repo, tag, sha):
    ref = gh_json(f'repos/{repo}/git/ref/tags/{tag}')
    if ref is None:
        return False
    obj = ref['object']
    while obj['type'] == 'tag':
        obj = gh_json(f"repos/{repo}/git/tags/{obj['sha']}")['object']
    if obj['type'] != 'commit' or obj['sha'] != sha:
        raise ValueError('Existing release tag belongs to a different commit')
    return True


def find_release(repo, tag):
    published = gh_json(f'repos/{repo}/releases/tags/{tag}')
    if published is not None:
        return published
    # GitHub's by-tag endpoint excludes drafts, even for their creator.
    drafts = gh_json(f'repos/{repo}/releases?per_page=100')
    return next((item for item in drafts if item['tag_name'] == tag), None)


def github(value):
    path, digest = artifact(value)
    repo, sha = os.environ['GITHUB_REPOSITORY'], os.environ['GITHUB_SHA']
    tag = f'v{value}'
    tagged = assert_tag(repo, tag, sha)
    release = find_release(repo, tag)
    if release and release['draft'] and release['target_commitish'] != sha:
        raise ValueError('Existing draft release belongs to a different commit')
    if not tagged:
        run('gh', 'api', f'repos/{repo}/git/refs', '-X', 'POST',
            '-f', f'ref=refs/tags/{tag}', '-f', f'sha={sha}')
        if not assert_tag(repo, tag, sha):
            raise ValueError('Release tag creation could not be verified')
    if release is None:
        notes = (['--notes', 'Supports Hytale 0.6.8 and 0.7.0-pre.5.1. Uses native section streaming, '
                  'block-based distances and native default budgets. Fixes lifecycle restoration, '
                  'configuration migration and tuning bounds. Adds streaming diagnostics and places '
                  'Advanced below the three primary settings.'] if value == '0.2.0' else ['--generate-notes'])
        run('gh', 'release', 'create', tag, '--repo', repo, '--target', sha,
            '--draft', '--title', f'SimView {value}', *notes)
        release = find_release(repo, tag)
        if release is None:
            raise RuntimeError('Created draft release could not be found')
    assert_tag(repo, tag, sha)
    assets = [item for item in release['assets'] if item['name'] == path.name]
    if assets:
        with tempfile.TemporaryDirectory() as directory:
            run('gh', 'release', 'download', tag, '--repo', repo,
                '--pattern', path.name, '--dir', directory)
            if hashlib.sha256((Path(directory) / path.name).read_bytes()).hexdigest() != digest:
                raise ValueError('Existing release asset has different contents')
    else:
        run('gh', 'release', 'upload', tag, str(path), '--repo', repo)
    if release['draft']:
        run('gh', 'release', 'edit', tag, '--repo', repo, '--draft=false')
    if not assert_tag(repo, tag, sha):
        raise ValueError('Published release tag could not be verified')
    print(f'Published {tag}: SHA256 {digest}')


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        raise RuntimeError('Modtale API redirects are refused')


def modtale_request(route, key, data=None, content_type=None, allow_missing=False):
    headers = {'X-MODTALE-KEY': key, 'Accept': 'application/json',
               'User-Agent': 'SimView-release (+https://github.com/Villagers654/SimView)'}
    if content_type:
        headers['Content-Type'] = content_type
    request = urllib.request.Request(API + route, data=data, headers=headers)
    try:
        with urllib.request.build_opener(NoRedirect).open(request, timeout=120) as response:
            body = response.read()
            return json.loads(body) if body else None
    except urllib.error.HTTPError as error:
        if allow_missing and error.code == 404:
            return None
        # Do not print response bodies or requests containing authentication headers.
        raise RuntimeError(f'Modtale API {route} returned HTTP {error.code}') from None


def modtale(value):
    key = os.environ.get('MODTALE_API_KEY', '')
    if not key:
        raise RuntimeError('Set Actions secret MODTALE_API_KEY (SimView PROJECT_READ and VERSION_CREATE)')
    path, digest = artifact(value)
    route = f'/projects/{PROJECT}'
    project = modtale_request(route, key)
    if project['id'] != PROJECT or project['title'] != 'SimView' or project['author'] != 'Villagers654':
        raise ValueError('Modtale project identity does not match')
    versions = modtale_request(route + '/versions', key)['versions']
    if any(item['versionNumber'] == value for item in versions):
        existing = modtale_request(route + f'/versions/hash/{digest}', key, allow_missing=True)
        if existing is None or existing['versionNumber'] != value:
            raise ValueError('Existing Modtale version has different contents')
        print(f'Modtale {value} already contains this jar')
        return
    catalog = modtale_request('/meta/game-versions', key)
    if not all(item in catalog for item in GAME_VERSIONS):
        raise ValueError('Modtale does not recognize both tested game versions')
    notes = run('gh', 'release', 'view', f'v{value}', '--repo', os.environ['GITHUB_REPOSITORY'],
                '--json', 'body', '--jq', '.body')
    boundary = uuid.uuid4().hex
    parts = []
    def field(name, text):
        parts.append(f'--{boundary}\r\nContent-Disposition: form-data; name="{name}"\r\n\r\n{text}\r\n'.encode())
    field('versionNumber', value)
    field('channel', 'RELEASE')
    field('replaceExisting', 'false')
    field('changelog', notes)
    for item in GAME_VERSIONS:
        field('gameVersions', item)
    parts.append(f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="{path.name}"\r\nContent-Type: application/java-archive\r\n\r\n'.encode())
    parts.extend([path.read_bytes(), f'\r\n--{boundary}--\r\n'.encode()])
    modtale_request(route + '/versions', key, b''.join(parts), f'multipart/form-data; boundary={boundary}')
    uploaded = modtale_request(route + f'/versions/hash/{digest}', key)
    if uploaded['versionNumber'] != value:
        raise ValueError('Uploaded Modtale version could not be verified')
    print(f'Published Modtale {value}: SHA256 {digest}')


def metadata(value):
    before = os.environ.get('BEFORE_SHA', '')
    changed = os.environ.get('GITHUB_EVENT_NAME') == 'workflow_dispatch'
    if before and before != '0' * 40:
        previous = json.loads(run('git', 'show', f'{before}:src/main/resources/manifest.json'))['Version']
        changed = previous != value
    elif os.environ.get('GITHUB_EVENT_NAME') == 'push':
        changed = True
    with open(os.environ['GITHUB_OUTPUT'], 'a') as output:
        output.write(f'version={value}\nchanged={str(changed).lower()}\n')


if __name__ == '__main__':
    try:
        {'metadata': metadata, 'github': github, 'modtale': modtale}[sys.argv[1]](version())
    except Exception as error:
        print(f'Release failed: {error}', file=sys.stderr)
        sys.exit(1)
