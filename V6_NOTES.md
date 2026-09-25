# RobotPetHonorX7C V6 Rebuild

This package is an overlay for the existing V5 repository. Extract it at the repository root with overwrite enabled.

## Core architecture

V6 routes behavior through:

`Perception -> PetBrain proposal -> BehaviorExecutive -> Action executor -> RobotLink/ESP safety`

Detectors no longer directly own motion. BehaviorExecutive leases DRIVE, FORK, FACE, SPEECH and MIC resources by priority. Safety and explicit STOP can preempt lower behavior; ordinary camera/idle events cannot steal an active fork/speech/drive sequence.

## Main changes

- Central behavior/resource arbitration and duplicate suppression.
- Separate motion/sequence jobs so ordinary detections do not cancel expressive fork actions.
- MediaPipe gesture history rebuilt for stable STOP, wave, come-here, circle/turn, point and hit/swing events.
- Kiss detection using ML Kit lip contours with temporal confirmation; blown-kiss combines pucker + hand near face + outward hand movement.
- Rapid triple screen tap acts as a tickle and emits a quick real FORK_UP/FORK_DOWN burst.
- Multi-object detection now keeps several detections, sorts by confidence and adds lightweight track IDs instead of selecting only the largest bounding box.
- Explicit `move that` / `push that` supports a tiny object nudge only when ESP distance telemetry is present and safe.
- Voice recognition and TTS language sources are separated. TTS languages are never presented as proof that speech recognition supports them.
- Voice state reports STARTING_MIC/LISTENING/HEARING/PROCESSING/SPEAKING; real installed TTS voice is shown and selectable voice choices preview immediately.
- Gemini periodic 12-second polling removed. Optional autonomous AI planning is rare and local behavior remains primary.
- Gemini receives character settings as system instructions, keeps bounded conversation history, requests JSON output, and opens an exponential cooldown circuit breaker on HTTP 429.
- Character settings: robot name, owner name, personality instructions and never-do rules.
- Eye proportions rebuilt: emotion no longer flattens eyes into thin capsules; blink is the main vertical squash.
- Robot protocol adds command sequence IDs and optional ESP ACK display (`type=ack`, `seq`, `accepted`, `reason`).
- V6 debug panel shows behavior locks, gesture candidate/confirmation stage, top object labels, kiss state, voice phase, active TTS voice, Gemini cooldown and TX/ACK state.

## Hardware boundary

The Android app still does not pretend camera-only vision is collision-safe. Real drive/fork movement remains gated by ESP telemetry. Object nudging additionally requires a usable center/obstacle distance reading. Fork limit switches and local ESP hard stops remain recommended before physical testing.

## Install overlay in Codespaces

If the ZIP is uploaded to the repository root:

```bash
unzip -o RobotPetHonorX7C-V6-Patch.zip
rm RobotPetHonorX7C-V6-Patch.zip
git add -A
git commit -m "Add V6 central living pet brain"
git push
```

GitHub Actions should then run unit tests and build `RobotPetHonorX7C-v6-debug-apk`.
