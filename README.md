# RobotPetHonorX7C

Android robot-pet brain designed for an Honor X7c mounted in a modified CyberBrick-style forklift chassis.

## What is implemented
- Full-screen animated robot eyes: blink, gaze, happy/curious/listening/sleepy/startled states.
- Front-camera vision with CameraX 1.6.2 + bundled ML Kit face detection + object detection/tracking.
- Face tracking drives eye gaze immediately. Autonomous body follow is optional.
- Android SpeechRecognizer: prefers on-device recognition on Android 12+ when the phone provides it, otherwise uses the system recognizer.
- Android TextToSpeech responses.
- Pet behavior engine: touch reaction, idle curiosity, auto-sleep, voice commands, face following decisions.
- ESP32 WebSocket link with 1-second heartbeat and telemetry gating.
- Safety rule: the Android app refuses non-STOP motion unless ESP32 telemetry says `safeToMove=true`.
- Settings UI for WebSocket URL and autonomous follow.
- GitHub Actions cloud build producing a downloadable debug APK artifact.
- Unit tests for safety-critical brain decisions.

## Why the source repository is not huge
Android/CameraX/ML Kit/Compose/OkHttp libraries are Maven dependencies. They are downloaded by Gradle during the cloud build instead of being copied into this repository. The compiled APK is therefore much larger than the source files.

## Build entirely from a phone
Push to `main` and open **Actions -> Build Android APK**. When the green build finishes, open it and download the artifact named `RobotPetHonorX7C-debug-apk`.

## First phone run
Grant Camera and Microphone. The face works even with no ESP32. In Settings, the default controller URL is `ws://192.168.4.1/ws`. Change it later to match the ESP32.

## ESP32 safety contract
The controller must send telemetry such as:
```json
{"type":"telemetry","safeToMove":true,"obstacleCm":42.0,"batteryPercent":80}
```
It must independently stop motors if phone heartbeats disappear for >1.5 seconds, an obstacle is too close, or a hardware limit switch trips. See `esp32/RobotPetController.ino`.

## Current limitation
This build is the phone brain and protocol. Final motor pins, motor driver type, ultrasonic/ToF placement and fork limit-switch wiring cannot be fixed in code until the physical chassis/electronics are chosen. The included ESP32 file therefore documents and enforces the software safety contract but leaves hardware-specific pin writes as TODOs.
