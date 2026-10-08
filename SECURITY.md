# Security

## Reporting a problem

Found something? Message **@osintgram_io** on Instagram. Please don't open a
public issue for anything exploitable.

## Why the attack surface is small

- **No `INTERNET` permission.** The app does not declare it, so it is
  technically incapable of making any network request. No analytics, no crash
  reporting, no telemetry, no phoning home — it cannot, even in principle.
- **No third-party SDKs.** The only dependencies are AndroidX
  (`core-ktx`, `recyclerview`).
- **Permissions are minimal and explained.** No `INTERNET`, no location, no
  camera, no storage. The app also declares `REQUEST_DELETE_PACKAGES` so the
  Uninstall action can open the system dialog, and `READ_CONTACTS` /
  `READ_CALENDAR` — those two are used only by the optional second search bar,
  are asked for at runtime, and nothing is stored or sent anywhere (the app has
  no network access at all).
- **Only one component is exported.** `MainActivity` must be exported to work
  as a HOME app. Every service (`FloatingBubbleService`,
  `AssistiveTouchService`) and every other activity (`SettingsActivity`,
  `TeaModeActivity`) is `exported="false"`.
- **The accessibility service reads nothing.** It declares
  `canRetrieveWindowContent="false"` and only fires Back / Home / Recents /
  Lock global actions.
- **The keystore never lives in the repo.** Release signing keys are stored in
  GitHub Actions secrets only. Anyone can verify an APK was built from this
  source by checking the signer certificate.

## Keeping it current

- **Dependabot** opens a monthly pull request for outdated or vulnerable
  Gradle dependencies and GitHub Actions.
- A **monthly maintenance run** reviews those updates, applies safe ones,
  rebuilds, and confirms the CI build is green.

## What you should do as the owner

- Keep `lowdistraction-keystore.zip` (the keystore + password) safe and
  private. If it leaks, someone else can sign an update as you.
- Never commit the keystore or the passwords.
- Merge Dependabot PRs after the CI build passes.
