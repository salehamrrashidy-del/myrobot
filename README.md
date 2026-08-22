# Mi Robot AI v0.1 — Motor Controller

This is the first step toward the AI companion robot: control the original Mi Robot Builder controller directly from an Android phone over BLE.

## What was recovered from Mi Robot Builder 1.5.0

The original app contains these BLE UUIDs:

- Service: `6e400001-b5a3-f393-e0a9-e50e24dcca9e`
- Write: `6e400002-b5a3-f393-e0a9-e50e24dcca9e`
- Notify: `6e400003-b5a3-f393-e0a9-e50e24dcca9e`

The native library also contains the command constructors.

Connect/handshake packet:

`55 01 01 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 AA`

Rocker/motor packet layout:

`55 02 02 00 [motorA] [motorB_hi] [motorB_lo] 00 ... 00 AA`

`128` is the neutral value used by the original app. The app sends `128/128` when stopping.

## First run

1. Open this folder in Android Studio.
2. Allow Gradle to sync.
3. Connect the Redmi Note 12 to the computer with USB debugging enabled, or build/install the debug APK.
4. Turn the Mi Robot controller on.
5. Launch **Mi Robot AI** and allow Nearby Devices permission.
6. Tap **Scan for robot** and tap the Mi Robot device from the list.
7. Wait for **Robot ready ✅**.
8. For the first movement test, keep the wheels off the ground.
9. Hold Forward briefly; releasing the button automatically sends STOP.
10. If Forward spins the wrong way, change the **Invert left/right motor** checkboxes. This is expected because custom builds can mount the motors in different orientations.

## Safety built into v0.1

- Movement happens only while a direction button is held.
- Releasing the button sends a STOP packet.
- The default speed is low.
- Closing the app attempts to send STOP before disconnecting.

## Next versions

- v0.2: animated robot eyes/fullscreen face
- v0.3: speech recognition + text-to-speech
- v0.4: camera/person tracking
- v0.5: AI agent with safe actions such as `turn_left`, `follow_person`, and `stop`
