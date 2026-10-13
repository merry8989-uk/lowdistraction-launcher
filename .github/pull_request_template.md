## What this changes

<!-- One or two sentences. -->

## Why

<!-- The problem it solves. -->

## Checks

- [ ] The app still has no INTERNET permission (the build enforces this)
- [ ] No trackers, analytics or telemetry added
- [ ] `versionCode` and `versionName` bumped in `app/build.gradle.kts` if this is a release
- [ ] A `fastlane/metadata/android/en-US/changelogs/<versionCode>.txt` note added for the release
- [ ] Build APK and CodeQL are green
