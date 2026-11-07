# P0 Fix — AI Voice Response Cut Off During Playback (v1.5.1)

Landed on top of Voice Intelligence 2.0 (PR #2). Interface, AI behaviour and
architecture are unchanged; only the audio **output** pipeline was repaired.
Microphone/speech-recognition input was not modified except where it was
provably feeding the output bug (item 1).

## Root causes found (in pipeline order)

1. **Speaker echo was streamed back to Gemini (primary).**
   `MyraaAudioSession.ingestNativePcm` ran the local VAD and then forwarded
   *every* native microphone frame unconditionally. During playback the mic
   hears the loudspeaker, Gemini's server VAD (`START_OF_ACTIVITY_INTERRUPTS`)
   treated that echo as a user barge-in, stopped generating, and sent
   `interrupted` — so speech died after roughly the first sentence
   ("Hello! I can help you with physics…"). The browser capture path already
   respected the VAD result; the native path did not. Now only frames the VAD
   accepts (genuine barge-in while speaking) are forwarded.
2. **"One chunk ended" was treated as "the response ended".**
   Each `AudioBufferSourceNode.onended` flipped state back to `listening`
   whenever the scheduled list momentarily emptied (any network jitter gap),
   and `turnComplete` used a fixed 100 ms timer. State flicker also weakened
   the echo/barge-in guard mid-response.
3. **No turn-aware queue.** There was no distinction between chunk end,
   response end, and server `turnComplete`; no preserved pre-context queue,
   no generation guard against stale sources.
4. **Duplicate playback engines on reconnect.** Live transport recovery
   re-fires `onopen`; the session rebuilt both AudioContexts every time,
   orphaning audio scheduled on the abandoned context. The existing graph is
   now reused and resumed.
5. **Suspended AudioContext never recovered** (background / audio-focus
   loss): chunks kept scheduling onto a frozen timeline with no `resume()`.
   The context now self-resumes via `onstatechange` and on chunk arrival.
6. **Session close could cut queued speech.** `session_closed` tore the
   session (and output context) down immediately; it now defers until the
   current turn has fully drained.
7. **Chunks arriving before the output graph existed were silently dropped**
   (`if (!outputAudioCtx) return`). They are now buffered in order and
   flushed when the graph is ready. Odd-length PCM frames can no longer throw
   in the Int16 view and kill a chunk.
8. **TTS vs backgrounding.** `MainActivity.onPause` and the JS
   `visibilitychange`/lifecycle handlers called `tts.stop()` /
   `stopSpeech()` on *any* minimise, cutting read-aloud mid-sentence. TTS is
   no longer stopped for backgrounding (explicit stop / destroy still stop
   it). Live (Gemini) audio and TTS engines remain separate; starting one
   still explicitly stops the other from the UI as before.

## The fix

`GaplessPcmPlayer` (exported, unit-tested) in `app-source/src/lib/audio.ts`:
- Schedules every valid chunk of a turn, in arrival order, on one monotonic
  gapless timeline — chunks are never overwritten; a failing chunk is logged
  and skipped without discarding the rest of the response.
- A turn finishes **only** when `turnComplete` was received **and** every
  scheduled source of that turn has ended. `onended` of a single chunk never
  ends the turn.
- Generation counter: sources from an interrupted turn cannot mutate the
  next turn. Interruption (real barge-in, explicit stop, disconnect) clears
  the queue and rebuilds the pitch shifter.
- Suspended/interrupted contexts are asked to `resume()`; scheduling stays
  sequential on the frozen timeline, so background speech is preserved.
- Bounded safety queue (3 min) — on overflow only the oldest *unscheduled*
  audio is dropped, with a warning, never the currently playing turn tail.

## Verification

Automated (this environment): `cd app-source && npm run check`
- `tests/playback.test.ts` (new regression suite, fake Web Audio graph,
  deterministic, no arbitrary delays): 10-chunk gapless ordering,
  chunk-end ≠ turn-end, turnComplete-before-drain, consecutive turns,
  interruption + stale-source isolation, suspended-context recovery, plus
  source guards for items 1, 4–8 above.
- Existing suites still pass: 72 parser cases, background/SIM contracts,
  `tsc --noEmit`, Vite mobile bundle.

Acceptance-test mapping: TEST 1–8 and 11 (short/long/30–60 s/Bengali/
English/mixed/consecutive/rapid) are covered at the engine level by the
multi-chunk, consecutive-turn and queue tests above — long responses are
exactly "many chunks, one turn". TEST 9 (minimise), 10 (screen lock),
12 (network fluctuation) and 13 (engine switching) require a physical
device with a Gemini Live key and were **not** run here; the code paths
they exercise (context self-resume, drain-before-close, ordered
`receiveChain` re-delivery, background TTS) are covered by the static
source guards in the same test file. Do not mark those four as
device-verified until they are run on hardware.
