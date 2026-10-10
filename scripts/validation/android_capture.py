#!/usr/bin/env python3
"""Capture an explicitly selected Android test device without resetting its battery stats."""
import argparse
import datetime
import json
import os
from pathlib import Path
import subprocess
import time


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--adb', default='adb')
    p.add_argument('--serial', required=True, help='Explicit device/emulator serial; no implicit first-device selection')
    p.add_argument('--package', default='de.schuelken.cloudbridge.debug')
    p.add_argument('--output', required=True, type=Path)
    p.add_argument('--duration', type=int, default=60)
    p.add_argument('--sample-seconds', type=int, default=5)
    p.add_argument('--instrument', action='store_true', help='Run installed disposable-local acceptance tests')
    p.add_argument('--launch', action='store_true', help='Launch the selected application before observation')
    p.add_argument('--perfetto', action='store_true', help='Collect app-filtered scheduling/CPU trace on device')
    args = p.parse_args()
    if args.duration < 5 or args.duration > 3600 or args.sample_seconds < 1:
        p.error('duration must be 5–3600 and sample-seconds at least 1')
    if not args.package.startswith('de.schuelken.cloudbridge'):
        p.error('Use a CloudBridge package; isolated debug builds recommended')
    args.output.mkdir(parents=True, exist_ok=True)
    adb = [args.adb, '-s', args.serial]
    report = dict(schemaVersion=1, timestamp=datetime.datetime.now(datetime.timezone.utc).isoformat(),
                  package=args.package, serial=args.serial, durationSeconds=args.duration,
                  limitations=['No battery reset/unplug simulation; cumulative counters require careful interpretation',
                               'CPU/RSS/thermal/power conditions must be controlled across paired runs',
                               'No cloud operation is initiated by this harness'], commands=[], samples=[])

    def capture(label, words, timeout=30):
        result = subprocess.run([*adb, *words], capture_output=True, text=True, timeout=timeout)
        # Preserve command status; unavailable platform counters are not successful measurements.
        (args.output / (label + '.txt')).write_text(result.stdout + result.stderr)
        report['commands'].append(dict(label=label, exit=result.returncode, argv=words))
        return result

    ready = capture('state', ['get-state'])
    if ready.returncode or ready.stdout.strip() != 'device':
        raise SystemExit('Explicit device is unavailable/unauthorized; no test actions performed')
    capture('device-properties', ['shell', 'getprop'])
    capture('package-provenance', ['shell', 'dumpsys', 'package', args.package])
    capture('battery-before', ['shell', 'dumpsys', 'battery'])
    capture('thermal-before', ['shell', 'dumpsys', 'thermalservice'])
    capture('batterystats-before', ['shell', 'dumpsys', 'batterystats', args.package])
    if args.instrument:
        capture('instrumentation', ['shell', 'am', 'instrument', '-w', '-r',
                                   args.package + '.test/androidx.test.runner.AndroidJUnitRunner'], timeout=180)
    if args.launch:
        capture('launch', ['shell', 'monkey', '-p', args.package, '-c', 'android.intent.category.LAUNCHER', '1'])
    trace_path = '/data/local/tmp/cloudbridge-validation-' + str(os.getpid()) + '.perfetto-trace'
    tracing = None
    if args.perfetto:
        # Short config captures scheduling/process counters, not file names or request bodies.
        config = f'''buffers {{ size_kb: 16384 fill_policy: RING_BUFFER }}
duration_ms: {args.duration * 1000}
data_sources {{ config {{ name: "linux.ftrace" ftrace_config {{
 ftrace_events: "sched/sched_switch"
 ftrace_events: "sched/sched_waking"
 atrace_apps: "{args.package}"
 }} }} }}
data_sources {{ config {{ name: "linux.process_stats" process_stats_config {{ proc_stats_poll_ms: 1000 }} }} }}
'''
        tracing = subprocess.Popen([*adb, 'shell', 'perfetto', '--txt', '-c', '-', '-o', trace_path],
                                   stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
        tracing.stdin.write(config); tracing.stdin.close()
    start = time.monotonic(); index = 0
    while time.monotonic() - start < args.duration:
        prefix = f'sample-{index:04d}'
        capture(prefix + '-memory', ['shell', 'dumpsys', 'meminfo', args.package])
        capture(prefix + '-cpu', ['shell', 'dumpsys', 'cpuinfo'])
        capture(prefix + '-processes', ['shell', 'ps', '-A', '-o', 'PID,PPID,NAME,RSS'])
        report['samples'].append(dict(index=index, elapsedSeconds=time.monotonic() - start))
        index += 1
        time.sleep(min(args.sample_seconds, max(0, args.duration - (time.monotonic() - start))))
    capture('battery-after', ['shell', 'dumpsys', 'battery'])
    capture('thermal-after', ['shell', 'dumpsys', 'thermalservice'])
    capture('batterystats-after', ['shell', 'dumpsys', 'batterystats', args.package])
    if tracing:
        try:
            code = tracing.wait(timeout=15)
            (args.output / 'perfetto-status.txt').write_text(str(code) + '\n' + tracing.stdout.read() + tracing.stderr.read())
            if code == 0:
                capture('perfetto-pull', ['pull', trace_path, str(args.output / 'trace.perfetto-trace')])
        finally:
            if tracing.poll() is None: tracing.terminate()
            capture('perfetto-cleanup', ['shell', 'rm', '-f', trace_path])
    report['passed'] = all(c['exit'] == 0 for c in report['commands'])
    (args.output / 'capture.json').write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps(dict(passed=report['passed'], samples=len(report['samples']), output=str(args.output))))

if __name__ == '__main__':
    main()
