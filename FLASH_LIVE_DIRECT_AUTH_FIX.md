# MiRobotAI v1.8.2 — Gemini 3.1 Flash Live Direct Authentication

The previous build failed before opening the Live socket because `/v1beta/auth_tokens`
returned HTTP 401.

This version follows Google's raw WebSocket example directly:

`wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent?key=YOUR_API_KEY`

Only the query-string API key is used for the Live WebSocket. No second Gemini auth
header is added.

The app also identifies the key type locally without displaying the key:
- `AQ.` = authorization key
- `AIza` = standard API key

If Google still rejects the Live connection with code 1008, the app will include the
detected key type in the message.

Barge-in, stop-on-speech, movement tools, BLE control, and Edge Guard remain unchanged.