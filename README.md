# Low Distraction

A minimal, **text-only** Android launcher. Your home screen becomes a plain
list of app names — no icons, no wallpaper, no widgets, no folders, no dock, no
feed — plus a short **pause** before any app opens, and an assistive-touch
bubble when you want one.

It is also **unable to reach the internet**: the app ships with no internet
permission at all, and a build-time check fails the build if that ever changes.

Current version: **1.28** (`versionCode` 29) · Licence: **AGPL-3.0**

---

## The idea

Most launchers are designed to be looked at. This one is designed to be left.

A launcher is the first thing you see when you unlock a phone, dozens of times
a day, and every modern home screen is an invitation: a wallpaper to admire,
icons to arrange, widgets to check, a search bar that answers anything, a feed
that never ends. Low Distraction takes all of that away. What is left is a text
list, a search box you have to actually type into, and one deliberate moment of
friction before an app opens.

It is a small, unglamorous tool with a single opinion: **the phone should be
boring on purpose.**

## How it works

1. **Set it as your Home app.** Install, then *Settings → Set as default
   launcher*, which opens Android's own Home-app picker. Pick Low Distraction,
   press Home, and the text list is your home screen.
2. **You see names, not icons.** Apps appear as plain text in a single column,
   in the theme's typeface.
3. **You tap an app and hit a pause.** The screen asks *“Do you really need
   this right now?”* with a countdown. **Tap anywhere to cancel** — the whole
   screen is the cancel button. Let it run out and the app opens.
4. **Or you reach for the bubble.** A small assistive-touch circle floats over
   everything; long-press it for a radial wheel of your pinned apps.

There is no third screen, no drawer, and no hidden gesture to learn. A
nine-step first-run guide walks through all of it once, and can be replayed any
time from *Settings → How to use*.

---

## Features

### The home screen

- **Text-only list** of installed launchable apps.
- **Clock + date header** (auto-updating `TextClock`).
- **Three display modes:** name only (the default), icon only, or icon + name.
- **Pinned grid** at the top for the apps you reach for constantly. You choose
  its shape — **1 to 10 rows by 1 to 4 columns**, three by two by default.
  Nothing is ever pinned for you.
- **17 sort orders:** alphabetical either way, newest/oldest installed,
  recently updated, most/least opened, most/least time spent, largest/smallest,
  shortest/longest name, and more. Each order can be switched off individually.
  The four that need usage access fall back to name order, with a note, if you
  decline.
- **Hidden apps:** take an app off the list without uninstalling it. It stays
  openable from *Settings → Hidden apps* and comes straight back when unhidden.

### Opening an app

- **The pause.** A full-screen question with a countdown you set — **anywhere
  from 1 second to 2 minutes**. Tap anywhere to cancel.
- **Tick sound.** A soft tick each second, with the last three a little
  brighter so you can hear it running out. The tick has its **own** switch, so
  the timer can run silently. The pause itself can be switched off too.
- **Auto-open on a unique match.** When a search narrows the list to exactly
  one app it launches by itself after a short pause — keep typing to cancel.
  Enter/Search opens the top match.
- **Double-tap** the header or an empty part of the list to **lock the screen**.

### Search

- The **left search box** filters your apps as you type.
- A **second, opt-in search box** appears once you switch on any source in
  *Settings → Search sources*. Sources: **settings shortcuts, contacts,
  calendar, files, and Dev.Check**. Every source is **off by default** and asks
  for its own permission when you turn it on.
- **Every source shows whether it holds its permission**, with a *Remove
  permission…* button beside it that opens this app's page in system settings.
- **Search inside files:** switch on *All files access* to search the whole of
  storage by name — documents, media, sub-folders — with no folder picking. If
  you would rather not grant that, add individual folders instead. Either way,
  nothing is uploaded and nothing is indexed anywhere but on the phone.

### The assistive bubble and the wheel

A draggable floating bubble, like AssistiveTouch.

- **1 tap** → Back · **2 taps** → Home · **3 taps** → Lock/sleep
- **Long-press** → the **spin wheel**: up to **8 pinned apps** around a ring,
  with a **+** slot while there is room. Drag to spin it — it keeps spinning
  with momentum and plays a soft chime as slots pass by. Tap a slot to open
  that app; **long-press a slot to remove it**.
- The **bottom row** of the wheel holds four quick actions: **Torch, Volume,
  Do Not Disturb and Break** (a five-minute break screen).
