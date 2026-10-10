#!/usr/bin/env python3
"""Verify packaged native provenance, Android PIE and 16 KiB ELF layout."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import tempfile
import zipfile


def verify(apks, expected, go, readelf):
    records = []
    with tempfile.TemporaryDirectory(prefix='cloudbridge-artifacts-') as staging:
        for apk in apks:
            with zipfile.ZipFile(apk) as archive:
                provenance = json.loads(archive.read('assets/rclone-build.json'))
                if provenance['commit'] != expected:
                    raise ValueError(f'{apk.name}: incorrect engine identity')
                binaries = []
                for name in archive.namelist():
                    if not name.endswith('/librclone.so'):
                        continue
                    abi = name.split('/')[1]
                    data = archive.read(name)
                    digest = hashlib.sha256(data).hexdigest()
                    if digest != provenance['binaries'][abi]['sha256']:
                        raise ValueError(f'{apk.name}: binary/provenance mismatch for {abi}')
                    binary = Path(staging) / f'{abi}.so'
                    binary.write_bytes(data)
                    info = subprocess.check_output([go, 'version', '-m', str(binary)], text=True)
                    if not all(field in info for field in ('vcs.revision=' + expected, 'GOOS=android', 'CGO_ENABLED=1')):
                        raise ValueError(f'{apk.name}: wrong Go build identity or target')
                    header = subprocess.check_output([readelf, '-h', str(binary)], text=True)
                    segments = subprocess.check_output([readelf, '-lW', str(binary)], text=True)
                    loads = [line for line in segments.splitlines() if line.strip().startswith('LOAD')]
                    if ('DYN (Position-Independent Executable file)' not in header or
                            '/system/bin/linker' not in segments or not loads or
                            any(line.split()[-1] != '0x4000' for line in loads)):
                        raise ValueError(f'{apk.name}: invalid Android PIE/page alignment')
                    binaries.append({'abi': abi, 'sha256': digest, 'bytes': len(data)})
                if not binaries:
                    raise ValueError(f'{apk.name}: no native engine packaged')
                records.append({'apk': apk.name, 'sha256': hashlib.sha256(apk.read_bytes()).hexdigest(),
                                'engineCommit': expected, 'binaries': binaries})
    return records


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--apk-dir', type=Path, required=True)
    parser.add_argument('--engine-sha', required=True)
    parser.add_argument('--go', default='go')
    parser.add_argument('--readelf', default='readelf')
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    apks = sorted(args.apk_dir.glob('*.apk'))
    if len(apks) != 5:
        parser.error('Expected four ABI APKs and one universal APK')
    records = verify(apks, args.engine_sha, args.go, args.readelf)
    args.output.write_text(json.dumps(records, indent=2) + '\n')
    print(f'Verified {len(records)} APKs at engine {args.engine_sha}')
