# MiRobotAI v0.8.1

This build moves the **AI voice & personality** controls to the very TOP of the hidden panel so they cannot be missed. The panel also shows **v0.8.1 — AI BUILD** in yellow.

# Mi Robot AI v0.8.1 — Realtime Voice + Roam Fix

This is the first AI-speaking build.

## New in v0.8.1

- Realtime speech-to-speech AI (no Android TTS)
- Egyptian Arabic personality prompt
- Cute/warm/playful voice direction using the `marin` Realtime voice
- Microphone listens continuously while AI is connected
- Robot eyes animate while AI is speaking
- Hidden happiness / curiosity / boredom values influence the AI personality
- If vision notices the owner staying focused for a long time, the robot can ask a short cute Egyptian-Arabic question (20-minute cooldown)
- Gentle Roam fixed: it is no longer blocked simply because the camera sees a face
- Roam movements are more visible while remaining low-speed and short
- Faster companion turning retained

## First-time AI setup on the phone

1. Long-press the robot face to open hidden controls.
2. In **AI voice & personality**, paste the OpenAI API key you created.
3. Tap **Save key**. The key is encrypted using Android Keystore and is not built into the APK.
4. Tap **Connect AI**.
5. Allow microphone permission if Android asks.
6. Wait for **AI READY 🎙️**.
7. Talk normally in Arabic.
8. Use **Test cute Arabic voice** if you want the robot to say a quick hello.

## Important security note

This is a private hobby/prototype build. It connects from the Android device using a standard API key entered at runtime. For a public/production app, replace this with a small secure backend that mints short-lived Realtime credentials and use WebRTC.

## Safe roaming

The robot still has no real obstacle-distance sensor. Use Roam only on a clear floor and at low speed. Autonomous motion uses short pulses and auto-stop.

## Build

Upload the contents of this folder to the existing GitHub repository. GitHub Actions will build `MiRobotAI-v0.8.1-debug-apk`.
