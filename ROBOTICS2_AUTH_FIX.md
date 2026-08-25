# Gemini Robotics ER 2 — Authentication Fix

Observed server close:
`1008: Request had invalid authentication credentials. Expected OAuth 2 access token, login cookie or other valid authentication credential.`

## What this means

The WebSocket connection was rejected during authentication. The model setup,
tool declarations, safety engine, BLE layer, and motor commands had not run yet.

## v1.7.2 changes

1. Trim the saved API key before the WebSocket handshake.
2. Authenticate the raw Live WebSocket with the documented `?key=` parameter.
3. Also include `x-goog-api-key` in the WebSocket upgrade request so current
   Google AI Studio authorization keys are presented in the standard Gemini API
   API-key header form.
4. The in-app key/model test now uses `x-goog-api-key`.
5. The app does not send `Authorization: Bearer <Gemini API key>`.

## Movement path after authentication succeeds

`voice -> Gemini Robotics ER 2 -> robot_move(direction) -> local safety -> BLE -> motors`

Until the Live session is authenticated and setup completes, no movement tool
can fire.
