# Windows build guide

Status checked 2026-09-25. This guide is for local development on the current Rareities/CloudBridge fork, not for producing an accepted release.

## Prerequisites

- Git for Windows with its HTTPS remote helper available.
- Temurin JDK 17 (or a compatible JDK supported by the pinned Gradle wrapper).
- Go 1.26.0 or newer, as specified in `gradle.properties`.
- Android SDK with API 36 build tools and the exact NDK version pinned in `gradle.properties`.
- Enough disk space for the Android SDK/NDK, Gradle dependencies, Go module cache, and four Android ABI builds.

Open a new PowerShell window and set paths for that session. Adjust the JDK folder if yours differs:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-17.0.XX-hotspot'
$env:ANDROID_HOME = Join-Path $env:LOCALAPPDATA 'Android\Sdk'
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
$env:PATH = "$env:JAVA_HOME\bin;$env:ANDROID_HOME\platform-tools;$env:PATH"

java -version
go version
git --version
```

Install the NDK version named by `de.schuelken.cloudbridge.ndkVersion` in `gradle.properties` using Android Studio's SDK Manager or `sdkmanager`. Use `ANDROID_HOME` or `local.properties` for the SDK location. Do not change the repository's pinned versions just to work around a machine-specific issue.

## Build and test

From the repository root:

```powershell
.\gradlew.bat --version
.\gradlew.bat :app:testOssDebugUnitTest :app:assembleOssDebug
```

This is the full path: Gradle checks out the immutable rclone ref in `gradle.properties`, then builds the native libraries and app. The first run needs network access and can take substantially longer than an incremental build. The resulting development APKs are under `app\build\outputs\apk\oss\debug\`.

If Git reports that `git-remote-https` is unavailable, repair/install Git for Windows with its HTTPS transport. Do not disable TLS verification or substitute an unreviewed local engine checkout. If SDK archives or dependency JARs fail with access errors, use a task-scoped writable SDK/cache mirror or CI; do not reset broad user caches or alter permissions recursively.

For an isolated Android JVM-test diagnostic only, the native checkout/build tasks can be excluded:

```powershell
.\gradlew.bat :app:testOssDebugUnitTest -x :rclone:checkoutRclone -x :rclone:buildAll
```

Record these exclusions. This does not validate the app-pinned rclone integration and does not produce a complete APK.

## CI and release status

The current `android.yml` workflow runs OSS debug unit tests and packages an OSS debug APK. It does not upload the APK or publish a release. The 2026-09-25 read-only refresh returned no open PRs or combined status checks, and no PR-triggered workflow runs; the connector query did not cover push/manual runs or provide a complete release inventory. Do not trigger a master push with an empty commit.

Release variants require a readable production keystore plus `storeFile`, `keyAlias`, `storePassword`, and `keyPassword`, supplied in ignored local `keystore.properties` or the corresponding `CB_*` environment variables. Release tasks never fall back to the Android debug key. No fork release is available; do not distribute a release build until signing-certificate continuity, provenance, migration/rollback, security, provider, and device acceptance gates pass.

## Acceptance-device reminder

The handoff's acceptance target is a Galaxy S26 on One UI 8.5/9. Record the exact device model, Android API level, One UI version, and firmware when testing. Emulator tests or another phone do not satisfy that gate. Live Proton testing is a separate gate and may use only a verified disposable area; never delete an existing `Proton:RoundSync-Test` directory.
