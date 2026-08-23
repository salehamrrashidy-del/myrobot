# MiRobotAI v0.9.7

AI connection repair build.

- Gemini Live setup now uses the canonical `setup.generationConfig.responseModalities` wire format used by Google's current GenAI SDK.
- Rejects non-Live Gemini models before opening the voice socket.
- Migrates an accidentally saved normal Gemini model back to `gemini-3.1-flash-live-preview`.
- Shows whether the setup frame was actually sent and gives better parse/close diagnostics.
- Gemini endpoint remains automatic; do not fill the Text/Endpoint field when Gemini is selected.

# MiRobotAI v0.9.6 — Gemini Live setup fix

This build focuses on the Gemini Live connection handshake.

Changes:
- Uses `gemini-3.1-flash-live-preview` by default.
- Sends the initial Gemini WebSocket setup in the same shape as Google's current raw WebSocket quickstart (`responseModalities` directly in `setup`).
- Waits up to 30 seconds for `setupComplete` instead of 10 seconds.
- Handles both text and binary WebSocket messages.
- Shows the first unexpected Gemini setup message type instead of silently waiting.
- Microphone starts only after `setupComplete`.

Use Gemini Live, keep the default model, save one Gemini API key, then tap Connect AI.
