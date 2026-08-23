# MiRobotAI v0.9.4

Gemini Live stability update.

- Defaults Gemini to the official free-tier `gemini-2.5-flash-native-audio-preview-12-2025` Live model.
- Migrates the previous Gemini 3.1 setting automatically.
- Uses the youthful `Leda` voice.
- API test checks access to the exact Live model, not just the account key.
- Retries transient Gemini WebSocket 1008/1011 setup failures twice.
- Direct audio-in/audio-out; no Android TTS.
