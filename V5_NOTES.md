# RobotPet Honor X7C — V5 Full Intelligence Upgrade

This ZIP is an overlay for the existing `shehanperies/RobotPetHonorX7C` repository.
It includes all V4 calm-behavior changes plus the V5 features below, so V4 does not need to be installed separately.

## Local Living Brain (works with no cloud/API)
- Slower, persistent internal drives: mood, annoyance, social need, boredom, curiosity, energy.
- Idle is alive but not motor-hyperactive: eye wandering, short playful expressions, fork self-play, attention invitations, contextual questions.
- No permanent face chasing. Following is a time-limited session started intentionally (voice / come-here / approved AI action).
- Follow corrections require stable error, use short pulses and cooldowns.
- Lost-person behavior: wait a randomized 4–7 seconds, then do a small randomized physical left/right body scan, check each side, return near center, and stop instantly if a face reappears.
- Search is bounded; it cannot become an endless spinning loop.
- Context behavior: morning greeting, meal-time questions, late-night reaction, battery/charging, object curiosity and habituation.
- Expressive fork macros: greeting, happy bounce, curiosity, self-play, sad, startled, rest.
- Safety remains local and higher priority than personality.

## Vision / gestures
- MediaPipe hand gesture recognizer plus trajectory heuristics.
- Open palm STOP, thumbs up/down, wave, point L/R, point up/down for fork, peace, I-love-you, shh, fist / hit-swing.
- Come-here supports open/fist/open, vertical beckon movement and finger curl/release transitions.
- Turn-around uses an index-finger circle trajectory with relaxed thresholds.
- Static gestures require stability before firing.
- Wink detection is suppressed when a hand is over/near the face and requires a stable one-eye pattern.
- EfficientDet-Lite0 object detector stays on-device.

## Voice
- Listening has priority over autonomous TTS, so pet speech cannot cancel the microphone session.
- Partial text, RMS and exact recognition errors are shown in TEST mode.
- One retry for NO_MATCH / SPEECH_TIMEOUT, then on-device -> system recognizer fallback where appropriate.
- Android 13+ recognition support is queried to populate real available language choices.
- Installed Android TTS voices are enumerated.
- Voice style presets: Robot, Normal, Cute, Deep, Tiny Bot, Calm.

## Optional Gemini Brain
- Local brain always works without an API key.
- API key can be pasted later in Settings; no APK rebuild is needed to add a key.
- Key is encrypted with Android Keystore AES/GCM before local storage.
- App dynamically fetches Gemini models supporting `generateContent`.
- AUTO model selection plus selectable/custom model name.
- Gemini is only a high-level personality/planning layer. Allowed actions are bounded (chat/greet/play/search/follow/look/fork/rest); it never receives direct motor-speed authority.
- Failure/network/model problems fall back to the local brain for user speech.

## Another-phone remote
- Optional local HTTP remote server for the same Wi-Fi/hotspot.
- Random six-digit PIN.
- Live JPEG camera preview reuses the same camera frames as on-device AI; the camera is not opened twice.
- Manual movement, fork controls, STOP, autonomy/manual toggle and status.
- Manual remote mode suppresses autonomous motor commands.
- Dead-man STOP when remote movement commands stop arriving.
- ESP32 safety telemetry still overrides movement.
- This is a LAN convenience remote; it is not an Internet-facing secure remote service.

## TEST / SIM ESP
- SIM ESP pretends the controller is connected/safe but sends no real hardware traffic.
- TEST panel shows raw -> derived gesture, object/confidence, voice state, Gemini state, internal drives, remote info and motor/fork commands that would be sent.

## Validation before packaging
- `Models.kt` + `PetBrain.kt` compiled with local `kotlinc` successfully.
- A smoke test confirmed: face lost -> wait, later small body turn; face reappears -> STOP/cancel search; merely seeing an off-center/far face does not auto-follow; come-here starts a follow session.
- The complete Android project still needs the GitHub Actions Gradle build after this overlay is pushed. Do not treat this note as proof that the Android APK has compiled until that workflow is green.
