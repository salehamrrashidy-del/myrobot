# MiRobotAI v0.9.1 — API diagnostics fix

This build stops guessing whether an API key works.

## AI setup
1. Select **Gemini Live** or **OpenAI Realtime**.
2. Paste the matching provider key and tap **Save provider**.
3. Tap **1) TEST API KEY**.
   - `KEY OK ✅` means authentication is valid.
   - `HTTP 401/403` means the key/project is not accepted.
   - `HTTP 429` means quota/rate/billing restriction.
4. Tap **2) CONNECT AI**. Wait for `AI READY 🎙️`.
5. Tap **3) TEST CUTE ARABIC VOICE** or just talk.

## Fixes
- OpenAI default model updated to `gpt-realtime-2.1-mini`.
- OpenAI no longer says READY before the Realtime session is actually accepted.
- Gemini still uses `gemini-3.1-flash-live-preview`.
- Key validation is separate from realtime audio so you can tell whether the problem is the key or the voice connection.
- WebSocket failures now show more useful HTTP/detail text.

Gemini keys only work with Gemini, and OpenAI keys only work with OpenAI. Custom endpoints must implement an OpenAI-Realtime-compatible WebSocket protocol.
