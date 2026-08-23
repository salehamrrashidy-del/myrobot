package com.example.mirobotai;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.util.Base64;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

/**
 * Prototype speech-to-speech Realtime client.
 *
 * The standard API key is entered at runtime and stored using Android Keystore;
 * it is NOT built into the APK. For a production app, replace this with a small
 * backend that mints ephemeral Realtime credentials and use WebRTC.
 */
public class RealtimeVoiceController {
    public interface Listener {
        void onAiStatus(String message);
        void onAiConnected();
        void onAiDisconnected();
        void onUserSpeechStarted();
        void onUserSpeechStopped();
        void onAssistantAudioStarted();
        void onAssistantAudioDone();
        void onAssistantTranscript(String transcript);
    }

    private static final int SAMPLE_RATE = 24_000;
    private static final String MODEL = "gpt-realtime-2.1-mini";
    private static final String VOICE = "marin";

    private final Context context;
    private final Listener listener;
    private final OkHttpClient client;

    private WebSocket socket;
    private volatile boolean connected = false;
    private volatile boolean recording = false;
    private AudioRecord audioRecord;
    private AudioTrack audioTrack;
    private Thread recordThread;
    private final Object audioLock = new Object();
    private final StringBuilder transcript = new StringBuilder();
    private volatile boolean assistantSpeaking = false;

