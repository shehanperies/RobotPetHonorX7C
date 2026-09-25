# Welly V8 Stability Rebuild

V8 is a behavior-stability rebuild, not another detector-feature patch.

## What changed
- One serialized foreground behavior queue; stale low-priority events are dropped.
- TTS is no longer a PRIMARY behavior and cannot replace kiss/wave/touch in the middle.
- Low-priority `interruptMotion` can no longer preempt a higher-priority behavior.
- Kiss detection learns a neutral mouth baseline and requires a deliberate change, a hold, then release before rearming.
- Close-face is now an approach event; starting/remaining close does not repeatedly fire.
- Static/dynamic hand gestures latch after one event and must return to neutral/no-hand before rearming.
- Object events require spatial stability and 2-minute same-object habituation; track IDs alone are not identity.
- Normal idle mode does not physically search whenever a face disappears. Physical search is allowed only in an explicit follow session.
- Phone sensor reactions are masked while Welly is moving itself.
- TTS lifecycle uses `UtteranceProgressListener` instead of guessed text-length timers.
- Wake listener recovers after microphone permission is granted and is muted through TTS plus a tail delay.
- ESP link treats stale telemetry as unsafe and sends STOP on command ACK timeout.
- V7-corrupted personality values are reset once on the first V8 launch (`mindVersion=8`).
- Idle speech/fork actions are additionally rate-limited so Welly can stay quietly alive.

## Wake word note
The foreground "Welly" wake listener still uses Android SpeechRecognizer. It is improved, but it is not yet a custom DSP/TFLite keyword spotting model. Core pet behavior does not require Gemini or an API key.
