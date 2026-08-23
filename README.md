# MiRobotAI v1.0.0 — live info + tools

This build restores current-information abilities to Gemini Live.

## New in v1.0.0
- Adds Gemini Live Google Search grounding for current weather, news, recent facts, prices, sports, and other changing information.
- Adds a local `get_current_datetime` function so the robot reads the phone's real local time/date/timezone instead of guessing.
- Handles Gemini Live `toolCall` and sends the required `toolResponse` back over the same WebSocket.
- Keeps the saved API key, voice selector, anti-self-interruption audio handling, BLE control, face tracking, companion behavior, and existing personality.

## Test after install
1. Connect Gemini Live.
2. Ask: `What time is it?` — status should briefly show `Clock checked on phone ✅`.
3. Ask: `What's the weather today in Alexandria?` — Gemini should use Search grounding.
4. Ask a recent fact, for example: `What happened in the news today?`

## Build
Upload/replace the repo files, commit, open GitHub Actions, and run Build Android APK.
Artifact: `MiRobotAI-v1.0.0-debug-apk`.

Important: do not uninstall the app if you want Android to keep the encrypted saved API key. Install this build as an update over v0.9.9 when possible.
