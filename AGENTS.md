# CloudBridge Agent Guidelines

Android cloud file manager wrapping [rclone](https://rclone.org). Fork of RCX / rcloneExplorer.

## Core Rules

- Ask clarifying questions only when requirements are ambiguous, risky, conflict with existing patterns, or have meaningful tradeoffs. Do not ask for trivial tasks.
- Make the smallest correct change. Do not refactor unrelated code or over-engineer.
- Follow this codebase's existing patterns before applying generic best practices.
- If best practice and minimal change conflict, ask the user before proceeding.
- Use targeted searches before reading large files.
- Never commit secrets, credentials, API keys, or unrelated user changes.

## Project Structure

| Module | Purpose |
|---|---|
| `app` | Main Android application |
| `rclone` | Cross-compiles rclone (Go) into `librclone.so` per ABI |
| `safdav` | SAF/WebDAV bridge library (`io.github.x0b.safdav`) |

- Package namespace: `ca.pkay.rcloneexplorer` (legacy from rcloneExplorer fork).
- Application ID: `de.schuelken.cloudbridge`.
- Newer code lives under `de.schuelken.cloudbridge.*`.
- `app/src/rcx/` is an additional source set for RCX-specific utilities.
- Product flavors are `oss` and `rs` in the `edition` dimension. Most work should target `oss` unless the task says otherwise.

## rclone Upgradeability

Keep this repository easy to upgrade from upstream rclone.

- The rclone source is controlled by `de.schuelken.cloudbridge.rCloneRepoUrl` and `de.schuelken.cloudbridge.rCloneRef` in `gradle.properties`.
- To upgrade rclone, prefer changing only `rCloneRef` (and `rCloneRepoUrl` only if switching forks), then rebuild.
- Do not modify generated or fetched rclone source under `rclone/cache/`.
- Do not reintroduce the deprecated `rclone/patches/` flow. Keep backend changes in the
  selected Rareities/rclone repository and verify provider behavior before carrying forward
  any app-specific behavior from an older engine fork.
- Fix defects at the layer that owns their behavior: CloudBridge for Android orchestration, UI, lifecycle and scheduling; Rareities/rclone or its responsible dependency for backend, protocol, filesystem, persisted-engine-state and library-concurrency behavior. Use an app-side mitigation only when it is genuinely an integration concern or a lower-layer fix cannot be made safely, and record that reason.
- Keep Go-side rclone changes in the Rareities fork at `https://github.com/Rareities/rclone`; do not vendor local source patches here. Independently test the engine/library change before app integration.

## Build

Prerequisites: Go 1.26+, JDK 17, Android SDK with NDK. Versions are pinned in `gradle.properties`; check there first if builds break.

```sh
./gradlew :app:testOssDebugUnitTest :app:assembleOssDebug
```

- The normal app build checks out the immutable Rareities/rclone ref from `gradle.properties` and builds its native libraries for the configured ABIs. Network access and Git HTTPS support are required unless the exact source and dependencies are already cached.
- APK output is under `app/build/outputs/apk/oss/debug/`.
- ABI splits: `armeabi-v7a`, `arm64-v8a`, `x86`, `x86_64`, `universal`.
- Release variants never fall back to the Android debug key. Release packaging requires all production signing values and a readable keystore; that is a build gate, not permission to publish a release.
- The current GitHub Android workflow tests and packages an OSS debug APK only. It does not upload artifacts or publish a release.

## Verification

Run the checks that match the change. Before any commit or push, required checks must pass or the failure must be explained to the user.

```sh
./gradlew :app:testOssDebugUnitTest
./gradlew :app:assembleOssDebug
```

- JVM tests live in `app/src/test/`; instrumentation tests live separately and are not executed by the current `android.yml` workflow.
- For an isolated app-JVM diagnostic only, it may be necessary to exclude `:rclone:checkoutRclone` and `:rclone:buildAll`. Such a run does not validate native integration or produce a complete APK; record those exclusions with the test result.
- Lint baselines exist in `app/` and `safdav/`; `abortOnError` is enabled and `MissingTranslation` is a warning.
- Skip `assembleOssDebug` only for docs-only changes that cannot affect the build.

## Bug And Feature Workflow

1. Research the codebase to find the root cause or integration point.
2. Create a short plan when the change is non-trivial. List files to change, intended logic, and expected effect.
3. Implement only the smallest correct change.
4. Review the result for correctness, architectural consistency, edge cases, regressions, performance, and security.
5. If using an agent tool that supports subagents, use one for plan or code review on non-trivial changes.
6. If review finds issues, fix them and re-review. If issues persist, revert only your own changes and ask the user how to proceed.

## Versioning And Git

- Version codes end in `0`; the last digit is reserved for ABI multipliers.
- For release/build-version work, update the patch version and `versionCode` together.
- Use Conventional Commits for commit messages, such as `fix: correct login validation` or `feat: add Internxt token refresh`.
- Do not push unless explicitly asked.

## CI Workflows

- `android.yml`: runs on pushes and pull requests targeting `master`, and supports manual dispatch. It runs `:app:testOssDebugUnitTest` and `:app:assembleOssDebug`; it does not upload an APK artifact or publish a release.
- Other workflow files have separate triggers and scopes. Read the checked-in YAML before relying on them; do not infer that a workflow ran or passed without current GitHub Actions evidence.
- The 2026-09-25 GitHub refresh found no default-branch protection. Refresh this before publication, and use a reviewed pull request rather than pushing directly to `master` regardless of server-side settings.

## Gotchas

- Windows builds require the rclone module's Windows-specific NDK handling (`.cmd` suffixes and CRLF to LF conversion).
- Debug builds append `.debug` to the application ID, so debug and release can coexist on a device.
- `local.properties` with `sdk.dir` or `ANDROID_HOME` is required for rclone cross-compilation.
- Translations are managed via Weblate and Crowdin; do not manually edit localized `strings.xml` unless adding a new language.
