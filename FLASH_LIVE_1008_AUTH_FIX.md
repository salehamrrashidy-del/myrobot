# MiRobotAI v1.8.1 — Gemini 3.1 Flash Live 1008 Authentication Fix

## Root cause found

v1.8.0 switched from Robotics ER 2 to Gemini 3.1 Flash Live, but the code only
created an ephemeral Live token when `isRoboticsMode()` was true.

That meant Gemini 3.1 Flash Live fell back to the old raw WebSocket path:

`wss://...BidiGenerateContent?key=<long-lived-api-key>`

The server rejected that Android WebSocket during authentication with close code
1008 and the message that valid OAuth / authentication credentials were expected.

## Fixed connection flow

For every Gemini Live model:

1. Read the saved Gemini API key.
2. POST to `https://generativelanguage.googleapis.com/v1beta/auth_tokens`
   with `x-goog-api-key`.
3. Create a one-use ephemeral token constrained to:
   - `models/gemini-3.1-flash-live-preview`
   - response modality `AUDIO`
4. Open the v1beta Live WebSocket with:
   `?access_token=<ephemeral-token>`
5. Send the normal Gemini Live setup.
6. Wait for `setupComplete`.
7. Start microphone/camera streaming.

## Important

The long-lived API key is no longer put directly into the Gemini Live WebSocket
for Flash Live.

## Movement behavior is unchanged

`voice -> Gemini 3.1 Flash Live -> robot tool -> local safety -> BLE -> motors`

When the user starts speaking:
- assistant audio is interrupted;
- physical robot movement is stopped locally;
- the Gemini connection stays open.