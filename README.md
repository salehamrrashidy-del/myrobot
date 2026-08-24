# MiRobotAI v1.0.0 — Edge Guard + AI robot control

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
