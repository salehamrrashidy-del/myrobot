# Mi Robot AI v0.4 — Character Face + Mood

This version keeps the working Mi Robot BLE/motor control from v0.3 and adds the first real character layer.

## What is new
- Fullscreen landscape animated robot face
- Automatic blinking
- Happy / normal / curious / bored / upset / sleepy / surprised / talking expressions
- Mood/attention system that slowly changes when the robot is ignored
- Tap the face to give attention and cheer him up
- Long-press the face to open the hidden robot-control panel
- Emotion preview buttons in the hidden panel
- Existing BLE connection + forward/back/left/right/stop remain available
- No Android Text-to-Speech is used

## Voice
The intended voice path is natural speech-to-speech (audio in -> AI -> audio out), not Android TTS. A secure realtime voice connection needs a small backend that creates a short-lived client credential; never put a permanent API key in the APK.

## Build on GitHub
Upload the contents of this folder to the same repository. GitHub Actions builds `MiRobotAI-v0.4-debug-apk`.
