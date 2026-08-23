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
import android.net.Uri;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.concurrent.TimeUnit;
import java.io.IOException;

import okhttp3.Call;
import okhttp3.Callback;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

/**
 * Multi-provider realtime speech controller.
 *
 * Supported natively:
 *  - Gemini Live (Google API keys)
 *  - OpenAI Realtime (OpenAI API keys)
 *  - Custom OpenAI-compatible Realtime WebSocket endpoints
 *
 * A completely arbitrary API cannot share one wire protocol; providers that use
 * another realtime schema need a small adapter implementing that schema.
 */
public class RealtimeVoiceController {
    public enum Provider {
        GEMINI("Gemini Live", "gemini", "gemini-3.1-flash-live-preview", ""),
        OPENAI("OpenAI Realtime", "openai", "gpt-realtime-2.1-mini", "wss://api.openai.com/v1/realtime"),
        CUSTOM_OPENAI("Custom OpenAI-compatible", "custom", "", "");

        public final String label;
        public final String slot;
        public final String defaultModel;
        public final String defaultEndpoint;

        Provider(String label, String slot, String defaultModel, String defaultEndpoint) {
            this.label = label;
            this.slot = slot;
            this.defaultModel = defaultModel;
            this.defaultEndpoint = defaultEndpoint;
        }

        @Override public String toString() { return label; }
    }

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

    private static final int OPENAI_RATE = 24_000;
    private static final int GEMINI_INPUT_RATE = 16_000;
    private static final int OUTPUT_RATE = 24_000;

    private final Context context;
    private final Listener listener;
    private final OkHttpClient client;

    private WebSocket socket;
    private volatile boolean connected = false;
    private volatile boolean sessionReady = false;
    private volatile boolean recording = false;
    private AudioRecord audioRecord;
    private AudioTrack audioTrack;
    private Thread recordThread;
    private final Object audioLock = new Object();
    private final StringBuilder transcript = new StringBuilder();
    private volatile boolean assistantSpeaking = false;
    private Provider provider = Provider.GEMINI;
    private String model = Provider.GEMINI.defaultModel;
    private String customEndpoint = "";
    private String lastInstructions = "";
    private boolean localSpeech = false;
    private long lastLoudMs = 0L;