- **16 selectable wheel sounds**, all synthesised inside the app.
- **It gets out of the way of money.** The bubble hides itself over **96
  built-in payment, banking, brokerage, insurance and government packages**,
  and over any app whose name looks financial. Add your own, or exempt one that
  was caught by mistake. There is also an *Also pause accessibility* switch.

### Appearance

- **62 built-in themes** — 31 dark, 30 light, plus Graphite — all generated
  from a hue and a strength so they feel like one family.
- **Build your own theme** from four colours with an HSV picker.
- **Icon controls:** size (24–96), shape and outline.
- **Text controls:** size, weight (bold), letter-spacing.
- **Drifting glow:** the soft background glow can be turned off.

### Dev.Check

An optional, built-in device dashboard — off by default. Switch it on and a
small **Dev.Check** badge appears above the second search box; tap it for a
tabbed read-out of the device: **Hardware, System, Battery, Network, Apps,
Camera and Sensors** (the sensor tab shows live values that update as you hold
the phone). The same shortcuts also appear in the second search box while it is
on. No ads, no upsell, no Pro version.

### Gestures

| Gesture | Action |
| --- | --- |
| Long-press anywhere on the home screen | Open Settings |
| Swipe right in the list | Open the dialer |
| Swipe left in the list | Open the camera |
| Double-tap the header / empty list | Lock the screen |

---

## Privacy

**There is no analytics SDK, no telemetry service, no crash-reporting service,
no trackers, no ads, no accounts and no Pro features.** There is nowhere for
data to go, so nothing is sent anywhere in the first place.

That is worth being exact about, because the app does keep a **local
diagnostics log** — described below — and a log is only useful if it is
complete. What it records stays on the phone: the app has no internet
permission, so nothing can leave except through the export, share or mail
buttons you press yourself. It includes a **Crash reports** section with the
stack trace, and a **Usage (local analytics)** section that counts sessions,
screens, searches and feature use from the events already in the log. Both are
views over the local file. Neither is a service, and neither uploads.

The app **cannot reach the network**. The source manifest declares
`android.permission.INTERNET` with `tools:node="remove"`, which strips it out
of the merged manifest no matter what a dependency tries to add, and a
`checkNoInternet` guard in the Gradle build **fails the build** if that
declaration is ever removed. The released APK contains no internet permission
at all — you can verify that on the file.

### What it asks for, and when

Permissions that are off by default are never requested until you switch on the
feature that needs them.

| Permission | What it is for | Needed when |
| --- | --- | --- |
| `INTERNET` | Nothing — removed from the app by the build itself | never |
| `SYSTEM_ALERT_WINDOW` | Drawing the floating bubble over other apps | only if the bubble is on |
| `FOREGROUND_SERVICE`, `…_SPECIAL_USE`, `POST_NOTIFICATIONS` | Keeping the bubble alive with a dismissible notification | only if the bubble is on |
| `ACCESS_NETWORK_STATE`, `ACCESS_WIFI_STATE` | Reading your connection state so Dev.Check can display it (neither can open a connection) | only when you open Dev.Check |
| `READ_CONTACTS` | Searching contacts from the second search box | off by default |
| `READ_CALENDAR` | Searching the calendar from the second search box | off by default |
| `MANAGE_EXTERNAL_STORAGE`, `READ_EXTERNAL_STORAGE` | Finding a file by name across storage | off by default |
| `PACKAGE_USAGE_STATS` | The four sort orders based on app usage | only if you pick one of them |
| `ACCESS_NOTIFICATION_POLICY` | The wheel's Do Not Disturb quick action | optional |
| `WRITE_SETTINGS` | Screen brightness from the bubble's quick settings | optional |
| `VIBRATE` | Haptic feedback on the wheel | optional |
| `REQUEST_DELETE_PACKAGES` | The uninstall option in an app's long-press menu | optional |

---

## Install

Low Distraction is **not on Google Play**, and has no interest in being. It is
built and released from source, and signed with the same key every time.

- **GitHub Releases** — the signed APK, built by CI from the tagged commit:
  <https://github.com/merry8989-uk/lowdistraction-launcher/releases/latest>
- **Free-software repositories** — inclusion requests are open for **F-Droid**
  and **IzzyOnDroid**; until they are accepted, use the releases page.

Once installed, open **Settings → Apps → Default apps → Home app** and pick
**Low Distraction** (or use *Settings → Set as default launcher* inside the
app, which opens that same system screen).

