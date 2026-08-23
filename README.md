Mi Robot AI v0.7.2

Build compatibility fix for the v0.7 companion vision release.

# MiRobotAI v0.7 — Companion Vision

Keeps the working Mi Robot Builder BLE motor control and the LOOI-style face from v0.6.

## New in v0.7
- Front camera runs invisibly behind the robot face.
- On-device ML Kit face detection (camera frames are not uploaded).
- Eyes look toward a detected face.
- Companion mode can gently rotate the robot to keep a face centered.
- Experimental gentle roam mode: tiny low-speed movement pulses with automatic stop.
- A conservative "focus-like" heuristic notices when a person stays still for ~90 seconds and makes the robot curious. It does **not** claim to know what the person is thinking.
- Existing hidden Happiness / Curiosity / Boredom meters remain slow and invisible.
- Manual drive still works and temporarily pauses autonomous movement.

## Safety
Roam mode is deliberately OFF by default. The current robot has no true distance sensor, so only use roam in an open floor area. Autonomous pulses are short and low-speed.

## Owner recognition
This version detects and tracks faces but does not yet identify the owner. Reliable owner recognition needs an on-device face-embedding model and enrollment flow; that is the next vision step rather than using an unreliable shortcut.

## Controls
Long-press the face to open the hidden control panel.


## v0.7.2 build fix
Added `gradle.properties` with AndroidX enabled so CameraX and ML Kit dependencies build correctly in GitHub Actions.
