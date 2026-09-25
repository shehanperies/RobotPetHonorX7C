/*
  RobotPet ESP32 protocol reference firmware.
  Hardware pins are intentionally placeholders: set them for the final forklift chassis.
  Requires an ESP32 WebSocket server library before flashing; this file documents the safety contract
  the Android app expects and is not flashed automatically by the Android build.

  Android -> ESP32 JSON:
    {"type":"heartbeat","ts":...}
    {"type":"command","command":"FORWARD|BACKWARD|LEFT|RIGHT|STOP|FORK_UP|FORK_DOWN","durationMs":500}

  ESP32 -> Android at least 4x/second:
    {"type":"telemetry","safeToMove":true,"obstacleCm":45.0,"batteryPercent":82}

  HARD SAFETY RULES:
  1) If heartbeat is older than 1500 ms => stop drive motors and fork.
  2) If front obstacle is below your configured threshold => stop, regardless of phone command.
  3) Never let a network command bypass local hardware limits/end-stops.
*/

unsigned long lastHeartbeatMs = 0;
const unsigned long HEARTBEAT_TIMEOUT_MS = 1500;

void emergencyStop() {
  // TODO: set both motor drivers and fork motor to STOP for your real pinout.
}

void setup() {
  Serial.begin(115200);
  emergencyStop();
  // TODO: start Wi-Fi AP or join home Wi-Fi, then expose WebSocket path /ws.
}

void loop() {
  if (millis() - lastHeartbeatMs > HEARTBEAT_TIMEOUT_MS) emergencyStop();
  // TODO: read ultrasonic/ToF sensors, enforce local stop, and send telemetry.
}