---

## Requirements

- Android SDK Platform **34** and Build-Tools **34.0.0**
- JDK 17
- Gradle 8.x (the wrapper pins **8.2**)
- Android Gradle Plugin **8.1.4**, Kotlin **1.9.22**
- `minSdk 26` (Android 8.0), `targetSdk 34`

> **Why 34 and not 35?** Termux's arm64 `aapt2` currently tops out at
> compileSdk 34. On a PC you can bump to 35 safely.
>
> **Why not Gradle 9 / AGP 8.13 / Kotlin 2.x?** Gradle 9 removed
> `DependencyHandler.module(Object)`, which AGP 8.1.x still calls, so the build
> fails outright. Adopting any of those means moving to AGP 8.13 +
> compileSdk 35 + Kotlin 2.x together — a deliberate migration, not a version
> bump. `.github/dependabot.yml` ignores those majors so they are not proposed
> as routine updates.

---

## Building in Termux (aarch64, no root, no PC)

The one real obstacle on aarch64 Termux is `aapt2`: Google only ships an
x86_64 binary, so Gradle's bundled copy cannot run. The fix is to install
Termux's own arm64 `aapt2` and point Gradle at it.

```bash
# 1. Toolchain
pkg update && pkg upgrade -y
pkg install -y openjdk-17 gradle aapt2 wget unzip

# 2. Android SDK (command-line tools)
export ANDROID_HOME="$HOME/android-sdk"
mkdir -p "$ANDROID_HOME/cmdline-tools"
cd "$ANDROID_HOME"
wget -O clt.zip https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip
unzip -q clt.zip
mv cmdline-tools latest
mv latest "$ANDROID_HOME/cmdline-tools/"

export JAVA_HOME="$PREFIX/lib/jvm/java-17-openjdk"
export PATH="$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"

yes | sdkmanager --licenses
sdkmanager "platform-tools" "platforms;android-34" "build-tools;34.0.0"

# 3. Point Gradle at the arm64 aapt2 (put it in your USER config, not the repo)
mkdir -p ~/.gradle
echo "android.aapt2FromMavenOverride=$PREFIX/bin/aapt2" >> ~/.gradle/gradle.properties

# 4. Build
cd ~/lowdistraction-launcher
chmod +x gradlew
echo "sdk.dir=$ANDROID_HOME" > local.properties
./gradlew assembleDebug
```

The APK lands at `app/build/outputs/apk/debug/app-debug.apk`. If `./gradlew`
gives you trouble, skip the wrapper and use system Gradle:
`gradle assembleDebug`.

To install it, copy the APK to `/sdcard/Download/`, open it with a file manager
and allow "install unknown apps" for that manager.

## Building in the cloud (recommended)

Push the repo to GitHub and the workflows do it for you:

- **`.github/workflows/build.yml`** — builds a debug APK on every push and
  uploads it as an artifact (Actions tab → run → Artifacts).
- **`.github/workflows/release.yml`** — builds the **signed** APK and attaches
  it to a GitHub **Release**. Trigger it by pushing a tag:

  ```bash
  git tag v1.28 && git push origin v1.28
  ```

  or run it manually from the Actions tab and type a tag name.
- **`.github/workflows/codeql.yml`** — CodeQL analysis.
- GitHub's **Automatic Dependency Submission** and **Dependabot** keep the
  dependency graph and updates current.

Release notes come from
`fastlane/metadata/android/en-US/changelogs/<versionCode>.txt`.

## Troubleshooting (Termux)

- **`AAPT2 … Daemon startup failed`** — Gradle is using its bundled x86_64
  aapt2. Make sure `~/.gradle/gradle.properties` contains the
  `android.aapt2FromMavenOverride` line pointing at `$PREFIX/bin/aapt2`.
- **`Failed to install build-tools`** — run `sdkmanager "build-tools;34.0.0"`
  again and accept licences with `yes | sdkmanager --licenses`.
- **`SDK location not found`** — you are missing `local.properties`
  (`sdk.dir=…`) or the `ANDROID_HOME` env var.
- **Out of memory during the build** — lower `org.gradle.jvmargs` in
  `gradle.properties` (e.g. `-Xmx1024m`) and stop other apps.
- **`syntax error: unexpected ')'` from aapt2** — you are mixing an x86_64
  aapt2 with an arm64 one; remove any stale copies and rebuild.

