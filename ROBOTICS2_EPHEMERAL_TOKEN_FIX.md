# v1.7.3 Gemini Robotics Live authentication fix

The previous build authenticated the raw Live WebSocket directly with the saved Gemini API key. The server returned WebSocket 1008 with an OAuth/authentication error.

This version follows Google's documented client-to-server Live API flow:

1. The app uses the saved Gemini API key only to request a short-lived Live token from `POST /v1beta/auth_tokens`.
2. The app connects the Robotics WebSocket using that token via the `access_token` query parameter.
3. The long-lived Gemini API key is not sent directly on the Robotics WebSocket.

Movement remains:
`voice -> Gemini Robotics ER 2 -> robot_move(direction) -> local safety -> BLE -> motors`

If the token provisioning request is rejected, the app shows the HTTP status and server response before opening the WebSocket.
