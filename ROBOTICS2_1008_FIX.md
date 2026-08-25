# Gemini Robotics ER 2 — WebSocket 1008 Fix

This version changes the Robotics ER 2 streaming session setup to match the
official minimal Live API robotics pattern more closely.

## Changed

- Model remains: `gemini-robotics-er-2-streaming-preview`
- Output remains: `TEXT`
- Physical function calls remain `BLOCKING`
- `robot_move(direction)` remains the movement tool for:
  - `forward`
  - `backward`
  - `left`
  - `right`
  - `stop`
- Removed optional `realtimeInputConfig` / custom VAD fields from Robotics mode.
  Normal Gemini Live audio modes still keep their previous VAD settings.
- Removed legacy movement aliases from the Robotics tool list to reduce setup
  complexity. They remain available for non-Robotics Live models.
- Added a clearer on-screen diagnostic when WebSocket close code `1008` occurs.

## Important

A successful model metadata/key check does not guarantee that a Live Robotics
session is allowed for the same project/key. If 1008 still occurs with this
minimal setup, record the full close reason shown by the app. That points to
API/project access rather than the robot motor code.

## Movement route

`voice -> Gemini Robotics ER 2 -> robot_move(direction) -> app safety -> BLE -> motors`

The AI never receives raw motor power values and cannot bypass the local safety
layer.
