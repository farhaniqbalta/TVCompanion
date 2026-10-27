#!/usr/bin/env python3
"""Explicitly install and provision the TVCompanion debug app without logging secrets."""
import argparse
import ipaddress
import json
import shlex
from pathlib import Path
import subprocess
import sys
import time

ROOT = Path(__file__).resolve().parents[1]
PACKAGE = 'id.poncol.tvcompanion'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--tv', required=True, help='TV IPv4[:ADB port]')
    parser.add_argument('--config', required=True, type=Path, help='Private lamp JSON file outside the repository')
    args = parser.parse_args()
    host, _, port = args.tv.partition(':')
    address = ipaddress.IPv4Address(host)
    if not address.is_private or address.is_loopback or address.is_multicast:
        raise ValueError('Use the TV LAN IPv4 address')
    endpoint = f'{address}:{int(port or 5555)}'
    config_path = args.config.expanduser().resolve()
    repository = ROOT.parent.resolve()
    if config_path.is_relative_to(repository):
        raise ValueError('Keep private configuration outside the repository')
    if config_path.stat().st_size > 4096:
        raise ValueError('Invalid configuration size')
    payload = json.loads(config_path.read_text())
    if not isinstance(payload, dict) or any(not isinstance(payload.get(name), str) for name in ('ip', 'id', 'key', 'version')):
        raise ValueError('Invalid configuration')
    payload = {name: payload[name] for name in ('ip', 'id', 'key', 'version')}
    if payload['version'] != '3.5' or len(payload['key'].encode()) != 16 or not payload['id']:
        raise ValueError('POC credentials are incomplete or not protocol 3.5')
    ipaddress.IPv4Address(payload['ip'])
    apk = ROOT / 'app/build/outputs/apk/debug/app-debug.apk'
    if not apk.exists():
        raise ValueError('Build the APK first')
    def adb(*command, data=None):
        result = subprocess.run(['adb', '-s', endpoint, *command], input=data, capture_output=True, timeout=60)
        if result.returncode:
            raise RuntimeError('ADB operation failed. Check TV connection and debugging authorization.')
        return result.stdout
    result = subprocess.run(['adb', 'connect', endpoint], capture_output=True, timeout=15)
    if result.returncode or b'connected' not in result.stdout:
        raise RuntimeError('ADB connection failed. Check TV address and debugging authorization.')
    adb('get-state')
    installed = adb('install', '-r', str(apk))
    if b'Success' not in installed:
        raise RuntimeError('TV did not confirm APK installation')
    adb('shell', 'am', 'force-stop', PACKAGE)
    # run-as works only for this development APK. No key is embedded or passed as a shell argument.
    adb('shell', 'run-as', PACKAGE, 'mkdir', '-p', 'files')
    encoded = json.dumps(payload).encode()
    adb('exec-in', shlex.join(['run-as', PACKAGE, 'sh', '-c', 'umask 077; cat > files/provision.json']), data=encoded)
    # Raw ADB execution can return before the remote writer has closed its file.
    # Confirm the complete transfer before launching the importer.
    for _ in range(20):
        size = adb('shell', shlex.join(['run-as', PACKAGE, 'sh', '-c', 'if test -f files/provision.json; then wc -c < files/provision.json; fi']))
        if size.strip() == str(len(encoded)).encode():
            break
        time.sleep(0.1)
    else:
        adb('shell', 'run-as', PACKAGE, 'rm', '-f', 'files/provision.json')
        raise RuntimeError('Credential transfer did not complete')
    adb('shell', 'am', 'start', '-W', '-n', PACKAGE + '/id.poncol.tvcompanion.app.MainActivity', '--ez', 'open_light', 'true')
    # Launch imports the one-time file, encrypts with Android Keystore, then deletes the plaintext.
    for _ in range(10):
        time.sleep(0.5)
        check = adb('shell', shlex.join(['run-as', PACKAGE, 'sh', '-c', 'if test -f files/lamp.enc && test ! -f files/provision.json; then echo READY; fi']))
        if b'READY' in check:
            print('TVCompanion installed, securely provisioned and opened. Launch only reads status.')
            return
    raise RuntimeError('App did not confirm encrypted provisioning; inspect the TV screen')


if __name__ == '__main__':
    try:
        main()
    except Exception as exc:
        # Never print subprocess output, credentials or exception payloads.
        print('Installation did not complete (' + type(exc).__name__ + '). Check ADB and your private configuration file.', file=sys.stderr)
        sys.exit(1)
