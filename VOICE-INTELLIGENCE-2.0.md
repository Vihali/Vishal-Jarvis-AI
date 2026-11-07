# Vishal Jarvis Voice Intelligence 2.0 (1.5.1)

This update is implemented on the **existing 1.5.1 Android app** from Vishal Jarvis Android Build #39 (`app-source/`, commit `44277c8`), not the older 1.1.0 zip.

Version stays **1.5.1** / `versionCode` 11. UI, colors, layout, 3D character, navigation and unrelated features are unchanged.

## What changed

### A. Live voice capture and recovery

- Native `AudioRecord` at 16 kHz (`VOICE_COMMUNICATION` → recognition → mic) with `NoiseSuppressor`, `AcousticEchoCanceler` and `AutomaticGainControl` only after `isAvailable()` / `getEnabled()` checks.
- `SpeechEnhancer` adaptive Wiener NS, high-pass, AGC and VAD. If hardware NS is on, software NS runs in **light** mode so the two do not both hollow the voice.
- WebView `getUserMedia` is the fallback only. Browser enhancement is not applied on the native path.
- Client VAD / barge-in in `audio.ts` still gates echo while Jarvis is speaking.
- Gemini Live `realtimeInputConfig`: high start-of-speech sensitivity, low end-of-speech sensitivity, `silenceDurationMs: 700` (not 100–200 ms, which splits utterances), interruption on user activity.
- Auto-recovery: stalled native PCM (>2.5 s) or Live `goAway` / unexpected socket close requests a **new ephemeral token** via `renewLive` without tearing down the Talk session. Max 3 attempts, then an honest error.
- Capture diagnostics are in Settings and `window.__mobileDiagnostics()`.

### B. Contacts

- `NameMatcher` + `BengaliPhonetics`: extract the spoken name, Latin/Bengali variants, phonetic and consonant keys, nicknames, user aliases, ranked confidence.
- Thresholds: auto ≥ 0.86 with 0.10 gap; confirm ≥ 0.72; several close names → chooser. **Never auto-dials an ambiguous match.**
- Example saved name `লাবণ্য` is intended to match `লাবণ্যকে কল করো`, `Labanya ke call karo`, `Labonno call`, `লাবন্যকে ফোন করো`.
- Aliases are stored in app `SharedPreferences` only. Saved Android contact names are never rewritten.

### C. Calling safety (1.5.1 behaviour kept)

- Confident unique match still uses **`Intent.ACTION_CALL`** with the existing SIM chooser.
- Medium confidence: speak/show the name and ask before calling.
- Several close names: picker. Cancel reports that nobody was called.
- SMS / WhatsApp recipient resolution uses the same matcher.

## Tests that actually ran here

| Check | Result |
| --- | --- |
| `npm run check` in `app-source/` (tsc + actions tests + Vite mobile assets) | **passed** (72 parser cases, background contracts, Vite mobile build) |
| GitHub Actions `Vishal Jarvis Android Build` (`app-source/**`) including `:app:testDebugUnitTest` | after push; APK link added when green |
| Device: traffic/horns/crowds/wind, Gemini Live freeze, real address book | **not run** — no phone in this environment |
| Placing a real `ACTION_CALL` | **not run** |

JUnit `NameMatcherTest` covers Labanya variants, ambiguous names, unmatched names, aliases, and enhancer frame length. It does not prove a physical microphone or SIM call.

## How to verify on a phone

1. Install the 1.5.1 Actions APK (debug unless the workflow published a signed build).
2. Grant Contacts, Microphone, Phone, Notifications.
3. Save `লাবণ্য` and say the four example phrases. Confirm it does **not** call a different person.
4. Save two similar names and confirm a chooser, never a silent wrong call.
5. Talk outside / near traffic; barge-in while Jarvis is speaking; background the app during Live (FGS should keep the call).
6. Toggle flashlight, open YouTube, play media, WhatsApp draft — unrelated 1.5.1 actions must still work.
