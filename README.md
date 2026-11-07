# Vishal Jarvis — Android

This repository contains the existing Vishal Jarvis Android companion (`com.myraa.mobile`).
The checked-in `app-source/` is the source of truth for the APK and the original
`.github/workflows/android-build.yml` builds it. The `Vishal-Jarvis-Android-App-Source.zip`
is a downloadable snapshot of that same source, not a second application. The 1.4 update
continues from the source of the previously published 1.3 APK, including its existing
interface, procedural 3D robot, voice, settings, memory and device actions.

## What changed in 1.4

- **Background text tasks:** a submitted question is saved before execution. A user-started
  foreground service protects an active request; a persisted, network-constrained Android
  `JobScheduler` job resumes an interrupted request and retries temporary failures with
  backoff. Completed answers are saved to the existing history and can notify the user.
  Phone actions that require a visible UI are not silently executed in the background;
  interrupted device actions are not automatically repeated.
- **Research:** requests indicating current information use Gemini Google Search grounding
  if the chosen model and project support it. Returned source links are included with the
  answer. If grounding is unsupported or no sources are returned, the app says so.
- **Diagnostics and accessibility:** the existing connection check distinguishes Android
  network/captive-portal state, key/model errors and provider failures. Existing history
  search, dark/light/system themes and settings remain; conversation can also be saved or
  shared as a UTF-8 `.txt` file, and text size can be adjusted in Advanced settings.
- **SIM prompt:** the app no longer automatically asks for SIM/phone-state access. It still
  requests Phone and SMS permissions only for the existing respective features. If prior
  phone-state access is unavailable, SIM details/specific-SIM selection may not be
  available; the phone's default call account or system chooser can still be used.

### Background and privacy limits

The Gemini key remains encrypted using the existing Android Keystore alias. Questions,
results and the task queue are private app data; when a request is processed, the question
and recent context are sent to Google, and research may use Google Search grounding. No
server is included. **A powered-off phone cannot run tasks**. Android may defer jobs due to
Doze, battery restrictions, network availability, force-stop, or until the first unlock
after reboot. Permit notifications in Android settings to see completion notifications.
Do not force-stop the app if tasks should continue. Android/UI-dependent operations and
Live voice have their own foreground/permission requirements.

## Build and download

Use **Actions → Vishal Jarvis Android Build → Run workflow**. A normal run tests and
uploads the APK as the `Vishal-Jarvis-APK` artifact. Select `publish` to publish the
successfully tested APK to GitHub Releases. The workflow runs TypeScript/parser tests,
Gradle Java unit tests, signature/package checks and an Android emulator instrumentation
suite before publishing. It installs Node 22, JDK 21, Gradle 8.11.1 and Android SDK 35.

The package ID remains `com.myraa.mobile`. If all four GitHub Actions secrets below are
configured, the release APK uses that existing signing key; otherwise the build produces a
clearly labelled **debug APK**. An incomplete signing configuration fails rather than
silently producing a misleading release. Never commit the keystore or passwords.

- `ANDROID_SIGNING_KEYSTORE_BASE64` — base64 of the existing release keystore
- `ANDROID_SIGNING_STORE_PASSWORD`
- `ANDROID_SIGNING_KEY_ALIAS`
- `ANDROID_SIGNING_KEY_PASSWORD`

Android only allows an in-place update when the signing certificate matches the installed
APK. The workflow compares the new certificate with the previous 1.3 APK when available.
If signatures differ, export any needed conversations **before** uninstalling: uninstalling
also removes local data. The debug key is cached for repeat CI builds on the same branch,
but it is not a secure production signing key.

For a local Windows build, see [`app-source/android/README.md`](app-source/android/README.md).
`python tools/package-source.py` refreshes the checked-in source ZIP without build outputs
or signing material. License and third-party notices are in `app-source/`.
