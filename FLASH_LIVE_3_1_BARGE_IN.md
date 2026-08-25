# MiRobotAI v1.8.0 — Gemini 3.1 Flash Live + Barge-In

## Model

Default Gemini model:

`gemini-3.1-flash-live-preview`

## When the user talks while the robot is speaking

1. The Android microphone remains active.
2. Android Acoustic Echo Canceler / Noise Suppressor are enabled when supported.
3. Local VAD detects the user's voice.
4. Any queued assistant audio is stopped immediately.
5. Gemini receives the new user audio.
6. Live API activity handling uses `START_OF_ACTIVITY_INTERRUPTS`.
7. Gemini interrupts the previous response and listens to the new turn.

The AI connection itself is **not disconnected**.

## Physical movement rule

Whenever user speech starts:

`onUserSpeechStarted() -> stopRobot() -> BLE STOP`

The companion controller is then paused for 4.5 seconds so roaming or face-following
does not immediately start moving while the user is speaking.

## Voice movement tools

The model still has bounded robot tools. Direct commands should route through:

`robot_move(direction)`

Directions:
- forward
- backward
- left
- right
- stop

Safety/Edge Guard remains local and can refuse movement.