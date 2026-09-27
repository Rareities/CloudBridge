# Contributing to CloudBridge

We welcome any contribution to CloudBridge, and there are multiple ways to contribute:

 - [Reporting a bug](#reporting-a-bug)
 - [Localize CloudBridge into your language](#localize-CloudBridge)
 - [Developing](#developing)
 - [Submitting a pull request](#submitting-a-pr)
 - [Requesting a new features](#requesting-a-new-feature)


## Reporting a bug
No one likes it if something goes wrong. However, before submitting a bug report, please make sure to check the following links:

- [CloudBridge source repository](https://github.com/Rareities/CloudBridge)
- [Inherited upstream documentation](https://thies2005.github.io/CloudBridge/) (may describe older behavior and releases)
- [rclone documentation](https://rclone.org/)
- [rclone forum](https://forum.rclone.org/)

A lot of problems are errors in `rclone.conf`. Reproduce with synthetic or disposable credentials first. Do not export a live configuration to Termux, a desktop, or a ticket just to reproduce a bug. If testing in Termux or on a PC is necessary, use a separate disposable configuration.

If a problem is reproducible in rclone itself, follow rclone's current reporting guidance after confirming it without exposing account data.

If a private issue-reporting channel or the upstream template is used, include the following non-secret diagnostic details:
 - App version (e.g. `v1.11.4`)
 - Exact Android version (e.g. `8.1.0`)
 - Your device model and manufacturer
 - An exact list of steps that leads to your issue. If you collect logs, inspect them locally before sharing.
 - Never attach raw logs or an exported/live `rclone.conf`. Logs and configuration can contain credentials, tokens, remote names, account identifiers, paths, and provider-specific data.
 - If a configuration example is essential, create a minimal synthetic file or manually redact a copy, then inspect every line for secrets, identifiers, URLs, and private paths before sharing it. Prefer disposable credentials.
 - We may ask you to reproduce the problem on a PC or in Termux using a separate disposable configuration; do not copy real credentials for that test.


## Localize CloudBridge
 - Use the configured Weblate/Crowdin translation project and follow its current contribution instructions.
 - Do not hand-edit generated localized `strings.xml` files. Changes to source-language strings belong in app source and should be reviewed separately from translations.
 - Keep placeholders, formatting tokens, and XML escapes intact.
   Here is an example of translating into **bn-BD**

   Default string values **en-US**
   ```sh
   <string name="app_name">CloudBridge</string>
   <string name="app_description">Rclone for Android</string>
   <string name="app_short_name">CloudBridge</string>
   ```
   Translated string values into **bn-BD**
   ```sh
   <string name="app_name">রাউন্ড সিঙ্ক</string>
   <string name="app_description">অ্যান্ড্রয়েডের জন্য আরক্লোন</string>
   <string name="app_short_name">রাউন্ড সিঙ্ক</string>
   ```


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

The current CI builds and tests OSS debug only. Release variants require a production keystore and credentials and must not be treated as publishable until signing continuity, provenance, and acceptance gates are documented. See [BUILD_GUIDE.md](BUILD_GUIDE.md) and [WINDOWS_BUILD_GUIDE.md](WINDOWS_BUILD_GUIDE.md).


## Submitting a PR
Here are a few tips on getting your PR merged:

1. Keep your PR small. Small PRs are easier to review, easier to test and as a result can be merged quickly. If this is your first PR to CloudBridge, keep it very small.
2. Keep your PR focussed. Your PR should have a single, specific purpose. If you discover something else you'd like to improve while working on your PR, only include it if there's a direct link to the purpose of the PR.
3. Use the style of the existing code base. Use idiomatic code whenever possible. If you have performance concerns, use the profiler to test your assumptions.
4. Rebase your branch before creating your PR.


## Requesting a new feature
The Rareities fork currently has its GitHub issue tracker disabled. For code changes, use a focused pull request against the current default branch. Do not assume the upstream issue tracker describes this fork's state. Please avoid '+1', 'me to' or similar comments on upstream discussions and use :+1: [reactions](https://github.blog/2016-03-10-add-reactions-to-pull-requests-issues-and-comments/) instead.

The remaining template guidance below is inherited upstream material and applies only if that upstream issue form is used; it is not a currently enabled issue form for the Rareities fork. A future fork feature-request process should ask for:
- Searching for existing issues and discussions that already cover your request. We may close your request without comment if you fail to do this.
- For anything related to data transfer or accessing files on your cloud storage, please first check if your idea works in rclone. If it does not work there, it will probably also not work in CloudBridge.
- Asking yourself what you can do to create this feature.
- The version of CloudBridge you are using.

You will also be asked two free-form questions:
> #### What problem are you trying to solve?

Describe what you are trying to achieve. This may include a series of steps if you are using rclone as part of a larger workflow, or may just be a single action. **Do not describe possible solutions.** Keep your ideas for the next question. 

You can describe this as a problem ("I cannot find a file"), or as a goal you want to achieve ("I would like to stream a video on my TV").

> #### What should CloudBridge be able to do differently to help with this problem?

Describe how you would solve your problem. This may include additional buttons, options, menus, dialogs, etc. 

This two-step approach allows us to to design general solutions, that work not just for your specific situation, but for the broader CloudBridge user base. It also makes it easier for other community to join the discussion and suggest different solutions.

Please keep in mind that CloudBridge and rclone are developed by volunteers.
