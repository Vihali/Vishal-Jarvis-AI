# Vishal Jarvis Android (v1.2.0)

A standalone Android assistant. Native Java owns credentials, HTTPS, the agent loop,
Android TTS, lifecycle and the device action engine. The bundled HTML/TypeScript UI
contains chat, live voice and a self-contained procedural Three.js robot character.

## Architecture

```
WebView UI (src/mobile/main.ts)
  ├─ streaming deltas, tool activity, memory & permission UI
  └─ window.MyraaNative  ← JavascriptInterface
MainActivity
  ├─ Android Keystore encrypted Gemini key
  ├─ agent loop: streamGenerateContent (SSE) → functionCall → MobileActions → loop
  ├─ long-term memory (max 30 short, user-approved facts)
  └─ Gemini Live token minting, TTS, lifecycle, permissions
MobileTools       tool schema + system prompt (mirrored in src/mobile/actions.ts)
MobileActions     bounded intent engine for 20+ device actions
GeminiAccess      metadata-only key checks and safe provider errors
LiveVoiceService  foreground notification + floating stop chip
```

Text chat and live voice share one capability set. Every action is either a system UI
the user confirms (dialer, SMS/WhatsApp composer, alarm/timer/calendar editor, Maps,
settings panel, share sheet) or a narrow reversible change (torch, media key, media
volume, clipboard). There is no accessibility service, no arbitrary URL intent, no
shell, no file or screen access.

## Requirements

Android 8.0 / API 26 or later, Android System WebView 100+, and OpenGL ES 3.
Target API 35. Chat and Gemini Live voice require the user's own API key, network
access and access to the selected Google model. The key is encrypted with Android
Keystore and is not bundled.

## Build on Windows

Install Node.js, JDK 21, Android SDK platform 35 and build-tools 35.0.0. From the
project root:

```powershell
npm install
npm run check   # tsc --noEmit, parser tests, vite build
pwsh -NoProfile -File scripts/build-android.ps1 -SdkRoot 'C:\path\to\android-sdk' -JavaRoot 'C:\path\to\jdk-21'
```

The script runs Vite, javac, D8, AAPT2, zipalign and apksigner. Output:

`artifacts/android/Vishal-Jarvis-1.2.0-private.apk`

Install with `adb install -r <apk-path>` or copy the APK to the phone and install it.
The development package ID remains `com.myraa.mobile` for compatibility with the
previous app identity; the launcher name is Vishal Jarvis.

## Agent behaviour

1. The typed message is added to trimmed history (last 40 turns).
2. The model streams a reply through `streamGenerateContent?alt=sse`; text deltas are
   pushed to the UI immediately.
3. If the stream contains `functionCall` parts, each call runs through
   `MobileActions`, and a truthful `functionResponse` is returned to the model.
4. The loop continues for at most four rounds, then the final text is stored with the
   conversation. Stopping cancels the request, any pending chooser and the connection.
5. `remember_fact` calls are validated natively (length, secrets refusal, dedupe,
   cap of 30) and injected into the next system prompt.

Deterministic one-line commands (torch, Wi-Fi, call, alarm, timer, volume, open URL)
also run locally in TypeScript so simple requests skip the network entirely.

## Character

`src/mobile/RobotCharacter.ts` procedurally builds the robotic male companion using
Three.js primitives and metallic/emissive materials. It has animated idle motion,
head tracking, voice-reactive eye/core/mouth effects, and wave/dance/sit/stand poses,
and it is bundled directly into the mobile web assets.

## Validation

`npm run check` verifies TypeScript types, the deterministic command parser and tool-schema tests, and the production asset build. The Android
APK build and on-device behaviour (startup, 3D rendering, permissions, streaming chat,
tool calls, Live voice, background lifecycle, battery/thermal) must still be verified
on a real device — a source update is not a successful build.

The instrumentation suite in `android/tests` exercises the production bridge with
fixture apps and contacts: it asserts that actions never send messages, never place
calls and never bypass Android's settings restrictions.
