#!/usr/bin/env python3
"""Disposable local-only rclone comparison and bisync safety checks; no cloud config."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import platform
import resource
import shutil
import signal
import statistics
import subprocess
import tempfile
import time

MARKER = 'CLOUDBRIDGE_BENCH_ACCESS'


def digest(path):
    h = hashlib.sha256()
    with path.open('rb') as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b''):
            h.update(block)
    return h.hexdigest()


def manifest(root):
    return {p.relative_to(root).as_posix(): digest(p) for p in sorted(root.rglob('*'))
            if p.is_file() and p.name != MARKER}


def dataset(root, kind):
    root.mkdir(parents=True, exist_ok=True)
    (root / MARKER).write_text('disposable-access-check\n', encoding='utf-8')
    if kind == 'small':
        for i in range(1000):
            (root / f'file-{i:04d}.bin').write_bytes(hashlib.sha256(str(i).encode()).digest() * 128)
    elif kind == 'large':
        for i in range(2):
            (root / f'large-{i}.bin').write_bytes(hashlib.sha256(str(i).encode()).digest() * (256 * 1024))
    elif kind == 'deep':
        for i in range(100):
            p = root / f'branch-{i % 10}' / 'one' / 'two' / 'three' / f'{i}.bin'
            p.parent.mkdir(parents=True, exist_ok=True)
            p.write_bytes(hashlib.sha256(str(i).encode()).digest() * 32)
        for name in ('space name', 'é', '日本語', '.hidden', 'line\nname'):
            (root / name).write_bytes(name.encode())
    elif kind != 'empty':
        raise ValueError(kind)


def execute(binary, args, config, log, interrupt=None):
    env = {k: v for k, v in os.environ.items() if not k.startswith('RCLONE_')}
    cmd = [str(binary), '--config', str(config), '--ask-password=false', *args]
    start = time.monotonic()
    sent = False
    with log.open('wb') as output:
        process = subprocess.Popen(cmd, stdout=output, stderr=subprocess.STDOUT, env=env,
                                   start_new_session=True)
        while True:
            pid, status, usage = os.wait4(process.pid, os.WNOHANG)
            if pid:
                process.returncode = os.waitstatus_to_exitcode(status)
                break
            elapsed = time.monotonic() - start
            if interrupt and not sent and elapsed >= interrupt[0]:
                os.killpg(process.pid, interrupt[1]); sent = True
            if elapsed > 120:
                os.killpg(process.pid, signal.SIGKILL)
                _, status, usage = os.wait4(process.pid, 0)
                process.returncode = os.waitstatus_to_exitcode(status)
                raise TimeoutError('Disposable command exceeded 120-second limit')
            time.sleep(0.01)
    return dict(exit=process.returncode, wallSeconds=time.monotonic() - start,
                userSeconds=usage.ru_utime, systemSeconds=usage.ru_stime,
                maxRssKiB=usage.ru_maxrss, voluntarySwitches=usage.ru_nvcsw,
                involuntarySwitches=usage.ru_nivcsw, logBytes=log.stat().st_size,
                interruptSent=sent, argv=cmd)


def bisync(binary, a, b, state, config, log, initial=False, extra=(), interrupt=None):
    args = ['bisync', str(a), str(b), '--workdir', str(state), '--compare', 'size,checksum',
            '--conflict-resolve', 'none', '--conflict-loser', 'num', '--max-delete', '10',
            '--check-access', '--check-filename', MARKER, '--checkers', '2', '--transfers', '2',
            '--stats', '0', *extra]
    if initial:
        args += ['--resync', '--resync-mode', 'path1']
    return execute(binary, args, config, log, interrupt)


def safety(binary, base, config, output):
    root = base / 'safety'; a = root / 'a'; b = root / 'b'; state = root / 'state'
    dataset(a, 'small'); dataset(b, 'empty'); output.mkdir(parents=True, exist_ok=True)
    results = []

    def run(name, predicate, **kwargs):
        result = bisync(binary, a, b, state, config, output / (name + '.log'), **kwargs)
        result.update(name=name, passed=bool(predicate(result)))
        results.append(result)
        return result

    run('initialize', lambda r: r['exit'] == 0 and manifest(a) == manifest(b), initial=True)
    (a / 'added').write_bytes(b'new')
    run('addition', lambda r: r['exit'] == 0 and (b / 'added').read_bytes() == b'new')
    (a / 'added').write_bytes(b'OLD'); (b / 'added').write_bytes(b'NEW')
    # Both sides changed to same-size different content: preserve both conflict copies.
    run('same-size-conflict', lambda r: r['exit'] == 0 and manifest(a) == manifest(b)
        and {b'OLD', b'NEW'}.issubset({p.read_bytes() for p in a.glob('added*') if p.is_file()}))
    (a / 'file-0000.bin').unlink()
    run('one-sided-deletion', lambda r: r['exit'] == 0 and not (b / 'file-0000.bin').exists())
    before = manifest(b)
    for p in sorted(a.glob('file-*.bin'))[:200]:
        p.unlink()
    run('deletion-threshold', lambda r: r['exit'] != 0 and manifest(b) == before)
    # Do not automatically resync after the threshold failure; independent missing-root fixture.
    ma = root / 'missing-a'; mb = root / 'missing-b'; ms = root / 'missing-state'
    dataset(ma, 'deep'); dataset(mb, 'empty')
    init = bisync(binary, ma, mb, ms, config, output / 'missing-init.log', initial=True)
    assert init['exit'] == 0
    snapshot = manifest(mb); ma.rename(root / 'unavailable-a')
    unavailable = bisync(binary, ma, mb, ms, config, output / 'missing-root.log')
    unavailable.update(name='missing-root', passed=unavailable['exit'] != 0 and manifest(mb) == snapshot)
    results.append(unavailable)
    ca = root / 'unicode-a'; cb = root / 'unicode-b'; cs = root / 'unicode-state'
    dataset(ca, 'empty'); dataset(cb, 'empty')
    (ca / 'é').write_bytes(b'NFC'); (ca / 'e\u0301').write_bytes(b'NFD')
    collision = bisync(binary, ca, cb, cs, config, output / 'unicode-default.log', initial=True)
    collision.update(name='unicode-default-normalization',
        passed=collision['exit'] != 0 or manifest(ca) == manifest(cb),
        limitation='Distinct NFC/NFD names require collision preflight or validated no-normalization policy')
    results.append(collision)
    # Inject process death only in an independent disposable pair, never in benchmark baselines.
    fa = root / 'fault-a'; fb = root / 'fault-b'; fs = root / 'fault-state'
    dataset(fa, 'empty'); dataset(fb, 'empty')
    init = bisync(binary, fa, fb, fs, config, output / 'fault-init.log', initial=True)
    assert init['exit'] == 0
    (fa / 'transfer.bin').write_bytes(b'Z' * 8 * 1024 * 1024)
    killed = bisync(binary, fa, fb, fs, config, output / 'forced-stop.log',
                    extra=('--bwlimit', '1M'), interrupt=(0.25, signal.SIGKILL))
    killed.update(name='forced-stop', passed=killed['interruptSent'] and killed['exit'] != 0,
                  recovery='not attempted: interrupted pair retained only for diagnosis')
    results.append(killed)
    return results


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--candidate', type=Path, required=True)
    parser.add_argument('--baseline', type=Path)
    parser.add_argument('--candidate-source', required=True, help='Exact source SHA and dirty-state description')
    parser.add_argument('--baseline-source', help='Exact source SHA; required with --baseline')
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--repetitions', type=int, default=3)
    parser.add_argument('--scratch', type=Path)
    args = parser.parse_args()
    if args.repetitions < 2 or args.repetitions > 30:
        parser.error('Use 2–30 repetitions')
    if args.baseline and not args.baseline_source:
        parser.error('--baseline-source is required with --baseline')
    args.output.mkdir(parents=True, exist_ok=True)
    report = dict(schemaVersion=1, completed=False, platform=platform.platform(), machine=platform.machine(),
                  logicalCpus=os.cpu_count(), clock='monotonic', metrics='Linux wait4 per child',
                  limitations=['Host local filesystems only; no Android/device/Proton measurement',
                               'Warm page/build caches; no cold-cache or isolated-network claim',
                               'Shared execution host; load is not experimentally isolated',
                               'Host energy, device RSS/PSS and cloud API calls not measured'], engines={}, samples=[], safety={})
    def checkpoint():
        (args.output / 'report.json').write_text(json.dumps(report, indent=2) + '\n')
    checkpoint()
    engines = [('candidate', args.candidate)]
    if args.baseline:
        engines.insert(0, ('baseline', args.baseline))
    with tempfile.TemporaryDirectory(prefix='cloudbridge-perf-', dir=args.scratch) as disposable:
        temp = Path(disposable); config = temp / 'empty.conf'; config.write_text('')
        for label, executable in engines:
            binary = executable.resolve(strict=True)
            report['engines'][label] = dict(path=str(binary), sha256=digest(binary),
                source=args.baseline_source if label == 'baseline' else args.candidate_source,
                version=subprocess.check_output([str(binary), 'version'], text=True).strip())
            for kind in ('empty', 'small', 'large', 'deep'):
                for repeat in range(args.repetitions):
                    run = temp / label / kind / str(repeat); a = run / 'a'; b = run / 'b'; state = run / 'state'
                    dataset(a, kind); dataset(b, 'empty')
                    logbase = args.output / f'{label}-{kind}-{repeat}'
                    init = bisync(binary, a, b, state, config, logbase.with_suffix('.initialize.log'), initial=True)
                    assert init['exit'] == 0 and manifest(a) == manifest(b), (label, kind, 'initialize')
                    init.update(engine=label, workload=kind, repetition=repeat, phase='initialize')
                    report['samples'].append(init)
                    checkpoint()
                    steady = bisync(binary, a, b, state, config, logbase.with_suffix('.steady.log'))
                    assert steady['exit'] == 0 and manifest(a) == manifest(b), (label, kind, 'steady')
                    steady.update(engine=label, workload=kind, repetition=repeat, phase='no-change')
                    report['samples'].append(steady)
                    checkpoint()
                    shutil.rmtree(run)
            report['safety'][label] = safety(binary, temp / label, config, args.output / label / 'safety')
            checkpoint()
    report['summary'] = []
    for label, _ in engines:
        for kind in ('empty', 'small', 'large', 'deep'):
            for phase in ('initialize', 'no-change'):
                rows = [r for r in report['samples'] if (r['engine'], r['workload'], r['phase']) == (label, kind, phase)]
                values = [r['wallSeconds'] for r in rows]
                report['summary'].append(dict(engine=label, workload=kind, phase=phase, samples=len(rows),
                    medianWallSeconds=statistics.median(values), minWallSeconds=min(values), maxWallSeconds=max(values),
                    meanWallSeconds=statistics.mean(values), stdevWallSeconds=statistics.stdev(values),
                    maxRssKiB=max(r['maxRssKiB'] for r in rows)))
    report['passed'] = all(r['passed'] for rows in report['safety'].values() for r in rows)
    report['completed'] = True
    (args.output / 'report.json').write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps(dict(passed=report['passed'], sampleCount=len(report['samples']), safety=report['safety']), indent=2))
    raise SystemExit(0 if report['passed'] else 1)

if __name__ == '__main__':
    main()
