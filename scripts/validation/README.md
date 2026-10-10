# Validation and resource measurements

These tools use disposable local data. They never select a configured cloud remote or start a production synchronization job. Results distinguish host measurements from Android/device and provider acceptance.

## Local engine comparison

Build the historical configured source (`thies2005/rclone` commit `94f543c9d4df4f7a29c87c9b76abf7bb341b5d33`) and the reviewed candidate using the same recorded Go compiler. An archive without Git metadata needs `-buildvcs=false`; record its exact source SHA explicitly. Retain binary SHA256 and dependency manifests. Do not overwrite a production engine executable.

```sh
python3 scripts/validation/local_benchmark.py \
  --baseline /absolute/path/to/baseline-rclone \
  --baseline-source 94f543c9d4df4f7a29c87c9b76abf7bb341b5d33 \
  --candidate /absolute/path/to/candidate-rclone \
  --candidate-source '<reviewed SHA; describe any dirty source>' \
  --output /absolute/path/to/private-results \
  --repetitions 3
```

The script rejects cloud configuration by passing a private empty config and removing inherited `RCLONE_*` variables. All endpoints/workdirs are created inside one temporary directory. Output includes exact argv, engine hashes/version/source, per-child Linux `wait4` wall/user/system CPU, peak RSS, context switches, bounded workload statistics and repeated-run distributions. Raw logs contain only generated disposable names.

Workloads: empty payload, 1,000 deterministic 4 KiB files, two deterministic 8 MiB files, deep directories and unusual names. Each pair initializes explicitly and executes a no-change run. Safety fixtures cover addition, same-size simultaneous edits, one-sided deletion, deletion percentage limits, a missing root, NFC/NFD collisions, and forced child death during a bandwidth-limited transfer. Interrupted fixtures are never automatically resynced.

A failed assertion or incomplete `report.json` is not acceptance. `completed=true` and `passed=true` are both required. The collision fixture intentionally exposes silent normalization ambiguity: preserving different NFC/NFD names requires a separately validated capability policy. Do not suppress that failure to obtain a green report.

These are **warm-cache host filesystem measurements**, not Android energy or cloud benchmarks. Shared-host contention, filesystem caches, CPU scaling and backend revisions can dominate differences. Run paired trials on an otherwise idle machine, alternate engine order for larger experiments, record thermal/power/network conditions and report variance. Do not infer battery savings from elapsed time or RSS. Do not disable checksums, signatures or deletion limits to improve a result.

## Installed Android acceptance tests

`NativeEngineAcceptanceTest` is an instrumented suite for a disposable debug installation. It verifies the installed native executable against packaged provenance, executes it on the actual Android kernel, tests structured unusual filenames, and runs a temporary local bisync checksum fixture. The test does not request cloud credentials or use `rclone.conf` from application settings.

```sh
./gradlew assembleOssDebug assembleOssDebugAndroidTest
adb -s DEVICE_SERIAL install -r app/build/outputs/apk/oss/debug/APP_FOR_DEVICE.apk
adb -s DEVICE_SERIAL install -r app/build/outputs/apk/androidTest/oss/debug/TEST_APK.apk
adb -s DEVICE_SERIAL shell am instrument -w -r \
  de.schuelken.cloudbridge.debug.test/androidx.test.runner.AndroidJUnitRunner
```

Use a dedicated emulator/device and explicitly choose the compatible ABI. Installation and instrumentation are separate from read-only capture. A successful build of a test APK does not mean device tests ran.

## Device capture

```sh
python3 scripts/validation/android_capture.py \
  --serial DEVICE_SERIAL --package de.schuelken.cloudbridge.debug \
  --output /absolute/path/to/private-device-traces --duration 120 --perfetto
```

Capture is read-only by default. `--launch` starts the app; `--instrument` runs the installed disposable-local suite. The script does not install APKs, force-stop the app, change permissions, unplug battery simulation or reset cumulative battery counters. It records exact device/package state, thermal/battery conditions, memory, CPU/process samples and before/after batterystats. Perfetto records scheduling/process counters and cleans up only its own trace file. Dumps/traces include device identifiers and system activity: keep them private and use dedicated devices.

Counter availability differs by OS. Nonzero capture commands and unavailable counters remain recorded failures. Cumulative batterystats are not a direct per-run energy value; compare controlled observation intervals and preserve charging/thermal state. Network requests, cloud API calls, authentication count, wake-lock accounting and SAF/provider behavior require further app/provider-specific instrumentation and disposable account fixtures. This harness makes no acceptance claim for those absent measurements.

## Remaining device/fault acceptance matrix

Run min-SDK through target-SDK transitions on actual supported 32/64-bit ABIs; include modern 16 KiB-page environments. Exercise denied/revoked permissions, SAF metadata/hash availability, Doze, reboot, timezone/DST, process death, force-stop/reopen, foreground timeout and Android job quotas. Use disposable tasks and data. Restore tests require crashes at each journal decision boundary and Keystore invalidation; they must not involve a user's active configuration.

Pair idle, schedule-not-due, RC-browse-then-idle, small incremental edits, concurrent admission and interrupted jobs. Record exact app/engine SHA, signing certificate, ABI, OS, device, network, thermal and power state. An emulator can establish API/kernel behavior, but cannot establish physical battery acceptance. Proton accounts, old/new official-client objects, signatures, rotation, draft ownership and aliases require explicitly provisioned disposable provider fixtures; no credentials are embedded in these tools.
