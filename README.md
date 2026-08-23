# MiRobotAI v0.9.5

Fixes the long Gemini “setting up audio” wait.

- Uses current `gemini-3.1-flash-live-preview` by default.
- Uses a minimal Gemini Live setup first (audio + persona only) to reduce setup failures.
- No automatic 1008/1011 retry loop.
- Setup has a 10-second timeout, so it never hangs indefinitely.
- Once the realtime session is stable, voice selection can be added back safely.

Build with the included GitHub Actions workflow and install the debug APK.
