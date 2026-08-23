# MiRobotAI v0.9.9 — saved API key + softer voice

This build focuses on daily-use comfort.

## API key persistence
- API keys are encrypted with Android Keystore and saved per provider on the phone.
- After you paste a key once, tap **SAVE / CHANGE KEY**. On later app launches, leave the API key box empty and just tap **Connect AI**.
- The provider, model, endpoint, and Gemini voice choice are saved too.
- A fixed **development-only signing key** is included for this personal side-loaded project so future GitHub APKs from this project can install as updates and keep app data.
- Important: Android still deletes app data if you manually uninstall the app or clear its storage. Do not uninstall between normal updates once v0.9.9 is installed.

## Softer voice
Gemini Live now has a saved voice selector with these curated options:
- **Achird — Friendly** (default)
- Vindemiatrix — Gentle
- Sulafat — Warm
- Achernar — Soft
- Leda — Youthful
- Puck — Upbeat

The robot personality prompt was also softened: natural medium pace, no breathy whisper, no exaggerated high pitch, and less theatrical emotion.

## Gemini audio stability retained from v0.9.8
- Uses `gemini-3.1-flash-live-preview`.
- Waits for `setupComplete` before starting microphone upload.
- Uses `NO_INTERRUPTION` and pauses mic upload while the robot speaks to reduce self-echo interruptions.
- Uses queued PCM playback to reduce gaps between audio chunks.

## Build
Upload this folder to the same GitHub repository and run the existing **Build Android APK** action.
The artifact name is `MiRobotAI-v0.9.9-debug-apk`.

> The bundled signing key is for this personal development build only. Replace it before publishing a production app.
