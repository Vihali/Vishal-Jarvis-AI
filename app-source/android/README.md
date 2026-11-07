# Vishal Jarvis Android 1.4

The existing Android application keeps package ID `com.myraa.mobile`, Android 8 / API 26
minimum, API 35 target, the bundled WebView/Three.js robot and the existing settings,
live voice, memory and phone actions. Android System WebView 100+ and OpenGL ES 3 are
required. Gemini requires the user's own API key and access to the configured model.

## Background architecture

`MainActivity` persists each submitted AI text task in `AiTaskStore` before starting it.
An eligible `dataSync` foreground service holds a notification while the visible-user
request is active. A persistent `JobScheduler` job (the platform equivalent of a durable
WorkManager job, without an added AndroidX dependency) requires network, survives reboot,
and retries transient IO, quota and 5xx errors with exponential backoff. Permanent
configuration errors are reported instead of endlessly retried. The existing Android
Keystore key is read natively; no key is included in job extras, WebView, notifications,
source code or logs. User and model turns are recorded once per task ID. A job is cancelled
when the user explicitly stops it or clears history.

An Activity that is destroyed hands the request to its saved job. Device actions that need
foreground UI/confirmation are not performed from an unattended job. A durable
`tool_started` marker prevents a partially completed call/message/other phone action from
being replayed automatically after interruption; the user is asked to check it. Simple
foreground actions and Gemini Live keep their existing paths. AI tasks cannot execute
while the phone is powered off because there is no server-side worker. Android can defer
jobs in Doze, under battery restrictions or following force-stop; persisted jobs may wait
until the first device unlock after reboot. A user must allow notifications on Android 13+
for visible completion alerts.

Time-sensitive/research prompts are sent with Gemini Google Search grounding. Sources are
stored with the answer when returned. Grounding availability depends on the chosen model,
project, region, quota and network; an unsupported model reports an actionable error and a
response without grounding metadata is explicitly labelled as lacking sources.

Conversation search and dark/light/system themes are retained. Advanced → Text size changes
WebView text zoom without moving or replacing the character. Conversation & device can
copy, save via Android's document picker, or share a UTF-8 text file through a narrow,
read-only temporary content grant; there is no broad filesystem permission.

The SIM/phone-state permission is never requested automatically. Existing Phone and SMS
permissions and actions remain. If Android has not already granted phone-state access,
the app cannot enumerate/select SIM accounts and reports that honestly, without showing a
SIM access prompt. Android's default account/system chooser can still handle calls.

## CI and signing

`.github/workflows/android-build.yml` installs JDK 21, Gradle 8.11.1, SDK platform 35 and
build-tools 35.0.0. It runs `npm run check`, Gradle unit tests, builds and verifies the real
APK, and runs instrumentation against an Android emulator. A manual `workflow_dispatch`
can optionally publish the verified artifact to Releases. All four `ANDROID_SIGNING_*`
GitHub Secrets described in the repository README must be present for release signing;
otherwise a labelled debug APK is built. A changed signing certificate prevents an in-place
Android update and uninstalling the old app removes its local data.

## Local Windows build

1. Install Node 22, JDK 21, Android SDK platform 35/build-tools 35.0.0 and Gradle 8.11.1.
2. From `app-source/`, run `npm ci`, `npm run check`, `node --import tsx scripts/export-mobile-personality.ts`, and `pwsh -File scripts/ensure-build-icons.ps1`.
3. Copy `build/icon.png` to `android/app/src/main/res/drawable/vishal_jarvis_icon.png`.
4. Create `android/local-debug.keystore` with alias `androiddebugkey` and password `android`
   (the CI script demonstrates this), then run `gradle -p android :app:testDebugUnitTest :app:assembleDebug`.

The old Windows `scripts/build-android.ps1` is retained as a standalone debug build path;
it outputs `artifacts/android/Vishal-Jarvis-1.4.0-debug.apk`. The primary CI path uses Gradle.

The emulator suite uses fixture HTTPS responses and no real Google key; it covers screen-off
completion, Activity destruction and scheduler handoff, retry, grounding, text export and
side-effect replay protection. Emulator tests are not a substitute for testing on the user's
actual device, battery configuration, network and Gemini account.
