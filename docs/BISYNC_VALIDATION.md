# Bisync implementation validation

Date: 11 October 2026. This is a tested development candidate, not a production
compatibility or battery-performance certification. No production Proton data
was used and no release was merged or published by this implementation pass.

## Inputs

| Input | Value |
| --- | --- |
| App baseline | `ece9275441e448ec520a7cbc336c59bfe643b22c` |
| App candidate | `feat/proton-bisync`, version 1.0.2, default version code 30 plus ABI offsets |
| Packaged engine | Rareities/rclone `789cfc5c4fde57ad301485a809e23a4aa84e5e31` |
| Engine source label | `v1.76.0`, development source |
| Subsequent engine CI-only commit | `5b1b5c0dccfea83712b574a3fefa9d9916d45655` |
| Local build host | Windows, JDK 17, Go 1.27.1, Android SDK/build tools 36 |
| Native build | GOOS=linux, CGO disabled, `noselfupdate`, four ABIs |

The app pins the executable source commit, not the later CI-only commit. The
APK asset `engine-provenance.json` records exact native hashes and build inputs;
`engine-modules.txt` records module versions. A separate delivery manifest maps
the final app source commit to the verified APK hashes.

## Executed checks

| Check | Result |
| --- | --- |
| `testOssDebugUnitTest` | Passed: 98 tests in 25 suites, zero failures/errors/skips |
| Root `lint` | Passed with existing baselines; app 162 warnings, SAF module 1 warning |
| Existing filtered lint debt | App baseline filters 6 errors and 428 warnings; SAF baseline filters 5 warnings |
| `assembleOssDebug` | Passed: armeabi-v7a, arm64-v8a, x86, x86_64 and universal APKs |
| `scripts/Verify-Apk.ps1` | All five APK signatures, native hashes, provenance, sizes and ELF architectures verified |
| Release without signing inputs | `preOssReleaseBuild` rejected by the independent release-signing guard |
| Host native encryption smoke | Passed: plaintext canary absent on disk, wrong password rejected, portable re-encryption readable |
| Host RC authentication smoke | Environment-only credentials: unauthenticated request HTTP 401, authenticated request HTTP 200 |
| Engine package tests | Proton Drive, Internxt and bisync tests passed |
| Engine static checks | `go vet` passed for the changed engine packages |
| Companion modules | Fake-server session, filename, block and crypto tests passed |
| Upload cancellation/cooldown | Targeted tests repeated ten times and passed |
| Engine GitHub integration | Run 38107556724 succeeded at executable source 789cfc5 |
| Engine GitHub regression | Run 38107556700 succeeded at 789cfc5, including concurrent ownership/race and static checks |
| CI isolation follow-up | YAML, Bash grammar, pinned actions, trust-boundary assertions and diff checks passed |

The normal app suite includes native encryption using the locally built engine
via `CLOUDBRIDGE_TEST_RCLONE`. Other environments may skip that opt-in test unless
they provide an executable. Keystore unit tests use controlled test keys; they
do not establish actual device key invalidation behaviour.

Regression coverage includes nonzero/cancelled/missing processes, concurrent
stream draining and exit callbacks, provider timeout/failure, RC handler
registration and lease ordering, missing daemon handling, malformed imports,
journal rollback, task identities, pending/terminal history, bounded logs and
previews, option restrictions, schedule/DST cases, updater channel/version
selection and opt-in session-health preferences.

The new trusted upstream publisher has not been exercised end to end on GitHub.
It intentionally publishes only when its reviewed workflow and script are on
master. Parser and structural tests are not a substitute for that deployment
test. App pull-request CI status is reported separately from this local result.

## Remaining release gates and scope limits

| Gate | Not yet established |
| --- | --- |
| Live Proton | Disposable-account cross-client upload/read/overwrite, multi-process long-lived refresh, account aliases/two-password matrix |
| Device behaviour | Physical Android launch, all advertised API/ABI combinations, Doze/reboot/force-stop, foreground timeout, real SAF clients and Keystore loss |
| Resource performance | Controlled energy, memory, wakeup, throughput and concurrency measurements on low/high-memory devices |
| Crypto evolution | Continued compatibility with future Proton crypto/service changes and authenticated official-client objects |
| Signed release | Approved real signing certificate, device acceptance and an explicit release decision |

The 30-second RC idle grace and conservative transfer admission are initial
policies, not measured optimal settings. No battery-saving percentage or
universal service compatibility is claimed. The full original plan's live
validation and benchmark acceptance criteria are therefore still open.

The current managed bisync pilot is native local storage paired with an ordinary
Drive directory. SAF/cloud-to-cloud **bisync** is not promoted as validated,
although existing ordinary cloud-to-cloud copy/sync remains. Photos, Shared
With Me, instant event-driven sync, unknown-draft recovery, remote revision
history UI, desktop support and biometric-per-run unlocking remain outside
this implemented candidate.

## Reproduction and artifacts

```sh
./gradlew testOssDebugUnitTest lint assembleOssDebug
```

On Windows, run `scripts/Verify-Apk.ps1` with `ANDROID_HOME` and JDK 17 configured.
It verifies the packaged native engine rather than only trusting a build log.
The deliberately failing signing test is:

```sh
./gradlew :app:preOssReleaseBuild -x :rclone:buildAll
```

Without signing inputs, the expected rejection is `Release signing is
incomplete`. It must not produce an unsigned or debug-signed release.

The local delivery directory contains the five debug APKs, `verified-apks.json`,
engine provenance/module metadata, final app source identity, guide, this report
and relevant logs. Debug builds use `de.schuelken.cloudbridge.debug`, separate
from the release app and its storage. They are not a signed in-place upgrade
of a pre-existing release installation.
