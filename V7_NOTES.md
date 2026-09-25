# Welly V7 — coordinated rebuild

This patch is intentionally focused on the main failure reported in V6: detections and reactions overlapping until the robot feels like a “fruit salad”.

## Core rule

**Perception may run together. Behavior may not.**

Camera face tracking, hand recognition, object recognition, kiss detection and sensors continue to update the world state. They do not each own the robot. A single PRIMARY behavior lane decides what Welly is doing now.

## Behavior priority

1. Safety / emergency STOP
2. Remote manual
3. Explicit spoken movement/STOP command
4. Listening / speaking turn
5. Confirmed gesture / kiss / tickle
6. Touch / petting
7. Follow session
8. Lost-person search
9. Stable vision/social reaction
10. Phone/battery context
11. Gemini high-level suggestion
12. Idle/self-play

A lower-priority event waits/drops instead of cancelling the current reaction. Only a higher-priority event can preempt it.

## Event fusion / anti-overlap

- Raw detector frames update diagnostics only.
- Vision brain sampling is throttled.
- Confirmed gesture/kiss events are one-shot.
- Objects must remain stable before becoming an event.
- Repeated identical vision events have an event-level duplicate gate.
- Idle/spontaneous actions start only from the idle heartbeat, not from every camera frame.
- A running fork/drive sequence cannot be replaced by another normal behavior.
- TTS and microphone turns own the PRIMARY lane so reactions do not talk over each other.
- Safety and remote control remain able to preempt immediately.

## Wake word

Robot name: **Welly**.

While the robot-face app is open, Android SpeechRecognizer runs a lightweight foreground wake listener. Saying **“Welly”** or **“Hey Welly”** pauses the wake listener, stops autonomous motion, then opens the normal command listener. After the conversation finishes, the wake listener resumes.

This is deliberately a no-key “soft wake word” using Android recognition, not a DSP-level always-on assistant hotword engine. That keeps the APK small and avoids another paid/custom model dependency.

## Features retained behind the coordinator

- Face tracking, smile/wink/head reactions
- Kiss + blown kiss
- Stable STOP/wave/come-here/turn/point/peace/love/thumbs/fist/hit-swing gestures
- Multi-object detection and tracking
- Explicit safe object nudge flow (still sensor-gated)
- Follow session and bounded lost-person search
- Tickle -> real FORK_UP/FORK_DOWN burst
- Touch/petting and mood/drives
- Sleep/wake, boredom/social/self-play/context prompts
- Android speech recognition + partial text + TTS voice selection
- Gemini optional high-level chat/personality with local brain fallback and rate limiting
- ESP WebSocket heartbeat, telemetry, safety gating, command sequence and ACK support
- Same-Wi-Fi remote control/watchdog
- SIM ESP/test diagnostics

## Hardware boundary

The app can decide and transmit DRIVE/FORK commands. Actual physical safety still belongs on ESP32: motor-driver pins, local obstacle sensors, fork limit switches/end-stops, heartbeat timeout and hard STOP behavior must be implemented in firmware before unrestricted physical movement.
