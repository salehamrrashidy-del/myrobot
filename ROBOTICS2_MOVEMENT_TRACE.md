# MiRobotAI v1.7.0 — Robotics ER 2 Movement Trace

Official streaming model:
`gemini-robotics-er-2-streaming-preview`

## What fires for a voice command

Example: "move forward" / "اتحرك لقدام"

1. Android microphone streams 16 kHz PCM audio to Gemini Robotics ER 2 Streaming.
2. The model selects the BLOCKING tool:
   `robot_move({"direction":"forward"})`
3. `RealtimeVoiceController.handleGeminiEvent()` receives the tool call.
4. `MainActivity.onRobotToolCall()` routes `robot_move` to `executeRobotMoveTool()`.
5. Local safety runs first:
   - robot Bluetooth handshake must be ready.
   - forward motion must pass Edge Guard.
6. `autoPulse(+1, 0, 9, 150ms)` runs.
7. `sendMove()` converts the bounded intent to motor axes.
8. `MiRobotBleManager.move()` queues the BLE write.
9. `MiRobotProtocol.rockerPacket()` creates the motor packet.
10. After the pulse, a neutral stop packet is sent.
11. Only after the bounded pulse finishes does the app send the tool result back to Robotics ER 2.

## Default motor-axis preview

These are the values before any left/right inversion checkbox changes:

| Voice direction | Local action | axisA | axisB |
| --- | --- | ---: | ---: |
| forward | short pulse | 137 | 137 |
| backward | short pulse, Edge Guard must be OFF | 120 | 120 |
| left | small in-place turn | 117 | 139 |
| right | small in-place turn | 139 | 117 |
| stop | neutral stop | 128 | 128 |

## Safety behavior

- Forward is blocked immediately when Edge Guard sees a possible drop.
- Backward is blocked whenever Edge Guard is enabled because the front camera cannot verify the rear edge.
- Left/right are bounded short turns.
- Stop is immediate and does not require AI approval.
- Gemini never receives raw motor authority; it requests a bounded tool and the Android app decides whether to execute it.
