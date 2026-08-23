# MiRobotAI v0.10.0 — safer roaming + working companion mode

This build keeps the v0.9.9 AI/key/voice work and focuses on autonomy.

## What changed

- **Companion mode now actively searches for you.** If it cannot see a face, it makes small scan turns.
- **Companion mode can gently approach you.** When your face is centered and still looks far away, it uses short forward pulses.
- Face detection now accepts smaller/farther faces (`minFaceSize` lowered from 0.16 to 0.08), so companion mode can react before you are already very close.
- **Free roam is more visible and regular** with short forward/turn exploration pulses.
- Removed the old long 4.5–6 second autonomy pauses around every AI speech event. Movement stops while either person is actually speaking, then resumes shortly afterward.
- **Experimental camera table-edge guard** added.
  - Put the robot safely in the middle of the table.
  - Aim the front camera slightly downward so the lower part of its view contains the tabletop.
  - Tap **CALIBRATE SAFE SURFACE** and keep it still for about a second.
  - With Edge Guard ON, autonomous forward movement is blocked unless the camera has a fresh calibrated safe-surface reading.
  - If the image changes like a possible edge, the robot stops and free roam makes only a tiny turn-away pulse.
  - Autonomous reverse is disabled while Edge Guard is ON because a front camera cannot see a cliff behind the robot.

## Important safety limitation

A phone camera is **not a real cliff sensor**. Lighting, shadows, a patterned/glass table, camera angle, or motion blur can fool visual edge detection. Do not leave this robot unattended on a high table. Test the guard at very low speed while keeping a hand ready to catch the robot. For dependable table-top roaming, dedicated downward-facing IR/ToF cliff sensors are much safer.

## Quick test

1. Install/update the APK and reconnect the Mi Robot controller until it says **ROBOT READY**.
2. For floor testing, turn **Table edge guard OFF**, then enable **Free roam**. It should begin moving within a couple of seconds.
3. For table testing, leave **Table edge guard ON**, put the robot in the safe center, tap **CALIBRATE SAFE SURFACE**, wait for **Edge guard ✅**, then enable **Free roam**.
4. For **Companion mode**, keep Camera face tracking ON and stand/sit in front of the robot. It should turn toward your face and, if you look far enough away, take small forward steps toward you.

GitHub Actions artifact: `MiRobotAI-v0.10.0-debug-apk`