    public RealtimeVoiceController(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
        client = new OkHttpClient.Builder()
                .pingInterval(15, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .build();
    }

    public boolean isConnected() { return sessionReady; }
    public Provider getProvider() { return provider; }

    public void connect(Provider provider, String apiKey, String endpoint, String model, String instructions) {
        disconnect();
        sessionReady = false;
        this.provider = provider == null ? Provider.GEMINI : provider;
        this.model = (model == null || model.trim().isEmpty()) ? this.provider.defaultModel : model.trim();
        this.customEndpoint = endpoint == null ? "" : endpoint.trim();
        this.lastInstructions = instructions == null ? "" : instructions;

        if (apiKey == null || apiKey.trim().isEmpty()) {
            status("No API key saved for " + this.provider.label);
            return;
        }

        status(this.provider.label + " connecting…");
        Request request;
        try {
            request = buildRequest(apiKey.trim());
        } catch (Exception e) {
            status("AI config error: " + e.getMessage());
            return;
        }

        socket = client.newWebSocket(request, new WebSocketListener() {
            @Override public void onOpen(WebSocket webSocket, Response response) {
                connected = true;
                sessionReady = false;
                if (RealtimeVoiceController.this.provider == Provider.GEMINI) {
                    configureGeminiSession(lastInstructions);
                    // Gemini signals setupComplete before we show READY/start the mic.
                    status("Gemini connected — setting up audio…");
                } else {
                    configureOpenAiSession(lastInstructions);
                    status("OpenAI socket connected — validating session…");
                }
            }

            @Override public void onMessage(WebSocket webSocket, String text) {
                if (RealtimeVoiceController.this.provider == Provider.GEMINI) handleGeminiEvent(text);
                else handleOpenAiEvent(text);
            }

            @Override public void onClosing(WebSocket webSocket, int code, String reason) {
                webSocket.close(code, reason);
            }

            @Override public void onClosed(WebSocket webSocket, int code, String reason) {
                connected = false;
                sessionReady = false;
                stopMicrophone();
                stopPlayback();
                status("AI disconnected (" + code + ")" + (reason == null || reason.isEmpty() ? "" : ": " + reason));
                if (listener != null) listener.onAiDisconnected();
            }

            @Override public void onFailure(WebSocket webSocket, Throwable t, Response response) {
                connected = false;
                sessionReady = false;
                stopMicrophone();
                stopPlayback();
                String msg = t == null ? "unknown error" : t.getMessage();
                if (response != null) {
                    String detail = "";
                    try {
                        if (response.body() != null) detail = response.body().string();
                    } catch (Exception ignored) { }
                    if (detail.length() > 220) detail = detail.substring(0, 220);
                    msg = "HTTP " + response.code() + (detail.isEmpty() ? " — " + msg : " — " + detail);
                }
                status("AI connection error: " + msg);
                if (listener != null) listener.onAiDisconnected();
            }
        });
    }

    private Request buildRequest(String apiKey) {
        if (provider == Provider.GEMINI) {
            String url = "wss://generativelanguage.googleapis.com/ws/" +
                    "google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent?key=" + Uri.encode(apiKey);
            return new Request.Builder().url(url).build();
        }

        String endpoint = customEndpoint;
        if (endpoint.isEmpty()) endpoint = provider.defaultEndpoint;
        if (endpoint.isEmpty()) throw new IllegalArgumentException("Enter a WebSocket endpoint");

        // Convenience: if the endpoint does not already contain a model query, append it.
        if (!model.isEmpty() && !endpoint.contains("model=")) {
            endpoint += (endpoint.contains("?") ? "&" : "?") + "model=" + Uri.encode(model);
        }
        return new Request.Builder()
                .url(endpoint)
                .addHeader("Authorization", "Bearer " + apiKey)
                .build();
    }

    /** Validates the saved key using a simple HTTPS request before opening realtime audio. */
    public void testApiKey(Provider provider, String apiKey) {
        if (provider == null) provider = Provider.GEMINI;
        if (apiKey == null || apiKey.trim().isEmpty()) {
            status("No API key saved for " + provider.label);
            return;
        }
        if (provider == Provider.CUSTOM_OPENAI) {
            status("Custom APIs cannot be key-tested universally. Use Connect AI to test the endpoint.");
            return;
        }
        Request req;
        if (provider == Provider.GEMINI) {
            String url = "https://generativelanguage.googleapis.com/v1beta/models?key=" + Uri.encode(apiKey.trim());
            req = new Request.Builder().url(url).get().build();
        } else {
            req = new Request.Builder()
                    .url("https://api.openai.com/v1/models")
                    .addHeader("Authorization", "Bearer " + apiKey.trim())
                    .get().build();
        }
        status("Testing " + provider.label + " key…");
        final Provider p = provider;
        client.newCall(req).enqueue(new Callback() {
            @Override public void onFailure(Call call, IOException e) {
                status("KEY TEST FAILED — network: " + (e.getMessage() == null ? "unknown" : e.getMessage()));
            }
            @Override public void onResponse(Call call, Response response) {
                try (Response r = response) {
                    String body = r.body() == null ? "" : r.body().string();
                    if (r.isSuccessful()) {
                        status("KEY OK ✅ — " + p.label + ". Now tap Connect AI.");
                    } else {
                        String detail = extractApiError(body);
                        status("KEY FAILED ❌ — HTTP " + r.code() + (detail.isEmpty() ? "" : ": " + detail));
                    }
                } catch (Exception e) {
                    status("KEY TEST FAILED — " + (e.getMessage() == null ? "response error" : e.getMessage()));
                }
            }
        });
    }

    private static String extractApiError(String body) {
        if (body == null || body.trim().isEmpty()) return "";
        try {
            JSONObject root = new JSONObject(body);
            JSONObject err = root.optJSONObject("error");
            String msg = err == null ? root.optString("message", "") : err.optString("message", "");
            if (msg.length() > 220) msg = msg.substring(0, 220);
            return msg;
        } catch (Exception ignored) {
            String t = body.replace('\n', ' ').replace('\r', ' ').trim();
            return t.length() > 220 ? t.substring(0, 220) : t;
        }
    }

    public void updatePersona(String instructions) {
        lastInstructions = instructions == null ? "" : instructions;
        if (!connected || socket == null) return;
        if (provider == Provider.GEMINI) {
            // Gemini systemInstruction is fixed at setup. Avoid sending a fake user message
            // every time hidden meters change; the next connection uses the new state.
            return;
        }
        configureOpenAiSession(lastInstructions);
    }

    /** Makes the robot speak a short AI-generated line without needing user speech. */
    public void speakPrompt(String prompt) {
        if (!connected || socket == null || prompt == null || prompt.trim().isEmpty()) return;
        if (provider == Provider.GEMINI) {
            try {
                JSONObject input = new JSONObject();
                input.put("text", prompt.trim());
                JSONObject event = new JSONObject();
                event.put("realtimeInput", input);
                socket.send(event.toString());
            } catch (JSONException ignored) { }
            return;
        }

        try {
            JSONObject content = new JSONObject();
            content.put("type", "input_text");
            content.put("text", prompt);
            JSONArray contentArray = new JSONArray();
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
        sessionReady = false;
        stopMicrophone();
        stopPlayback();
        WebSocket ws = socket;
        socket = null;
        if (ws != null) {
            try { ws.close(1000, "user disconnect"); } catch (Exception ignored) { }
        }
    }

    private void configureOpenAiSession(String instructions) {
        if (socket == null) return;
        try {
            JSONObject inputFormat = new JSONObject();
            inputFormat.put("type", "audio/pcm");
            inputFormat.put("rate", OPENAI_RATE);

            JSONObject vad = new JSONObject();
            vad.put("type", "semantic_vad");

            JSONObject input = new JSONObject();
            input.put("format", inputFormat);
            input.put("turn_detection", vad);

            JSONObject outputFormat = new JSONObject();
            outputFormat.put("type", "audio/pcm");

            JSONObject output = new JSONObject();
            output.put("format", outputFormat);
            output.put("voice", "marin");

            JSONObject audio = new JSONObject();
            audio.put("input", input);
            audio.put("output", output);

            JSONArray modalities = new JSONArray();
            modalities.put("audio");

            JSONObject session = new JSONObject();
            session.put("type", "realtime");
            if (!model.isEmpty()) session.put("model", model);
            session.put("output_modalities", modalities);
            session.put("audio", audio);
            session.put("instructions", instructions == null ? "" : instructions);
            session.put("max_output_tokens", 220);

            JSONObject event = new JSONObject();
            event.put("type", "session.update");
            event.put("session", session);
            socket.send(event.toString());
        } catch (JSONException e) {
            status("AI session config error");
        }
    }

    private void configureGeminiSession(String instructions) {
        if (socket == null) return;
        try {
            JSONObject setup = new JSONObject();
            setup.put("model", "models/" + model);
            JSONArray modalities = new JSONArray();
            modalities.put("AUDIO");
            setup.put("responseModalities", modalities);

            JSONObject systemInstruction = new JSONObject();
            JSONArray parts = new JSONArray();
            JSONObject part = new JSONObject();
            part.put("text", instructions == null ? "" : instructions);
            parts.put(part);
            systemInstruction.put("parts", parts);
            setup.put("systemInstruction", systemInstruction);

            JSONObject prebuilt = new JSONObject();
            prebuilt.put("voiceName", "Puck");
            JSONObject voiceConfig = new JSONObject();
            voiceConfig.put("prebuiltVoiceConfig", prebuilt);
            JSONObject speechConfig = new JSONObject();
            speechConfig.put("voiceConfig", voiceConfig);
            setup.put("speechConfig", speechConfig);

            JSONObject root = new JSONObject();
            root.put("setup", setup);
            socket.send(root.toString());
        } catch (JSONException e) {
            status("Gemini setup error");
        }
    }

    @SuppressLint("MissingPermission")
    private void startMicrophone() {
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            status("Microphone permission needed");
            return;
        }
        stopMicrophone();
        final int sampleRate = provider == Provider.GEMINI ? GEMINI_INPUT_RATE : OPENAI_RATE;
        int min = AudioRecord.getMinBufferSize(sampleRate,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        if (min <= 0) min = sampleRate / 5;
        int bufferSize = Math.max(min * 2, sampleRate / 5);
        try {
            audioRecord = new AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferSize);
            if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
                status("Microphone could not start");
                return;
            }
            audioRecord.startRecording();
            recording = true;
            final int chunkSize = Math.max(3200, sampleRate / 5); // about 100ms PCM16-ish chunk
            recordThread = new Thread(() -> {
                byte[] buffer = new byte[chunkSize];
                while (recording && connected && audioRecord != null) {
                    int read;
                    try { read = audioRecord.read(buffer, 0, buffer.length); }
                    catch (Exception e) { break; }
                    if (read > 0 && socket != null) {
                        if (provider == Provider.GEMINI) updateLocalVad(buffer, read);
                        String b64 = Base64.encodeToString(buffer, 0, read, Base64.NO_WRAP);
                        if (provider == Provider.GEMINI) {
                            try {
                                JSONObject audio = new JSONObject();
                                audio.put("data", b64);
                                audio.put("mimeType", "audio/pcm;rate=" + sampleRate);
                                JSONObject realtimeInput = new JSONObject();
                                realtimeInput.put("audio", audio);
                                JSONObject event = new JSONObject();
                                event.put("realtimeInput", realtimeInput);
                                socket.send(event.toString());
                            } catch (JSONException ignored) { }
                        } else {
                            socket.send("{\"type\":\"input_audio_buffer.append\",\"audio\":\"" + b64 + "\"}");
                        }
                    }
                }
            }, "MiRobotAI-Mic");
            recordThread.start();
        } catch (Exception e) {
            status("Microphone error: " + e.getMessage());
        }
    }

