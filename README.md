# Low Distraction Launcher

A minimal, text-only Android home screen written in Kotlin. No icons, no
widgets, no folders — just the clock, a search box and an alphabetical list
of app names. Inspired by launchers like Olauncher.

## Features

- Text-only alphabetical list of installed launchable apps (RecyclerView).
- Live search / filter as you type.
- Auto-open on unique match: when a query narrows the list to exactly one
  app, it launches by itself after a short pause (keep typing to cancel).
  Pressing Enter / Search on the keyboard opens the top match.
- Clock + date header (auto-updating `TextClock` widgets).
- Long-press an app for a small menu: **App info**, **Uninstall**, **Hide app**.
- Hidden apps are filtered out of the list; long-press the **clock** →
  *Hidden apps* to see and unhide them.
- **Assistive bubble:** a draggable floating button. Single/double tap opens a
  radial menu — up to 8 pinned apps around three nested circles
  (inner = Back, middle = Home, outer = Lock). Long-press the bubble for a
  device-shortcuts panel: Torch, Sound, Brightness, Focus mode, DND,
  Bedtime mode, and Tea mode (a 5-minute break screen).
- Gestures: swipe **right** in the list opens the dialer, swipe **left**
  opens the camera.
- **Settings:** long-press anywhere on the home screen (empty space, clock or
  background) to open Settings — the assistive-bubble on/off switch, ring apps,
  hidden apps, and a contact link all live there.
- Registers as a HOME app. On first launch it shows a **“Set as default
  launcher”** prompt (Android's own role dialog on Android 10+, Home settings
  on older versions); you can also trigger it any time from Settings →
  *Set as default launcher*.
- Dark, monospace, distraction-free styling.

## Project layout

```
lowdistraction-launcher/
├── settings.gradle.kts
├── build.gradle.kts
├── gradle.properties
├── gradlew / gradlew.bat / gradle/wrapper/…
├── .github/workflows/{build.yml,release.yml}
└── app/
    ├── build.gradle.kts
    └── src/main/
        ├── AndroidManifest.xml
        ├── java/com/lowdistraction/launcher/
        │   ├── AppInfo.kt          # data class for one app
        │   ├── AppRepository.kt    # loads + sorts launchable apps
        │   ├── AppListAdapter.kt   # text-only RecyclerView adapter
        │   ├── HiddenApps.kt       # SharedPreferences-backed hide list
        │   └── MainActivity.kt     # home screen logic + gestures
        └── res/
            ├── layout/activity_main.xml
            ├── layout/item_app.xml
            ├── values/{strings,colors,themes}.xml
            ├── drawable/{search_bg,ic_launcher_foreground}.xml
            └── mipmap-anydpi-v26/ic_launcher.xml
```

## Requirements

- Android SDK Platform **34** and Build-Tools **34.0.0**
- JDK 17
- Gradle 8.x (the wrapper pins 8.2)
- Android Gradle Plugin 8.1.4, Kotlin 1.9.22
- `minSdk 26` (Android 8.0), `targetSdk 34`

> Why 34 and not 35? Termux's arm64 `aapt2` currently tops out at compileSdk 34.
> If you build on a PC you can bump to 35 safely.

## Building in Termux (aarch64, no root, no PC)

The one real obstacle on aarch64 Termux is `aapt2`: Google only ships an
x86_64 binary, so Gradle's bundled copy can't run. The fix is to install
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

The APK lands at:

```
app/build/outputs/apk/debug/app-debug.apk
```

If `./gradlew` gives you trouble, skip the wrapper and use system Gradle:
`gradle assembleDebug`.

## Installing it on the phone

```bash
cp app/build/outputs/apk/debug/app-debug.apk /sdcard/Download/
```

Then open the file with a file manager and install it (allow "install
unknown apps" for that file manager). Finally go to
**Settings → Apps → Default apps → Home app** and pick **Low Distraction**.

## Building in the cloud (recommended)

Push the repo to GitHub and you get two workflows for free:

- **`.github/workflows/build.yml`** — builds a debug APK on every push and
  uploads it as a downloadable artifact (Actions tab → run → Artifacts).
- **`.github/workflows/release.yml`** — builds the APK and attaches it to a
  GitHub **Release**. Trigger it by pushing a tag:

  ```bash
  git tag v1.1 && git push origin v1.1
  ```

  or run it manually from the Actions tab and type a tag name. The APK shows
  up on the repo's Releases page, ready to download and install.

No local toolchain needed for either.

## Troubleshooting (Termux)

- **`AAPT2 … Daemon startup failed`** — Gradle is using its bundled x86_64
  aapt2. Make sure `~/.gradle/gradle.properties` contains the
  `android.aapt2FromMavenOverride` line pointing at `$PREFIX/bin/aapt2`.
- **`Failed to install build-tools`** — run `sdkmanager "build-tools;34.0.0"`
  again and accept licenses with `yes | sdkmanager --licenses`.
- **`SDK location not found`** — you are missing `local.properties`
  (`sdk.dir=…`) or the `ANDROID_HOME` env var.
- **Out of memory during the build** — lower `org.gradle.jvmargs` in
  `gradle.properties` (e.g. `-Xmx1024m`) and stop other apps.
- **`syntax error: unexpected ')'` from aapt2** — you are mixing an x86_64
  aapt2 with an arm64 one; remove any stale copies and rebuild.

## Assistive bubble

A floating, draggable bubble (like iOS AssistiveTouch) with three overlays:

- **Single / double tap** → radial menu. The centre is three concentric
  circles: **inner** = one step back, **middle** = go home, **outer** =
  lock/sleep the screen. Around it sit up to 8 pinned apps plus a **+** slot to
  add more (long-press a slot to remove it).
- **Long press** → device-shortcuts panel: Torch, Sound, Brightness, Focus
  mode, DND, Bedtime mode, Tea mode.

Turn it on from **Settings** (long-press anywhere on the home screen): there is
a single switch to enable or disable the bubble.

Permissions it needs (grant them once):

| Feature | What you must grant |
| --- | --- |
| Floating bubble | *Display over other apps* (Settings → Apps → Special access) |
| Back / Home / Lock | enable the **Assistive bubble** accessibility service |
| Brightness | *Modify system settings* |
| Do Not Disturb | *Do Not Disturb access* |

Honest limits: Android exposes **no public API** for Digital Wellbeing's
*Focus mode* or *Bedtime mode*, so those buttons open the Digital Wellbeing
settings screen rather than toggling silently. And a normal app cannot block
the Home button, so **Tea mode** is a full-screen, Back-proof break screen —
not an absolute lock (hold the hint 3 s to end it early).

## Contact

The Settings screen has a plain **Ask for more features** link to
`@osintgram_io` on Instagram. It is a normal web link — the app ships with no
analytics, no crash reporting and no trackers of any kind.

## Extending it

- **Double-tap to lock screen:** use `DevicePolicyManager` (needs the
  device-admin permission).
- **More gestures:** add cases to `onFling` in `MainActivity.setupGestures`
  (e.g. swipe up = open search and focus it).
- **Favourites row:** pin a few package names above the alphabetical list.
