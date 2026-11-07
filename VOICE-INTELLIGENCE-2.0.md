# Vishal Jarvis Voice Intelligence 2.0

This upgrade keeps the existing Android UI, 3D character, Gemini chat, and
dialer-only calling flow. It changes the microphone path and contact matching.

## A. Voice recognition and noise control

Native capture (`AudioCaptureEngine`) now owns the microphone during Live voice:

- `AudioRecord` with `VOICE_COMMUNICATION`, falling back to `VOICE_RECOGNITION` then `MIC`
- Device `NoiseSuppressor`, `AcousticEchoCanceler` and `AutomaticGainControl` enabled only after `isAvailable()` and a successful `setEnabled(true)`
- Adaptive Wiener speech enhancement, high-pass wind/rumble cut, formant-preserving gains, software AGC
- Hardware NS present → light software NS (avoids hollow double processing)
- Hardware NS missing → stronger adaptive software NS
- Calibration of the noise floor for ~400 ms at session start
- Improved VAD hangover (~360 ms local, 900 ms Gemini silence) so mid-sentence pauses are not cut
- Continuous cleaned PCM to Gemini (no silence gating that was dropping words)
- Microphone is no longer tapped to the speaker (that leak caused echo and cut-offs)
- Automatic Gemini Live reconnection (up to 3 attempts) without tearing down capture
- Diagnostics: source, sample rate, hardware effect support, noise floor, clipping, latency

Fallback: if `AudioRecord` cannot start, WebView `getUserMedia` still runs with
browser NS/AEC/AGC plus a lighter software enhancer.

This sandbox has no NDK, so RNNoise/C libraries are not bundled. Suppression is
on-device DSP plus Android audio effects. Word recognition still depends on
Gemini Live once the cleaned 16 kHz stream is sent.

## B. Contact name recognition

`NameMatcher` + `BengaliPhonetics` + `ContactMatcher`:

- Extracts the spoken name from Bengali, Banglish and English call commands
- Transliteration and spelling variants (a/o, b/v, nya/nno, …)
- Phonetic and consonant keys, Jaro–Winkler, Levenshtein, prefix/token scores
- Searches the permitted Android Contacts database (display name, phonetic name, nicknames)
- User-approved aliases stored in app preferences; the address book is never rewritten
- Ranking: exact / confident / confirm / choose / none
- Ambiguous matches never auto-dial

Example: saved name `লাবণ্য` is intended to match `লাবণ্যকে কল করো`,
`Labanya ke call karo`, `Labonno call`, and `লাবন্যকে ফোন করো`.

## C. Calling safety

Unchanged safety contract:

- `ACTION_DIAL` only. The user must tap Call.
- High-confidence unique match: speak/show the saved name, then open the dialer
- Medium confidence: confirmation dialog
- Several similar people: chooser
- No match: no dialer
- Contacts permission is still required

## D. Tests actually run here

| Test | Result |
| --- | --- |
| `scripts/test-contact-commands.ts` (10 cases) | **10 passed**, 0 failed |
| Phonetic key: Labanya / Labonya / Labonno / Lavanya / Labanyo | Shared key `laban` |
| Vite mobile production bundle | Succeeded |
| `HostVoiceTests.java` (matcher + SNR/VAD/AGC/FFT) | Source included; **not executed here** — this environment has no JDK and GitHub release assets are blocked |
| GitHub Actions APK compile (`javac`, D8, AAPT2, apksigner) | **Succeeded** in 1m14s — job `Build Android APK` and `Verify APK` both passed |
| Verified APK artifact | [Vishal-Jarvis-APK](https://github.com/Vihali/Vishal-Jarvis-AI/actions/runs/42374746171/artifacts/13575007570) from run [42374746171](https://github.com/Vihali/Vishal-Jarvis-AI/actions/runs/42374746171). Artifact SHA-256 `7ba3034a49bb51043fac5ba3ca5a5c91bd0b35786367ebb0b6a45875520e2912`. GitHub login is required to download Actions artifacts. |
| Quiet / crowd / traffic / wind / multi-speaker on a physical phone | **Not run** — no Android device or emulator in this environment |
| Live Gemini recognition accuracy / latency on device | **Not run** — no API key and no device |
| Hardware NS/AEC/AGC on a specific phone | Device-dependent; the app records support in Settings diagnostics |

Command extraction results:

- `লাবণ্যকে কল করো` → `call_contact` recipient `লাবণ্য`
- `Labanya ke call karo` → `Labanya`
- `Labonno call` → `Labonno`
- `লাবন্যকে ফোন করো` → `লাবন্য`
- `call Ma` → `Ma`
- `ফোন করো রাজেশ` → `রাজেশ`
- Bare `call` is rejected; explicit `+91…` still uses `call_number`

## E. Device limitations (honest)

- Android effect APIs are optional. Some phones expose none of NS/AEC/AGC.
- Software NS reduces stationary traffic/fan/wind rumble; sudden horns and other talkers cannot be fully removed without a beamforming mic array.
- Gemini may still mis-hear names. Matching is applied to whatever text/tool argument arrives.
- Background Live still needs the existing foreground microphone service and, for app switching, floating controls.
- Recognition delay is bounded by Gemini turn-taking (`silenceDurationMs` 900). Shorter values cut words; longer values feel slower.

After installing the APK, confirm Talk, speak a contact in Bengali and English,
and open Settings to read the `audio` diagnostics object.
