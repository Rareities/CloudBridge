# PERF-002 execution evidence

The final paired run uses historical thies2005/rclone `94f543c9d4df4f7a29c87c9b76abf7bb341b5d33` and published Rareities/rclone `fc35e4a8c75211abf6f1bca367af482a0aec68f7`. [The complete machine-readable report](native-benchmark.json) retains source identity, binary SHA256, version/linking metadata, per-run argv, Linux wait4 CPU/RSS/context switches and safety results. The baseline archive was built with `-buildvcs=false`; candidate Git build information is clean and identifies its exact published SHA. Both use Go 1.26.9. Baseline links statically and candidate dynamically; this and the many version changes confound causal comparison.

48 samples cover two engines, four workloads, three repetitions, initialization and no-change phases. These are shared-host warm-cache local filesystem observations. They do not demonstrate Android or cloud performance, isolated optimization effects, cold-start behavior, API costs or battery savings.

| Workload/phase | Baseline median seconds | Candidate median seconds | Baseline peak RSS MiB | Candidate peak RSS MiB |
| --- | ---: | ---: | ---: | ---: |
| empty/initialize | 0.051 | 0.146 | 79.6 | 71.3 |
| empty/no-change | 0.051 | 0.145 | 79.2 | 70.4 |
| small/initialize | 0.370 | 0.762 | 99.3 | 91.2 |
| small/no-change | 0.145 | 0.257 | 91.3 | 89.2 |
| large/initialize | 0.124 | 0.320 | 87.6 | 79.1 |
| large/no-change | 0.116 | 0.276 | 78.8 | 70.3 |
| deep/initialize | 0.081 | 0.259 | 84.8 | 78.1 |
| deep/no-change | 0.051 | 0.171 | 82.0 | 73.5 |

Each engine passes seven of eight native safety fixtures: initialization, addition, same-size content conflict preservation, one-sided deletion, deletion-percentage protection, missing-root protection and forced child-death outcome. Both **fail** NFC/NFD normalization collision: different names can collapse, one destination name is missing and the native command returns zero. Therefore `completed=true`, `passed=false`, and the harness exits nonzero. This is not native acceptance. CloudBridge now rejects normalized duplicate relative paths in `BisyncListingSafety` before mutation, with a regression fixture. The interrupted disposable pair is never automatically resynced.

The Android native-engine instrumentation APK compiles, but no physical device/emulator/system image or KVM is provisioned. Actual ABI launch, Keystore expiry/loss, SAF/provider cancellation, restore/process-death fault injection, foreground-service quota, Doze, network/trust and physical energy acceptance remain unexecuted. Disposable Proton accounts and official-client fixtures also remain unavailable.

[The harness instructions](../../scripts/validation/README.md) provide reproducible local and device-capture commands. `perf-002-host-results.json` is retained as an earlier, explicitly different-source run and is not evidence for the final SHA. No measured universal efficiency improvement is claimed.
