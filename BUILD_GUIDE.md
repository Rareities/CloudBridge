# CloudBridge build guide

Status checked 2026-09-25. These instructions describe the checked-in fork; a local build is not release or device-acceptance evidence.

## Prerequisites

- JDK 17 (the checked-in Android CI uses Temurin 17).
- Go 1.26.0 or newer; the current minimum is in `gradle.properties`.
- Android SDK platform/build tools for API 36 and the NDK version pinned in `gradle.properties`.
- Git with HTTPS transport. Gradle checks out the exact immutable Rareities/rclone commit listed by `de.schuelken.cloudbridge.rCloneRef`.
- Network access for uncached Gradle, Go, Android SDK/NDK, rclone source, and Go module dependencies.

Check `gradle.properties` before upgrading or changing any toolchain. Do not edit fetched/generated files under `rclone/cache/` or bypass the immutable engine ref.

## Local test and debug APK

On Linux/macOS:

```sh
./gradlew :app:testOssDebugUnitTest :app:assembleOssDebug
```

On Windows PowerShell:

```powershell
.\gradlew.bat :app:testOssDebugUnitTest :app:assembleOssDebug
```

The normal app build checks out and compiles the pinned native rclone source. Do not exclude `:rclone:checkoutRclone` or `:rclone:buildAll` for a full integration build. Those exclusions are acceptable only for a clearly labeled, isolated app-JVM diagnostic; such a run is not native integration or APK evidence.

Debug APKs are written under `app/build/outputs/apk/oss/debug/`. The build may create per-ABI and universal variants. Debug signing is for development only.

## GitHub Actions

`.github/workflows/android.yml` runs on pushes and pull requests targeting `master` and supports manual dispatch. It runs the OSS debug unit tests and assembles an OSS debug APK. It does **not** upload an artifact or publish a release. Do not create an empty commit or push directly to `master` merely to trigger a build.

The read-only GitHub refresh on 2026-09-25 returned no open PRs, no combined status checks, and no PR-triggered workflow runs for the checked heads; the connector query was limited to PR-triggered runs. Push/manual workflow history and a complete release inventory were not verified. The refresh reported unprotected default branches. Recheck live status before relying on CI or opening a PR. A workflow definition is not evidence that it ran or passed.

## Release and signing gate

No verified Rareities/CloudBridge release artifact was identified in this status snapshot, and the checked-in Android workflow contains no release-publishing job. Release package/sign/bundle tasks fail closed unless a readable production keystore and all four signing values are configured through ignored local `keystore.properties` or the documented `CB_*` environment variables. Never commit signing material. Passing a release Gradle task is not authorization to publish: certificate continuity, provenance, compatibility, security, device/provider acceptance, and the master handoff gates must also pass.

## Current limits

The host verification recorded in `EXECUTION_LEDGER.md` is JVM-only unless a row explicitly says otherwise. Android instrumentation, a Galaxy S26 on One UI 8.5/9 with actual API and firmware recorded, live Proton, and release signing remain separate gates. A debug APK, emulator, compile-only result, or GitHub workflow file does not substitute for them.
