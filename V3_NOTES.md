# RobotPet V3 Living Pet upgrade

This overlay adds:

- EfficientDet-Lite0 int8 pretrained COCO object detection (80 labels)
- richer gesture set: wave, stop palm, thumbs, point L/R/up/down, come-here, circle turn-around, shh, peace, I-love-you, fist/hit reactions
- face smile/wink/head movement reactions
- lost-person search behavior
- expressive fork macros
- phone shake/tilt/upside-down/light reactions
- persistent mood/annoyance
- battery/charging reactions
- improved voice diagnostics, live partial text, RMS meter and on-device -> system fallback
- TEST mode with SIM ESP so movement/fork decisions can be inspected without ESP32 hardware
- portrait + landscape full-sensor rotation

The SIM ESP mode never sends commands to real hardware.
Real movement remains gated by ESP32 safeToMove telemetry.