    public RealtimeVoiceController(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
        client = new OkHttpClient.Builder()
                .pingInterval(15, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .build();
    }

    public boolean isConnected() {
        return connected;
    }

    public void connect(String apiKey, String instructions) {
        disconnect();
        if (apiKey == null || apiKey.trim().isEmpty()) {
            status("No API key saved");
            return;
        }
        status("AI connecting…");
        Request request = new Request.Builder()
                .url("wss://api.openai.com/v1/realtime?model=" + MODEL)
                .addHeader("Authorization", "Bearer " + apiKey.trim())
                .build();
        socket = client.newWebSocket(request, new WebSocketListener() {
            @Override public void onOpen(WebSocket webSocket, Response response) {
                connected = true;
                configureSession(instructions);
                startMicrophone();
                status("AI READY 🎙️");
                if (listener != null) listener.onAiConnected();
            }

            @Override public void onMessage(WebSocket webSocket, String text) {
                handleEvent(text);
            }

            @Override public void onClosing(WebSocket webSocket, int code, String reason) {
                webSocket.close(code, reason);
            }

            @Override public void onClosed(WebSocket webSocket, int code, String reason) {
                connected = false;
                stopMicrophone();
                stopPlayback();
                status("AI disconnected");
                if (listener != null) listener.onAiDisconnected();
            }

            @Override public void onFailure(WebSocket webSocket, Throwable t, Response response) {
                connected = false;
                stopMicrophone();
                stopPlayback();
                String msg = t == null ? "unknown error" : t.getMessage();
                status("AI error: " + msg);
                if (listener != null) listener.onAiDisconnected();
            }
        });
    }

    public void updatePersona(String instructions) {
        if (!connected || socket == null) return;
        try {
            JSONObject session = baseSession(instructions);
            JSONObject event = new JSONObject();
            event.put("type", "session.update");
            event.put("session", session);
            socket.send(event.toString());
        } catch (JSONException ignored) { }
    }

    /** Makes the robot speak a short AI-generated line without needing user speech. */
    public void speakPrompt(String prompt) {
        if (!connected || socket == null || prompt == null || prompt.trim().isEmpty()) return;
        try {
            JSONObject content = new JSONObject();
            content.put("type", "input_text");
            content.put("text", prompt);

            org.json.JSONArray contentArray = new org.json.JSONArray();
            contentArray.put(content);

            JSONObject item = new JSONObject();
            item.put("type", "message");
            item.put("role", "user");
            item.put("content", contentArray);

            JSONObject createItem = new JSONObject();
            createItem.put("type", "conversation.item.create");
            createItem.put("item", item);
            socket.send(createItem.toString());

            JSONObject response = new JSONObject();
            response.put("type", "response.create");
            socket.send(response.toString());
        } catch (JSONException ignored) { }
    }

    public void disconnect() {
        connected = false;
        stopMicrophone();
        stopPlayback();
        WebSocket ws = socket;
        socket = null;
        if (ws != null) {
            try { ws.close(1000, "user disconnect"); } catch (Exception ignored) { }
        }
    }

    private JSONObject baseSession(String instructions) throws JSONException {
        JSONObject inputFormat = new JSONObject();
        inputFormat.put("type", "audio/pcm");
        inputFormat.put("rate", SAMPLE_RATE);

        JSONObject vad = new JSONObject();
        vad.put("type", "semantic_vad");

        JSONObject input = new JSONObject();
        input.put("format", inputFormat);
        input.put("turn_detection", vad);

        JSONObject outputFormat = new JSONObject();
        outputFormat.put("type", "audio/pcm");

        JSONObject output = new JSONObject();
        output.put("format", outputFormat);
        output.put("voice", VOICE);

        JSONObject audio = new JSONObject();
        audio.put("input", input);
        audio.put("output", output);

        org.json.JSONArray modalities = new org.json.JSONArray();
        modalities.put("audio");

        JSONObject session = new JSONObject();
        session.put("type", "realtime");
        session.put("model", MODEL);
        session.put("output_modalities", modalities);
        session.put("audio", audio);
        session.put("instructions", instructions == null ? "" : instructions);
        session.put("max_output_tokens", 220);
        return session;
    }

    private void configureSession(String instructions) {
        if (socket == null) return;
        try {
            JSONObject event = new JSONObject();
            event.put("type", "session.update");
            event.put("session", baseSession(instructions));
            socket.send(event.toString());
        } catch (JSONException e) {
            status("AI session config error");
        }
    }

    @SuppressLint("MissingPermission")
    private void startMicrophone() {
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            status("Microphone permission needed");
            return;
        }
        stopMicrophone();
        int min = AudioRecord.getMinBufferSize(SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        if (min <= 0) min = SAMPLE_RATE / 5;
        int bufferSize = Math.max(min * 2, 4800);
        try {
            audioRecord = new AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferSize);
            if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
                status("Microphone could not start");
                return;
            }
            audioRecord.startRecording();
            recording = true;
            final int chunkSize = 4800; // ~100 ms at 24 kHz PCM16 mono
            recordThread = new Thread(() -> {
                byte[] buffer = new byte[chunkSize];
                while (recording && connected && audioRecord != null) {
                    int read;
                    try { read = audioRecord.read(buffer, 0, buffer.length); }
                    catch (Exception e) { break; }
                    if (read > 0 && socket != null) {
                        String b64 = Base64.encodeToString(buffer, 0, read, Base64.NO_WRAP);
                        socket.send("{\"type\":\"input_audio_buffer.append\",\"audio\":\"" + b64 + "\"}");
                    }
                }
            }, "MiRobotAI-Mic");
            recordThread.start();
        } catch (Exception e) {
            status("Microphone error: " + e.getMessage());
        }
    }

    private void stopMicrophone() {
        recording = false;
        AudioRecord record = audioRecord;
        audioRecord = null;
        if (record != null) {
            try { record.stop(); } catch (Exception ignored) { }
            try { record.release(); } catch (Exception ignored) { }
        }
        Thread t = recordThread;
        recordThread = null;
        if (t != null) t.interrupt();
    }

    private void ensureAudioTrack() {
        synchronized (audioLock) {
            if (audioTrack != null) return;
            int min = AudioTrack.getMinBufferSize(SAMPLE_RATE,
                    AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
            int size = Math.max(min * 4, 9600);
            audioTrack = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build())
                    .setAudioFormat(new AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(SAMPLE_RATE)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build())
                    .setBufferSizeInBytes(size)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build();
            audioTrack.play();
        }
    }

    private void playChunk(byte[] pcm) {
        if (pcm == null || pcm.length == 0) return;
        synchronized (audioLock) {
            ensureAudioTrack();
            if (audioTrack != null) {
                try { audioTrack.write(pcm, 0, pcm.length, AudioTrack.WRITE_BLOCKING); }
                catch (Exception ignored) { }
            }
        }
    }

    private void flushPlayback() {
        synchronized (audioLock) {
            if (audioTrack != null) {
                try {
                    audioTrack.pause();
                    audioTrack.flush();
                    audioTrack.play();
                } catch (Exception ignored) { }
            }
        }
    }

    private void stopPlayback() {
        synchronized (audioLock) {
            if (audioTrack != null) {
                try { audioTrack.stop(); } catch (Exception ignored) { }
                try { audioTrack.release(); } catch (Exception ignored) { }
                audioTrack = null;
            }
        }
    }

    private void handleEvent(String text) {
        try {
            JSONObject event = new JSONObject(text);
            String type = event.optString("type", "");
            switch (type) {
                case "session.updated":
                    status("AI READY 🎙️");
                    break;
                case "input_audio_buffer.speech_started":
                    flushPlayback();
                    if (listener != null) listener.onUserSpeechStarted();
                    break;
                case "input_audio_buffer.speech_stopped":
                    if (listener != null) listener.onUserSpeechStopped();
                    break;
                case "response.output_audio.delta":
                    String delta = event.optString("delta", "");
                    if (!delta.isEmpty()) {
                        if (!assistantSpeaking) {
                            assistantSpeaking = true;
                            if (listener != null) listener.onAssistantAudioStarted();
                        }
                        playChunk(Base64.decode(delta, Base64.DEFAULT));
                    }
                    break;
                case "response.output_audio_transcript.delta":
                    transcript.append(event.optString("delta", ""));
                    break;
                case "response.output_audio_transcript.done":
                    String doneText = event.optString("transcript", transcript.toString());
                    if (listener != null && !doneText.isEmpty()) listener.onAssistantTranscript(doneText);
                    break;
                case "response.output_audio.done":
                    if (assistantSpeaking && listener != null) listener.onAssistantAudioDone();
                    assistantSpeaking = false;
                    transcript.setLength(0);
                    break;
                case "response.done":
                    if (assistantSpeaking && listener != null) listener.onAssistantAudioDone();
                    assistantSpeaking = false;
                    transcript.setLength(0);
                    break;
                case "error":
                    JSONObject err = event.optJSONObject("error");
                    status("AI error: " + (err == null ? text : err.optString("message", "unknown")));
                    break;
                default:
                    break;
            }
        } catch (Exception e) {
            status("AI event error");
        }
    }

    private void status(String message) {
        if (listener != null) listener.onAiStatus(message);
    }
}
