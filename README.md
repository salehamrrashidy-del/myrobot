# MiRobotAI v0.9.3 — Gemini native-audio fix

This build fixes the Gemini Live `1007 / Invalid JSON payload received` problem caused by sending `languageCode` to a native-audio Live model. Native-audio models auto-detect the spoken language, so Egyptian Arabic is enforced through the robot system instructions instead.

## Test
1. Select Gemini Live.
2. Save one Gemini API key.
3. Tap TEST API KEY.
4. If KEY OK, tap CONNECT AI.
5. Wait for `Gemini Live READY` before speaking.

Keep the robot wheels off the floor while testing autonomous movement.
