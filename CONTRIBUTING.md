# Contributing

Thanks for wanting to help. A few things keep this project what it is.

## The rules that do not bend

- **No internet access.** The app must never be able to reach the network. The
  manifest strips the `INTERNET` permission and the build fails if that guard is
  ever removed.
- **No trackers, analytics or telemetry.** Nothing about the user leaves the
  device.
- **No paid features.** There is no Pro version and there will not be one.

## Working on it

    git clone https://github.com/merry8989-uk/lowdistraction-launcher.git
    cd lowdistraction-launcher
    ./gradlew assembleDebug        # debug build
    ./gradlew assembleRelease      # release build

Release builds are signed from repository secrets in CI; locally a release build
falls back to the debug key so it still compiles.

## Releases

Every release is a tag named `v<versionName>` on a green commit. Before tagging:

1. Bump `versionCode` and `versionName` in `app/build.gradle.kts`.
2. Add `fastlane/metadata/android/en-US/changelogs/<versionCode>.txt` describing
   what changed. That file becomes the release notes.
3. Wait for Build APK and CodeQL to pass, then tag.

The F-Droid metadata lives in `fdroiddata` and is updated separately with the new
version code and the full 40-character commit hash.

## Reporting

Use the issue templates. For anything security related, see `SECURITY.md`.
