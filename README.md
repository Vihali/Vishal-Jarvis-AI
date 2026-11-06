# Vishal Jarvis — Android

An Android AI companion and on-device agent, built by Vishal. Native Java handles the
Gemini connection, encrypted credentials, the agent loop, Android TTS and device
actions; a bundled WebView UI provides chat, streaming replies, long-term memory,
live voice and a procedural Three.js robot character.

## Repository layout

| Path | What it is |
| --- | --- |
| `app-source/` | The Android app source. **This is the source of truth** and what CI compiles. |
| `app-source/android/app/src/main/java/com/myraa/mobile/` | Native Java: lifecycle, WebView bridge, agent loop, action engine, tool schema |
| `app-source/src/mobile/` | Bundled UI: chat, streaming, memory, permissions, 3D character |
| `app-source/shared/` | Personality contract shared by text chat and live voice |
| `app-source/android/tests/` | Instrumentation tests for the native bridge and action engine |
| `Vishal-Jarvis-Android-App-Source.zip` | Downloadable snapshot of `app-source/` |
| `.github/workflows/android-build.yml` | Typechecks the UI, builds the APK and publishes both artifacts |

## What the agent can do

**Chat and voice.** Text replies stream token by token. One line of text can chain up
to four Android actions, each reported to the UI with its real result. Gemini Live
voice uses the same tool schema and can keep running (with a notification and optional
floating stop chip) while you use other apps.

**Device actions (23).** Open installed apps and system panels, open https links,
search YouTube or the web, prepare WhatsApp and SMS drafts, dial a number or a saved
contact by name, toggle the flashlight, set alarms and timers, open calendar and Maps,
share or copy text, send media keys, change media volume, read battery/network/storage
status, list installed apps and open the camera.

**Safety by construction.** Calls only open the dialer; messages stay drafts you send;
Wi-Fi, Bluetooth and similar switches open their own system panel. There is no
accessibility service, screen capture, notification reading or file access, and the API
key never enters the WebView.

**Memory.** Ask it to remember a preference and it stores one short fact locally (up to
30, never passwords, OTPs or keys). Review, add or delete facts in Settings → Memory.

## Build

```bash
cd app-source
npm install
npm run check    # TypeScript typecheck + Vite asset build
```

Then, on Windows with JDK 21 and Android SDK 35:

```powershell
pwsh -NoProfile -File scripts/build-android.ps1 -SdkRoot 'C:\android-sdk' -JavaRoot 'C:\jdk-21'
```

The APK lands in `app-source/artifacts/android/Vishal-Jarvis-1.2.0-private.apk`. The
GitHub workflow runs the same steps and uploads the APK plus a fresh source ZIP.

## Honest status

The UI is typechecked and bundle-built in this repository. The APK build and on-device
behaviour must be verified on a real Android phone — see `app-source/android/README.md`
for the exact checklist and limits.

## License and third-party notices

See `app-source/LICENSE` and `app-source/THIRD-PARTY-NOTICES.md`.
