# About this personal fork

This is a fork of [CloudBridge](https://github.com/thies2005/CloudBridge), which
is a fork of [Round Sync](https://github.com/newhinton/Round-Sync), which comes
from [RCX](https://github.com/x0b/rcx), originally a fork of
[rcloneExplorer](https://github.com/patrykcoding/rcloneExplorer).

The main goal is to build CloudBridge with a reviewed, pinned version of the
latest rclone beta/development code, bring in selected Proton Drive patches from
[OSS-Singularity's rclone fork](https://oss-oo.io/OSS-Singularity/rclone) used by
its Proton Drive Linux work, and implement bisync inside CloudBridge. My
[rclone fork](https://github.com/Rareities/rclone) contains the engine changes;
this app pins an exact reviewed commit rather than following a moving branch.
I have also made other improvements and fixes to both rclone and the app.
The [full changelog and commit history](docs/FORK_CHANGELOG.md) explains what changed.

**DISCLAIMER: THIS IS HEAVILY VIBE CODED.** I know very little about Android app
development, but I know how to work with AI and have spent weeks on this project.
I think it works great for my needs, and I have taken enough steps personally to
feel comfortable using it. That is my own experience, not a guarantee that it
will work safely in every setup: **use it at your own risk**, and keep independent
backups of important files.

The reason I started this was to have a Proton Drive bisync service that could
automatically sync my folders. That use case guides the project. This is simply
a personal project; if it benefits someone else, I will be happy. Issues, pull
requests, comments and concerns are welcome. Published candidates and their
validation limits are described in the changelog and release notes.

---

# CloudBridge - Rclone for Android
[![license: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](https://github.com/thies2005/CloudBridge/blob/master/LICENSE) [![Latest Downloads](https://img.shields.io/github/downloads/thies2005/CloudBridge/latest/total)](https://github.com/thies2005/CloudBridge/releases) [![GitHub release](https://img.shields.io/github/v/release/thies2005/CloudBridge?include_prereleases)](https://github.com/thies2005/CloudBridge/releases/latest)
[![Documentation](https://img.shields.io/badge/Documentation-GitHub_Pages-4aad4e)](https://thies2005.github.io/CloudBridge/)
[![Android Lint](https://github.com/thies2005/CloudBridge/actions/workflows/lint.yml/badge.svg)](https://github.com/thies2005/CloudBridge/actions/workflows/lint.yml)
A cloud file manager, powered by rclone.
This fork adds a Proton Drive bisync candidate with preview/approval, durable
recovery state, advanced flags, scheduling and an expanded task editor.
Start with the [bisync guide](docs/BISYNC_GUIDE.md) and
[credential-storage decision](docs/ADR-002-native-rclone-config.md).
Upstream screenshots below show the existing file manager, not a device-verified
demonstration of the new bisync controls.


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
|                                                            Cloud Access                                                             |                                    256 Bit Encryption<sup>[1](https://rclone.org/crypt/#file-encryption)</sup>                                     |                                                         Integrated Experience                                                         |
|:-----------------------------------------------------------------------------------------------------------------------------------:|:--------------------------------------------------------------------------------------------------------------------------------------------------:|:-------------------------------------------------------------------------------------------------------------------------------------:|
| <img src="https://github.com/thies2005/CloudBridge/blob/master/docs/cloud-computing.png?raw=true" alt="Cloud Access" width="144" />  | <img src="https://github.com/thies2005/CloudBridge/blob/master/docs/locked-padlock.png?raw=true" alt="256 Bit End-to-End Encryption" width="108" /> | <img src="https://github.com/thies2005/CloudBridge/blob/master/docs/smartphone.png?raw=true" alt="Integrated Experience" width="132"/> |
|                                             Use your cloud storage like a local folder.                                             |                                         Keep your files private on any cloud provider with crypt remotes.                                          |                                  Don't give up features or comfort just because it runs on a phone.                                   |

- **File Management** (list, view, download, upload, move, rename, delete files and folders)
- **Streaming** (Stream media files, serve files and directories over FTP, HTTP, WebDAV or DLNA)
- **Integration** (Access local storage devices and share files with the application to store them on a remote)
- **Many cloud storage providers** (all via rclone config import, some without ui-setup)
- **Material 3 Design** (Dark theme)
- **All architectures** (runs on ARM, ARM64, x86 and x64 devices, Android 6+)
- **Storage Access Framework (SAF)** ([see docs](https://thies2005.github.io/CloudBridge/internals.html#storage-access-framework)) for SD card and USB device access.
- **Scoped shortcuts and external requests** with explicit confirmation for untrusted callers.
- **Task Management** to allow regular runs of your important tasks!


## Installation

Use this fork's reviewed release artifacts when available. The **Bisync debug
APK** workflow provides candidate APKs for testing; they use a separate debug
application ID and cannot replace a differently signed release installation.
See the [guide and device validation gates](docs/BISYNC_GUIDE.md) before using
bisync on important data. Upstream CloudBridge/Google Play downloads do not
automatically contain this fork's changes.
| CPU architecture | Where to find | APK identifier |
|:---|:--|:---:|
|ARM 32 Bit | older devices | ```armeabi-v7a``` |
|**ARM 64 Bit** | **most devices** | ```arm64-v8a``` |
|Intel/AMD 32 Bit | some TV boxes and tablets | ```x86``` |
|Intel/AMD 64 Bit | some emulators | ```x86_64``` |

If you don't know which version to pick use ```cloudbridge_v<version>-oss-universal-release.apk```. Most devices run ARM 64 Bit, and 64 Bit devices often can also run the respective 32 bit version at lower performance. The app runs on any phone, tablet or TV with Android 6 or newer, as long as you have a touchscreen or mouse.

[<img src="https://play.google.com/intl/en_us/badges/static/images/badges/en_badge_web_generic.png"
    alt="Get it on Google Play"
    height="80">](https://play.google.com/store/apps/details?id=de.schuelken.cloudbridge)

## Usage
[See the documentation](https://thies2005.github.io/CloudBridge/).


## Intents
External task requests open `ShortcutServiceActivity`. A task ID alone does
not authorise execution: requests without a valid app-issued shortcut capability
require visible user confirmation. Existing shortcuts may need recreation.

| Intent          |                   Content                   |                 |
|:----------------|:-------------------------------------------:|----------------:|
| packageName     |           de.schuelken.cloudbridge            |                 |
| className       | ca.pkay.rcloneexplorer.Activities.ShortcutServiceActivity |                 |
| Action          |                 START_TASK                  |                 |
| Long Extra      |                    task                     |        idOfTask |


## Libraries
- [rclone](https://github.com/rclone/rclone) - Calling this a library is an understatement. Without rclone, there would not be CloudBridge. See https://rclone.org/donate/ to support rclone.
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
- [App Update Checker](https://github.com/Sharkaboi/AppUpdateChecker) - Checks GitHub for new app versions.
- [android-retrofuture / android-retrostreams](https://github.com/streamsupport/streamsupport) - Java 8+ streams and CompletableFuture backports.
- Icons from [Flaticon](https://www.flaticon.com) courtesy of [Smashicons](https://www.flaticon.com/authors/smashicons) and [Freepik](https://www.flaticon.com/authors/freepik)


## Contact & Contributions
Whether you have a question, found a bug, or have an idea for a new feature — you are welcome to write me at [136268370+thies2005@users.noreply.github.com](mailto:136268370+thies2005@users.noreply.github.com). Pull requests and feature requests are always welcome!

## Developing

You should first make sure you have:

- Go 1.26+ installed and in your PATH
- JDK 17 installed and in your PATH
- Android SDK command-line tools installed OR the NDK version specified in `gradle.properties`
  installed

You can then build the app normally from Android Studio or from CLI by running:

```sh
# Debug build
./gradlew assembleOssDebug

# or release build
./gradlew assembleOssRelease
```


## License
This app is released under the terms of the [GPLv3 license](https://github.com/thies2005/CloudBridge/blob/master/LICENSE). By submitting a pull request, you agree that your contributions are licensed under the GPLv3 as well.


## About this app
CloudBridge is a fork of [**Round-Sync**](https://github.com/newhinton/Round-Sync) by **Felix Nüsse**<sup>[newhinton](https://github.com/newhinton)</sup>, which is a fork of [**RCX**](https://github.com/x0b/rcx) by **x0b**<sup>[x0b](https://github.com/x0b)</sup>, which is itself a fork of [**rcloneExplorer**](https://github.com/patrykcoding/rcloneExplorer) by **Patryk Kaczmarkiewicz**<sup>[patrykcoding](https://github.com/patrykcoding)</sup>.

CloudBridge was created to provide active maintenance, regular rclone updates, easy accessibility via releases on GitHub and Google Play, and extra features such as robust Internxt support.

If you want to convey a modified version (fork), we ask you to use a different name, app icon and package id as well as proper attribution to avoid user confusion.


## New Features in this Fork
This fork adds explicit support and fixes for the following providers:
- **Internxt**: Decentralized cloud storage. Included is a robust integration with automatic token renewal and state persistence to ensure the remote connection does not expire.
- **Drime**: Cloud storage provider.
- **Always up to date**: CloudBridge is regularly updated to the newest rclone version, so new providers, features and fixes arrive quickly.