    private void updateLocalVad(byte[] pcm, int length) {
        long sum = 0;
        int samples = 0;
        for (int i = 0; i + 1 < length; i += 2) {
            int lo = pcm[i] & 0xff;
            int hi = pcm[i + 1];
            short s = (short) ((hi << 8) | lo);
            sum += Math.abs((int) s);
            samples++;
        }
        int avg = samples == 0 ? 0 : (int) (sum / samples);
        long now = System.currentTimeMillis();
        if (avg > 900) {
            lastLoudMs = now;
            if (!localSpeech) {
                localSpeech = true;
                if (listener != null) listener.onUserSpeechStarted();
            }
        } else if (localSpeech && now - lastLoudMs > 700L) {
            localSpeech = false;
            if (listener != null) listener.onUserSpeechStopped();
        }
    }

    private void stopMicrophone() {
        recording = false;
        localSpeech = false;
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
            int min = AudioTrack.getMinBufferSize(OUTPUT_RATE,
                    AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
            int size = Math.max(min * 4, 9600);
            audioTrack = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build())
                    .setAudioFormat(new AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(OUTPUT_RATE)
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
                try { audioTrack.pause(); audioTrack.flush(); audioTrack.play(); }
                catch (Exception ignored) { }
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

    private void handleOpenAiEvent(String text) {
        try {
            JSONObject event = new JSONObject(text);
            String type = event.optString("type", "");
            switch (type) {
                case "session.created":
                    status("OpenAI session created — applying robot voice…");
                    break;
                case "session.updated":
                    if (!sessionReady) {
                        sessionReady = true;
                        startMicrophone();
                        status(provider.label + " READY 🎙️");
                        if (listener != null) listener.onAiConnected();
                    }
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
                case "response.done":
                    finishAssistantAudio();
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

    private void handleGeminiEvent(String text) {
        try {
            JSONObject event = new JSONObject(text);
            if (event.has("setupComplete")) {
                sessionReady = true;
                status("Gemini Live READY 🎙️");
                startMicrophone();
                if (listener != null) listener.onAiConnected();
                return;
            }
            if (event.has("error")) {
                JSONObject err = event.optJSONObject("error");
                status("Gemini error: " + (err == null ? text : err.optString("message", "unknown")));
                return;
            }
            JSONObject server = event.optJSONObject("serverContent");
            if (server == null) return;

            if (server.optBoolean("interrupted", false)) {
                flushPlayback();
                finishAssistantAudio();
            }

            JSONObject outTx = server.optJSONObject("outputTranscription");
            if (outTx != null) {
                String t = outTx.optString("text", "");
                if (!t.isEmpty()) transcript.append(t);
            }

            JSONObject modelTurn = server.optJSONObject("modelTurn");
            if (modelTurn != null) {
                JSONArray parts = modelTurn.optJSONArray("parts");
                if (parts != null) {
                    for (int i = 0; i < parts.length(); i++) {
                        JSONObject part = parts.optJSONObject(i);
                        if (part == null) continue;
                        JSONObject inline = part.optJSONObject("inlineData");
                        if (inline != null) {
                            String data = inline.optString("data", "");
                            if (!data.isEmpty()) {
                                if (!assistantSpeaking) {
                                    assistantSpeaking = true;
                                    if (listener != null) listener.onAssistantAudioStarted();
                                }
                                playChunk(Base64.decode(data, Base64.DEFAULT));
                            }
                        }
                        String textPart = part.optString("text", "");
                        if (!textPart.isEmpty()) transcript.append(textPart);
                    }
                }
            }

            if (server.optBoolean("turnComplete", false) || server.optBoolean("generationComplete", false)) {
                if (listener != null && transcript.length() > 0) listener.onAssistantTranscript(transcript.toString());
                finishAssistantAudio();
            }
        } catch (Exception e) {
            status("Gemini event error: " + e.getMessage());
        }
    }

    private void finishAssistantAudio() {
        if (assistantSpeaking && listener != null) listener.onAssistantAudioDone();
        assistantSpeaking = false;
        transcript.setLength(0);
    }

    private void status(String message) {
        if (listener != null) listener.onAiStatus(message);
    }
}