---

## The assistive bubble, in detail

A floating, draggable bubble (like iOS AssistiveTouch).

- **Bubble taps:** 1 tap = Back, 2 taps = Home, 3 taps = Lock/sleep.
- **Drag** the bubble to move it anywhere.
- **Long-press** the bubble → the spin wheel: up to 8 pinned apps, a **+** slot
  to add more, and the bottom row of **Torch, Volume, DND, Break**.

Turn it on from Settings (long-press anywhere on the home screen) — a single
switch enables or disables it.

**Honest limits.** Android exposes **no public API** for Digital Wellbeing's
Focus mode or Bedtime mode, so those buttons open the Digital Wellbeing
settings screen rather than toggling silently. And a normal app cannot block the
Home button, so **Break** is a full-screen, Back-proof break screen — not an
absolute lock (hold the hint for 3 s to end it early).

## Contact

Settings has a plain **Ask for more features** link to `@osintgram_io` on
Instagram. It is an ordinary web link — the app itself ships with no analytics
SDK, no crash-reporting service and no trackers of any kind, and it keeps its
diagnostics log only on the phone.

## Signing

CI builds a **signed release APK** using a fixed keystore, so every build shares
one signature and Android can update in place — no more "App not installed" when
installing a newer build over an older one.

The keystore lives in four repository secrets (never in the repo):
`KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`. When the
secrets are missing, the release build falls back to the debug key so a local
`./gradlew assembleRelease` still works.

> Keep a backup of the keystore and its password. If they are lost you can never
> publish an update with the same signature again — you would have to uninstall
> and reinstall.

## Project layout

```
lowdistraction-launcher/
├── settings.gradle.kts · build.gradle.kts · gradle.properties
├── gradlew / gradlew.bat / gradle/wrapper/…
├── docs/index.html                  # the prototype / showcase page (GitHub Pages)
├── fastlane/metadata/android/en-US/ # store metadata + per-version changelogs
├── .github/{workflows,dependabot.yml,ISSUE_TEMPLATE,pull_request_template.md}
└── app/src/main/
    ├── AndroidManifest.xml
    ├── java/com/lowdistraction/launcher/
    │   ├── MainActivity.kt          # home screen, gestures, the launch pause
    │   ├── AppRepository.kt · AppInfo.kt · AppListAdapter.kt
    │   ├── AppSort.kt · AppUsage.kt # 17 sort orders + the launcher's own tallies
    │   ├── Look.kt                  # 62 themes, icons, text sizing
    │   ├── ShapedIconDrawable.kt    # icon shapes / outlines
    │   ├── AppearanceActivity.kt · SortSettingsActivity.kt · SettingsActivity.kt
    │   ├── SearchSources.kt · SearchSourcesActivity.kt
    │   ├── QuickFind.kt · QuickFindAdapter.kt   # the second, opt-in search box
    │   ├── FileIndex.kt             # file search (all-files access or folders)
    │   ├── HiddenApps.kt · PinnedApps.kt
    │   ├── SensitiveApps.kt         # 96 payment/gov packages + name detection
    │   ├── DevCheck*.kt · DeviceInfo.kt         # the Dev.Check dashboard
    │   ├── WheelSound.kt · Chime.kt · TimerTick.kt
    │   ├── GuideActivity.kt · HomeRole.kt · ShortcutPrefs.kt
    │   └── bubble/
    │       ├── FloatingBubbleService.kt · AssistiveTouchService.kt
    │       ├── RadialMenuView.kt · QuickSettings.kt
    │       ├── BubblePrefs.kt · TeaModeActivity.kt
    └── res/
        ├── layout/  raw/ (17 sounds)  drawable/  values/  xml/
        └── mipmap-anydpi-v26/ic_launcher.xml
```

## Contributing

Bug reports and pull requests are welcome — please use the issue templates in
`.github/ISSUE_TEMPLATE/`. See `CONTRIBUTING.md`.

Two house rules the project will not bend on:

1. **No internet permission, ever.** The `checkNoInternet` build guard enforces
   it.
2. **No trackers, no analytics or crash-reporting service, no telemetry, no ads
   and no paid tiers.** Diagnostics are a local file with an on/off switch, and
   they leave the phone only when you send them.

## Licence

**AGPL-3.0.** See `LICENSE`. You are free to build it yourself; CI publishes the
exact APK for every tag.
