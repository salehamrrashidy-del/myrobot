# MiRobotAI v0.9 — Multi-provider AI

This build keeps the working Mi Robot BLE motor control, LOOI-style face, face tracking, companion mode, roam mode, and hidden mood meters.

## AI providers

The hidden control panel now lets you choose:

1. **Gemini Live** — paste a Google Gemini API key. Default model: `gemini-3.1-flash-live-preview`.
2. **OpenAI Realtime** — paste an OpenAI API key. Default model: `gpt-realtime-mini`.
3. **Custom OpenAI-compatible** — paste a key, model name, and WebSocket endpoint for a service that implements the OpenAI Realtime WebSocket protocol.

API keys are stored separately per provider and encrypted with Android Keystore.

## Important

There is no universal API-key format. A key only works with the provider/protocol it belongs to. Gemini and OpenAI use different WebSocket message formats, so v0.9 includes separate adapters for both. A completely different provider needs its own adapter unless it implements the OpenAI Realtime protocol.

## Use

Long-press the robot face → AI voice & personality → choose provider → paste key → Save provider → Connect AI.

For Gemini the endpoint is automatic. For OpenAI the standard endpoint is prefilled. For Custom, enter the provider's realtime WebSocket endpoint and model.

The normal robot face never shows transcripts or secret keys.
