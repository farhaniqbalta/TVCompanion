#!/usr/bin/env python3
"""Fetch checksum-pinned sources outside tracked files and build private ARMv7 crypto.

No app or TV access. Requires the Android SDK NDK specified in integrations/uxplay-native/build.gradle.
Sources, upstream notices and build logs remain in .native for local inspection.
"""
import concurrent.futures
import hashlib
import io
import os
from pathlib import Path
import shutil
import subprocess
import tarfile
import urllib.request

ROOT = Path(__file__).resolve().parents[1] / 'android' / 'integrations' / 'uxplay-native'
CACHE = ROOT / '.native'
SOURCES = (
    ('uxplay', 'https://codeload.github.com/FDH2/UxPlay/tar.gz/534e2cc0e558d6c94bcc66964f8fc13ad73a6cdc',
     '3d77ff2086740e875ba7632703dfc67ca2c46ef4686178542726d539f3e5bde9',
     'UxPlay-534e2cc0e558d6c94bcc66964f8fc13ad73a6cdc'),
    ('libplist', 'https://codeload.github.com/libimobiledevice/libplist/tar.gz/refs/tags/2.7.0',
     '66fa17a29270cb25ceaf189430ea38037c066652c2989b58d7ef6120b1a208d3', 'libplist-2.7.0'),
    ('openssl', 'https://codeload.github.com/openssl/openssl/tar.gz/refs/tags/openssl-3.5.4',
     '758b69feed5787dc12d34b0eb29b60d3c9d73d5a64760c62d93a6d26b344d65d', 'openssl-openssl-3.5.4'),
)

def fetch(spec):
    name, url, checksum, directory = spec
    archive = CACHE / (name + '.tar.gz')
    if not archive.exists():
        print('Fetching ' + name, flush=True)
        temporary = archive.with_suffix('.download')
        with urllib.request.urlopen(url, timeout=120) as response, temporary.open('wb') as out:
            shutil.copyfileobj(response, out)
        temporary.replace(archive)
    data = archive.read_bytes()
    if hashlib.sha256(data).hexdigest() != checksum:
        raise RuntimeError('Source checksum mismatch: ' + name)
    dest = CACHE / name
    if not dest.exists():
        unpack = CACHE / (name + '-unpack')
        unpack.mkdir(exist_ok=True)
        with tarfile.open(fileobj=io.BytesIO(data)) as source:
            for entry in source.getmembers():
                if entry.name.startswith('/') or '..' in Path(entry.name).parts:
                    raise RuntimeError('Unsafe archive entry')
                if entry.issym() or entry.islnk():
                    target = Path(entry.linkname)
                    if target.is_absolute() or '..' in target.parts:
                        raise RuntimeError('Unsafe archive link')
            source.extractall(unpack)
        (unpack / directory).replace(dest)
        unpack.rmdir()
    print('Verified ' + name, flush=True)


def main():
    CACHE.mkdir(exist_ok=True)
    with concurrent.futures.ThreadPoolExecutor(max_workers=3) as pool:
        list(pool.map(fetch, SOURCES))
    receiver = CACHE / 'uxplay' / 'lib' / 'raop.c'
    if 'if (raop->httpd) {' not in receiver.read_text():
        subprocess.run(['patch', '-p1', '-i', str(ROOT / 'patches' / '0001-safe-partial-startup-cleanup.patch')],
                       cwd=CACHE / 'uxplay', check=True)
    if 'LOGGER_DEBUG, "Handling request %s with URL %s"' in receiver.read_text():
        subprocess.run(['patch', '-p1', '-i', str(ROOT / 'patches' / '0002-control-stage-diagnostic.patch')],
                       cwd=CACHE / 'uxplay', check=True)
    sdk = os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT')
    if not sdk:
        raise RuntimeError('Set ANDROID_HOME to the Android SDK directory')
    ndk = Path(sdk) / 'ndk' / '27.2.12479018'
    host = 'darwin-x86_64' if os.uname().sysname == 'Darwin' else 'linux-x86_64'
    tools = ndk / 'toolchains' / 'llvm' / 'prebuilt' / host / 'bin'
    if not tools.exists():
        raise RuntimeError('Install NDK 27.2.12479018 with the Android SDK tools')
    prefix = CACHE / 'openssl-armv7'
    if not (prefix / 'lib' / 'libcrypto.a').exists():
        build = CACHE / 'openssl-build'; build.mkdir(exist_ok=True)
        env = dict(os.environ, ANDROID_NDK_ROOT=str(ndk), PATH=str(tools) + os.pathsep + os.environ['PATH'])
        log = CACHE / 'openssl-build.log'
        print('Building OpenSSL libcrypto for ARMv7 (log: .native/openssl-build.log)', flush=True)
        commands = [
            ['perl', str(CACHE / 'openssl' / 'Configure'), 'android-arm', '-D__ANDROID_API__=26',
             'no-shared', 'no-tests', 'no-apps', 'no-docs', 'no-module', 'no-engine', '-fPIC',
             '--prefix=' + str(prefix), '--libdir=lib'],
            ['make', '-j4', 'build_libs'],
            ['make', 'install_dev'],
        ]
        with log.open('w') as output:
            for command in commands:
                subprocess.run(command, cwd=build, env=env, stdout=output, stderr=subprocess.STDOUT, check=True)
    notices = CACHE / 'notices'; notices.mkdir(exist_ok=True)
    sections = ['Development-only TVCompanion AirPlay preview. No warranty. Upstream rights retained.\n'
                'Preferred sources and build scripts: scripts/prepare-airplay-native.py and its pinned URLs.\n'
                'UxPlay LGPL components and GPL PlayFair are linked in this local experimental binary.\n']
    for source in ['uxplay/README.md', 'uxplay/LICENSE', 'uxplay/lib/llhttp/LICENSE-MIT',
                   'libplist/COPYING.LESSER', 'openssl/LICENSE.txt']:
        sections.append('\n\n--- ' + source + ' ---\n' + (CACHE / source).read_text())
    (notices / 'third-party-notices.txt').write_text('\n'.join(sections))
    print('Native sources and libcrypto ready. Build the TVCompanion APK (or optional lab) next.', flush=True)

if __name__ == '__main__':
    main()
