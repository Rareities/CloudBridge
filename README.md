# CloudBridge - Rclone for Android
[![license: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](https://github.com/Rareities/CloudBridge/blob/master/LICENSE)
[![Android CI](https://github.com/Rareities/CloudBridge/actions/workflows/android.yml/badge.svg)](https://github.com/Rareities/CloudBridge/actions/workflows/android.yml)

A cloud file manager for Android, powered by rclone.

> This is the Rareities fork of CloudBridge. The 2026-09-25 status snapshot did not verify a complete release inventory, and this README provides no verified APK download. The [upstream documentation](https://thies2005.github.io/CloudBridge/) may describe older behavior or releases; use the build guides in this repository for the fork.


## Screenshots
<table>
  <tr style="border:none">
    <td style="border:none">
      <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/1.jpg" width="360vh" />
    </td>
    <td style="border:none">
      <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/2.jpg" width="360vh" />
    </td>
    <td style="border:none">
      <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/3.jpg" width="360vh" />
    </td>
  </tr>
</table>


## Features
The bullets below describe source-level capabilities, not completed WP12 or device/provider acceptance. See [FEATURE_SURVIVAL_MATRIX.md](FEATURE_SURVIVAL_MATRIX.md) for current verification gaps.
|                                                            Cloud Access                                                             |                                             rclone crypt content encryption                                             |                                                         Integrated Experience                                                         |
|:-----------------------------------------------------------------------------------------------------------------------------------:|:--------------------------------------------------------------------------------------------------------------------------------------------------:|:-------------------------------------------------------------------------------------------------------------------------------------:|
| <img src="https://github.com/Rareities/CloudBridge/blob/master/docs/cloud-computing.png?raw=true" alt="Cloud Access" width="144" />  | <img src="https://github.com/Rareities/CloudBridge/blob/master/docs/locked-padlock.png?raw=true" alt="Crypt encryption" width="108" /> | <img src="https://github.com/Rareities/CloudBridge/blob/master/docs/smartphone.png?raw=true" alt="Integrated Experience" width="132"/> |
|                                             Use your cloud storage like a local folder.                                             |                                         rclone crypt can encrypt configured content before upload; setup and recovery keys are your responsibility.                                          |                                  Source capabilities do not imply completed device/provider acceptance.                                   |

- **File Management** (list, view, download, upload, move, rename, delete files and folders)
- **Streaming and serving source paths** include FTP, HTTP, WebDAV and DLNA; protocol security, lifecycle, device compatibility and runtime acceptance are still under review.
- **Integration** (Access local storage devices and share files with the application to store them on a remote)
- **Provider options** through rclone; CloudBridge setup and provider compatibility vary and are not all acceptance-tested in this fork
- **Material 3 Design** (Dark theme)
- **Configured Android ABIs** include ARM, ARM64, x86 and x64; this build configuration does not prove a packaged artifact or runtime compatibility.
- **Declared API floor** is Android API 23 (Android 6); device compatibility and acceptance are not established by the manifest alone.
- **Storage Access Framework (SAF)** ([fork-specific notes](docs/internals.html#saf)) for user-granted document-tree access; SD/USB behavior remains device/provider dependent.
- **Task management and schedule-trigger source paths** are present; timing, background lifecycle, and device acceptance remain incomplete.


## Installation

No verified Rareities/CloudBridge release or downloadable APK artifact is identified in this documentation snapshot. Do not install an upstream APK expecting it to contain this fork's changes. See [BUILD_GUIDE.md](BUILD_GUIDE.md) for a local development build; debug APKs are not production releases.

> **Bisync safety:** the current fork has a read-only preview/review path, but safe initialization, apply, and recovery are not available. Do not use this build for two-way Bisync mutations or treat a preview as permission to run one.

The architecture table below names configured build variants; it is not a list of downloadable files. No Rareities APK is currently published.
| CPU architecture | Notes | Configured ABI (not a download) |
|:---|:--|:---:|
|ARM 32 Bit | older devices | ```armeabi-v7a``` |
|**ARM 64 Bit** | **most devices** | ```arm64-v8a``` |
|Intel/AMD 32 Bit | some TV boxes and tablets | ```x86``` |
|Intel/AMD 64 Bit | some emulators | ```x86_64``` |

The source currently declares Android API 23 as its minimum, but that does not establish device compatibility or acceptance. Use the build guides only for development; there is no released APK to select.

The existing Google Play listing, if available in your region, is not evidence that this fork has been released or that its signing identity is continuous.

## Usage
Start with the fork-specific [user guide](USER_GUIDE.md). Some inherited upstream documentation may describe behavior or releases that do not apply to this fork. Build details are in [BUILD_GUIDE.md](BUILD_GUIDE.md) and [WINDOWS_BUILD_GUIDE.md](WINDOWS_BUILD_GUIDE.md).


## Intents
The legacy external-intent recipe that directly starts `Services.SyncService` is not currently supported: the service is declared non-exported in the manifest. Do not configure Tasker or another app to call it. A secure, documented external automation contract remains to be audited before this integration can be claimed as preserved.


## Libraries
- [Rareities/rclone](https://github.com/Rareities/rclone) is the engine repository configured for this fork; [upstream rclone](https://github.com/rclone/rclone) is the upstream project. See https://rclone.org/donate/ to support rclone.
- [Jetpack AndroidX](https://developer.android.com/license) - AppCompat, RecyclerView, ConstraintLayout, WorkManager, DataStore, Lifecycle, SplashScreen and more.
- [Material Components](https://github.com/material-components/material-components-android) - Material 3 widgets and theming.
- [Kotlin Coroutines](https://github.com/Kotlin/kotlinx.coroutines) and [kotlinx.serialization](https://github.com/Kotlin/kotlinx.serialization) - Async work and JSON serialization.
- [Floating Action Button SpeedDial](https://github.com/leinardi/FloatingActionButtonSpeedDial) - A Floating Action Button Speed Dial implementation for Android that follows the Material Design specification.
- [Glide](https://github.com/bumptech/glide) - An image loading and caching library for Android focused on smooth scrolling.
- [MarkdownJ](https://github.com/myabc/markdownj) - converts markdown into HTML.
- [OkHttp](https://github.com/square/okhttp) - HTTP client used for provider APIs and authentication.
- [Jackson](https://github.com/FasterXML/jackson-core) - JSON processing.
- [NanoHTTPD](https://github.com/NanoHttpd/NanoHttpd) - Embedded HTTP server used for serving files and the SAF/WebDAV bridge.
- [Recyclerview Animators](https://github.com/wasabeef/recyclerview-animators) - An Android Animation library which easily add itemanimator to RecyclerView items.
- [Toasty](https://github.com/GrenderG/Toasty) - The usual Toast, but with steroids.
- [AppIntro](https://github.com/AppIntro/AppIntro) - Onboarding introduction screens.
- [RFC 3339 Date Parser](https://github.com/x0b/rfc3339parser) - Timestamp parsing.
- [android-retrofuture / android-retrostreams](https://github.com/streamsupport/streamsupport) - Java 8+ streams and CompletableFuture backports.
- Icons from [Flaticon](https://www.flaticon.com) courtesy of [Smashicons](https://www.flaticon.com/authors/smashicons) and [Freepik](https://www.flaticon.com/authors/freepik)


## Contact & Contributions
The upstream contact below is inherited attribution, not a confirmed security or support contact for this fork. The Rareities fork currently has GitHub issues disabled; focused pull requests can be opened against the repository. Never include credentials, live configuration, or unredacted logs.

Upstream contact: [136268370+thies2005@users.noreply.github.com](mailto:136268370+thies2005@users.noreply.github.com).

## Developing

You should first make sure you have:

- Go 1.26+ installed and in your PATH
- Java installed and in your PATH
- Android SDK command-line tools installed OR the NDK version specified in `gradle.properties`
  installed

You can then build the app normally from Android Studio or from CLI by running:

```sh
# Debug build
./gradlew assembleOssDebug

```

For a local OSS debug build and its tests, see [BUILD_GUIDE.md](BUILD_GUIDE.md). Release variants require the production signing key and do not authorize publication by themselves.


## License
This app is licensed under the [GPLv3](https://github.com/Rareities/CloudBridge/blob/master/LICENSE). By submitting a pull request, you agree that your contributions are licensed under the GPLv3 as well.


## About this app
CloudBridge is a fork of [**Round-Sync**](https://github.com/newhinton/Round-Sync) by **Felix Nüsse**<sup>[newhinton](https://github.com/newhinton)</sup>, which is a fork of [**RCX**](https://github.com/x0b/rcx) by **x0b**<sup>[x0b](https://github.com/x0b)</sup>, which is itself a fork of [**rcloneExplorer**](https://github.com/patrykcoding/rcloneExplorer) by **Patryk Kaczmarkiewicz**<sup>[patrykcoding](https://github.com/patrykcoding)</sup>.

This repository is a maintained fork. Release cadence, signing identity, and feature compatibility are tracked independently; no verified fork release artifact was identified in the current status snapshot. See [requirements](REQUIREMENTS_MATRIX.md), [test evidence](TEST_REPORT.md), [security review](SECURITY_REVIEW.md), and [release readiness](RELEASE_READINESS.md) for verified status and open gates.

The optional update notification now checks Rareities/CloudBridge and opens this fork's release page. It is notification-only (no in-app download or install), defaults off, and stable builds ignore prereleases. The fork had no verified release candidate in the 2026-09-25 GitHub refresh; updater runtime, opt-out and notification behavior still need integrated/device validation before distribution.

If you want to convey a modified version (fork), we ask you to use a different name, app icon and package id as well as proper attribution to avoid user confusion.


## New Features in this Fork
Source paths for additional providers such as Internxt and Drime are present, but their compatibility and acceptance in this fork are not established by this statement. CloudBridge uses a separately pinned rclone revision; update cadence and provider parity are not guaranteed.
