# Mi Robot AI v0.3

This version fixes the BLE connection sequence by copying the original Mi Robot Builder Android app more closely.

## What changed

- Uses the original app's BLE handshake: `57 01 02 AA`.
- Enables notifications on `6E400003...` before handshaking.
- Writes commands to `6E400002...` using normal **Write With Response**, as the original app does.
- Retries the handshake up to 3 times, roughly matching the original app's 150 ms retry behavior.
- Only shows **ROBOT READY** after the handshake write succeeds.
- Keeps the native 20-byte rocker command format and XOR byte used by the original app.
- Adds TX/RX logging so we can diagnose the next problem without guessing.

## First test

Keep the wheels off the floor.

1. Turn the Mi Robot controller on.
2. Open Mi Robot AI v0.3.
3. Tap **Scan for robot**.
4. Tap the Mi Robot device.
5. Wait for `4/4 ROBOT READY ✅`.
6. Hold Forward briefly, then release.

If it does not reach ROBOT READY, take a screenshot of the status/log area.
If it reaches ROBOT READY but wheels do not move, take a screenshot of the TX log.
