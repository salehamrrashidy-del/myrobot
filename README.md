# MiRobotAI v1.0.0 — Camera Safe Roam

This build keeps the working v0.9.9 AI, saved API-key behavior, voice selector, BLE motor control, face tracking, and emotion system, and adds a conservative camera safety layer for autonomous roaming.

## New in v1.0.0

- **Camera table/cliff guard** uses the lower part of the front-camera image while the screen remains the robot face.
- When Safe Roam is switched on, the robot stays still briefly while the camera learns the appearance of the current safe surface.
- **No autonomous forward motion** is allowed until the guard reports a stable SAFE surface.
- If the camera sees a likely edge/drop, movement stops immediately, the robot makes one tiny reverse pulse, turns away, and waits for a safe view before moving again.
- Random reverse roaming was removed because a front camera cannot see a rear cliff.
- Forward roam pulses are shorter and slower than before, with a camera re-check between moves.
- Roaming automatically turns off if camera vision is disabled.
- Existing Companion Mode still turns toward faces but does not drive toward them.

## Important physical setup

The front camera must be able to see **some of the tabletop/floor in the lower part of its view**. If the phone is perfectly upright and the camera only sees the room, software cannot reliably see a table edge. Tilt the phone/robot head slightly downward if needed.

This camera guard is a **heuristic extra safety layer, not a true depth/cliff sensor**. For unattended use on raised tables, a downward IR/ToF cliff sensor is still the reliable hardware solution. Test v1.0.0 on the floor first, then near a table edge while keeping a hand ready to catch the robot.

## Safe Roam test

1. Put the robot on the floor or in the middle of a large table, away from the edge.
2. Make sure **Camera face tracking** is ON.
3. Turn on **Safe roam — camera edge guard**.
4. Keep the robot still while it says `Table guard learning…`.
5. Wait for `Table guard READY ✅` / `Table guard SAFE ✅`.
6. Let it roam slowly.
7. During the first table-edge test, keep your hand next to the robot as a physical backup.

## Build

Upload the project to GitHub and run the included GitHub Actions workflow. The artifact is named `MiRobotAI-v1.0.0-debug-apk`.

The development signing key remains the same as v0.9.9, so Android should install this as an update and keep your saved API key.
