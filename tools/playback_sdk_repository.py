"""Manage the PlayTV copy of the PlaybackSDK Maven publication."""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile

PROJECT = Path(__file__).resolve().parents[1]
REPOSITORY = PROJECT / 'sdk-maven'
COORDINATE = Path('com/ai/media/playback-android')


def digest_file(path: Path) -> str:
    h = hashlib.sha256()
    with path.open('rb') as stream:
        while block := stream.read(1024 * 1024):
            h.update(block)
    return h.hexdigest()


def git(sdk: Path, *args: str) -> bytes:
    return subprocess.check_output(['git', '-C', str(sdk), *args])


def fingerprint(sdk: Path) -> str:
    """Include dirty SDK files and locally prepared SDK build inputs."""
    h = hashlib.sha256(b'playtv-sdk-inputs-v1\0')
    h.update(git(sdk, 'rev-parse', 'HEAD'))
    h.update(git(sdk, 'diff', '--binary', '--no-ext-diff', 'HEAD', '--'))
    untracked = git(sdk, 'ls-files', '--others', '--exclude-standard', '-z').split(b'\0')
    for raw in sorted(filter(None, untracked)):
        path = sdk / os.fsdecode(raw)
        h.update(b'untracked\0' + raw + b'\0')
        if path.is_file():
            h.update(bytes.fromhex(digest_file(path)))
        else:
            raise RuntimeError(f'Unsupported SDK untracked input: {path}')
    for relative in ('.upstream/android', '.upstream/java', '.upstream/assets'):
        root = sdk / relative
        if not root.is_dir():
            raise RuntimeError(f'Missing SDK build input: {root}')
        for path in sorted(p for p in root.rglob('*') if p.is_file()):
            h.update(path.relative_to(sdk).as_posix().encode('utf-8') + b'\0')
            h.update(bytes.fromhex(digest_file(path)))
    for name in ('local.properties',):
        path = sdk / name
        h.update(name.encode('utf-8') + b'\0')
        if path.is_file():
            h.update(bytes.fromhex(digest_file(path)))
    return h.hexdigest()


def verify() -> dict:
    manifest = json.loads((REPOSITORY / 'playback-sdk.json').read_text(encoding='utf-8'))
    version = manifest['version']
    if not isinstance(version, str) or not version or any(c not in 'abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789.-' for c in version):
        raise ValueError('Invalid SDK version')
    version_dir = REPOSITORY / COORDINATE / version
    files = manifest['files']
    required = {f'playback-android-{version}.{suffix}' for suffix in ('aar', 'pom', 'module')}
    if not required.issubset(files):
        raise ValueError('Incomplete Maven publication')
    for name, expected in files.items():
        if Path(name).name != name or digest_file(version_dir / name) != expected:
            raise ValueError(f'Invalid Maven publication file: {name}')
    return manifest


def install(sdk: Path, version: str, sdk_fingerprint: str | None) -> None:
    source = sdk / 'build/repository' / COORDINATE / version
    if not source.is_dir():
        raise RuntimeError(f'Missing SDK publication: {source}')
    required = [source / f'playback-android-{version}.{suffix}' for suffix in ('aar', 'pom', 'module')]
    if not all(path.is_file() for path in required):
        raise RuntimeError('SDK publication is incomplete')
    with tempfile.TemporaryDirectory(prefix='playtv-sdk-', dir=PROJECT / 'build') as temp:
        candidate = Path(temp) / 'repository'
        target = candidate / COORDINATE / version
        target.mkdir(parents=True)
        for path in source.iterdir():
            if path.is_file():
                shutil.copy2(path, target / path.name)
        files = {p.name: digest_file(p) for p in sorted(target.iterdir()) if p.is_file()}
        manifest = {
            'version': version,
            'sdkFingerprint': sdk_fingerprint,
            'sdkCommit': git(sdk, 'rev-parse', 'HEAD').decode('ascii').strip() if sdk_fingerprint else None,
            'sdkDirty': bool(git(sdk, 'status', '--porcelain', '--untracked-files=all')) if sdk_fingerprint else None,
            'files': files,
        }
        (candidate / 'playback-sdk.json').write_text(json.dumps(manifest, indent=2) + '\n', encoding='utf-8')
        # This directory is dedicated to the generated SDK publication.
        backup = Path(temp) / 'previous'
        if REPOSITORY.exists():
            REPOSITORY.rename(backup)
        try:
            candidate.rename(REPOSITORY)
        except BaseException:
            if backup.exists():
                backup.rename(REPOSITORY)
            raise


def main() -> None:
    parser = argparse.ArgumentParser()
    sub = parser.add_subparsers(dest='command', required=True)
    check = sub.add_parser('fingerprint')
    check.add_argument('sdk', type=Path)
    sub.add_parser('verify')
    update = sub.add_parser('install')
    update.add_argument('sdk', type=Path)
    update.add_argument('version')
    update.add_argument('--fingerprint')
    args = parser.parse_args()
    if args.command == 'fingerprint':
        print(fingerprint(args.sdk))
    elif args.command == 'verify':
        print(json.dumps(verify(), separators=(',', ':')))
    else:
        (PROJECT / 'build').mkdir(exist_ok=True)
        install(args.sdk, args.version, args.fingerprint)


if __name__ == '__main__':
    main()
