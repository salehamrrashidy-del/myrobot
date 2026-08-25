# MiRobotAI v1.7.0 — Edge Guard + AI robot control

This build adds three big changes while keeping the saved API key and softer voice from v0.9.9.

## 1) Experimental camera Edge Guard
- Uses the phone's front camera to look for a strong horizontal boundary low in the image that may be the edge of a table.
- Requires several consecutive frames before it blocks movement.
- When active, forward movement is stopped/blocked locally.
- Roam mode automatically enables Camera + Edge Guard.
- The AI cannot override the Edge Guard.

**Important:** this is only a visual heuristic. It is not a real cliff sensor. It works best only when the camera can actually see the tabletop/floor ahead. Test it on the floor first and do not trust it unattended on an elevated table. A downward ToF/IR cliff sensor is still the proper hardware solution.

## 2) Safer roaming
- Roam uses shorter, slower pulses.
- Turning is more common than moving forward.
- No automatic reverse because the front camera cannot see behind the robot.
- Forward nudges only happen while Edge Guard reports safe.
- If an edge is detected, the robot stops and only uses tiny in-place look-away turns.

## 3) Gemini AI can control the physical robot
Gemini Live now receives bounded robot tools:
- `robot_stop`
- `robot_turn(left/right)`
- `robot_nudge(forward)` — short only, blocked by Edge Guard
- `set_roam_mode(on/off)`
- `set_companion_mode(on/off)`
- `set_expression(...)`

The app executes the action locally and sends the result back to Gemini. The model never gets raw motor values and cannot bypass local safety.

Example voice commands:
- "لف شمال شوية"
- "قف"
- "شغل وضع التجول"
- "بص ناحيتي"

## API key persistence
- Keys remain encrypted on the phone with Android Keystore.
- Paste once, tap **SAVE / CHANGE KEY**, then just use **Connect AI** later.
- Do not uninstall the app or clear storage if you want the saved key to remain.

## Build
Upload/replace this folder in the same GitHub repository, then run **Build Android APK** in Actions.
Artifact: `MiRobotAI-v1.0.0-debug-apk`

> The bundled signing key is for this personal development build only. Replace it before publishing a production app.


## v1.1 Companion Architecture
- Added SafetyEngine between AI decisions and motors.
- Added MotorStateManager for movement feedback.
- Added BehaviorEngine for companion-style decisions.


## v1.2 Integration step
- Added CompanionBrain for mood-driven decisions.
- Added MovementGate as final motor safety checkpoint.


## MiRobotAI v1.3 Controller Integration
- Added RobotControllerCoordinator.
- Added MotorCommandQueue for movement command control.
- Prepared behavior-to-safety-to-motor integration layer.


## MiRobotAI v1.4 Companion Behavior
- Added companion behavior decisions.
- Added owner interaction cooldown.
- Prepared mood-driven robot actions.


## MiRobotAI v1.5 Real Companion Loop
- Added perception/emotion/behavior loop foundation.
- Added safe action bridge between behavior and movement.
- Prepared full companion decision flow.


## v1.5.1 Build Fix
- Fixed RobotActionBridge to use the existing MovementGate allowForward() method.


## MiRobotAI v1.6 Gemini Robotics 2
- Updated default Gemini model selection to Gemini Robotics 2.
- Kept model configurable for API compatibility.


## MiRobotAI v1.6.1 Fix
- Restored a valid Gemini Live API model ID.
- Gemini Robotics 2 remains a companion architecture mode, not an invalid API model string.


## v1.7.0 — Gemini Robotics ER 2 Streaming

- Default Gemini model is now the official `gemini-robotics-er-2-streaming-preview`.
- Uses the Gemini Live API robotics streaming pattern: audio input, text output, BLOCKING robot tools.
- Robotics text replies are spoken through local Android TTS so the robot remains conversational.
- New canonical movement tool: `robot_move(direction)`.
  - `forward` -> short safe forward pulse (blocked by Edge Guard if unsafe).
  - `left` / `right` -> short in-place turn.
  - `stop` -> immediate stop packet.
  - `backward` -> blocked while Edge Guard is enabled because the front camera cannot observe the rear cliff; when Edge Guard is deliberately off, only a very short reverse pulse is allowed.
- The debug/status panel now shows the exact command chain and motor-axis values that fire for voice movement requests.
- Tool responses are sent after the movement pulse completes, matching BLOCKING function-call semantics more closely.

### Voice movement path

`microphone -> Gemini Robotics ER 2 -> robot_move(direction) -> MainActivity.onRobotToolCall -> local safety -> autoPulse -> sendMove -> MiRobotBleManager.move -> MiRobotProtocol.rockerPacket -> BLE`

With default inversion settings and bounded pulses:
- forward (`delta=9`) -> axisA=137, axisB=137
- backward (`delta=8`, Edge Guard off only) -> axisA=120, axisB=120
- left (`delta=11`) -> axisA=117, axisB=139
- right (`delta=11`) -> axisA=139, axisB=117
- stop -> axisA=128, axisB=128


## v1.7.1 — Robotics WebSocket 1008 fix
- Robotics setup now uses the minimal official Live API configuration.
- Removed optional Robotics VAD/realtime setup fields that can be rejected.
- Simplified the Robotics function-tool list while preserving `robot_move`.
- Added clearer WebSocket 1008 diagnostics.

## v1.7.2 — Gemini Robotics authentication fix

The Robotics WebSocket authentication path now:
- trims accidental whitespace/newlines from the saved Gemini key before connecting;
- keeps the official Live API `?key=` authentication;
- also sends `x-goog-api-key` during the WebSocket HTTP upgrade for current AI Studio auth keys;
- uses `x-goog-api-key` for the in-app Gemini key/model test;
- never treats a Gemini API key as an OAuth Bearer token.

If WebSocket 1008 reports invalid authentication again, the app now identifies it as an authentication-stage failure. At that point no `robot_move` tool call has fired and no movement command has reached BLE/motors.
