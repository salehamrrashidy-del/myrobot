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
import android.media.audiofx.AcousticEchoCanceler;
import android.media.audiofx.NoiseSuppressor;
import android.net.Uri;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.io.IOException;

import okhttp3.Call;
import okhttp3.Callback;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

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
        GEMINI("Gemini 3.1 Flash Live", "gemini", "gemini-3.1-flash-live-preview", ""),
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
        /** Gemini Live function call that should be executed by the robot layer. */
        void onRobotToolCall(String id, String name, JSONObject args);
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
    private AcousticEchoCanceler acousticEchoCanceler;
    private NoiseSuppressor noiseSuppressor;
    private volatile boolean echoCancellationActive = false;
    private Thread recordThread;
    private final Object audioLock = new Object();
    private final BlockingQueue<byte[]> playbackQueue = new LinkedBlockingQueue<>();
    private final AtomicLong queuedPlaybackBytes = new AtomicLong(0L);
    private volatile boolean playbackWorkerRunning = false;
    private Thread playbackThread;
    private volatile long suppressMicUntilMs = 0L;
    private volatile boolean geminiInputPausedForAssistant = false;
    private final StringBuilder transcript = new StringBuilder();
    private volatile boolean assistantSpeaking = false;
    private volatile boolean externalSpeechActive = false;
    private Provider provider = Provider.GEMINI;
    private String model = Provider.GEMINI.defaultModel;
    private String customEndpoint = "";
    private String voiceName = "Achird";
    private String lastInstructions = "";
    private boolean localSpeech = false;
    private long lastLoudMs = 0L;
    private int geminiRetryCount = 0;
    private boolean manualDisconnect = false;
    private String reconnectApiKey = "";
    private final android.os.Handler retryHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable geminiSetupTimeout;

    public RealtimeVoiceController(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
        this.geminiSetupTimeout = () -> {
            if (provider == Provider.GEMINI && connected && !sessionReady) {
                status("Gemini setup timed out after 30s ❌ — no setupComplete received");
                WebSocket ws = socket;
                socket = null;
                connected = false;
                if (ws != null) { try { ws.close(1000, "setup timeout"); } catch (Exception ignored) {} }
                if (this.listener != null) this.listener.onAiDisconnected();
            }
        };
        client = new OkHttpClient.Builder()
                .pingInterval(15, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .build();
    }

    public boolean isConnected() { return sessionReady; }
    public Provider getProvider() { return provider; }

    /** Used when Robotics ER text output is spoken by the app's local TTS. */
    public void setExternalSpeechActive(boolean active) {
        externalSpeechActive = active;
        if (!active) suppressMicUntilMs = Math.max(suppressMicUntilMs, System.currentTimeMillis() + 700L);
    }

    public void connect(Provider provider, String apiKey, String endpoint, String model, String voiceName, String instructions) {
        manualDisconnect = true;
        disconnect();
        manualDisconnect = false;
        geminiRetryCount = 0;
        sessionReady = false;
        this.provider = provider == null ? Provider.GEMINI : provider;
        this.model = (model == null || model.trim().isEmpty()) ? this.provider.defaultModel : model.trim();
        if (this.provider == Provider.GEMINI && !isGeminiLiveModel(this.model)) {
            status("Gemini voice needs a Live model. Use: " + Provider.GEMINI.defaultModel);
            return;
        }
        this.customEndpoint = endpoint == null ? "" : endpoint.trim();
        this.voiceName = (voiceName == null || voiceName.trim().isEmpty()) ? "Achird" : voiceName.trim();
        this.lastInstructions = instructions == null ? "" : instructions;
        this.reconnectApiKey = apiKey == null ? "" : apiKey.trim();

        if (apiKey == null || apiKey.trim().isEmpty()) {
            status("No API key saved for " + this.provider.label);
            return;
        }

        status(this.provider.label + " connecting…");

        // Gemini Live raw-WebSocket authentication, exactly as documented by Google:
        // ...BidiGenerateContent?key=YOUR_API_KEY
        //
        // Do NOT add a second auth header. Sending only the query parameter avoids
        // duplicate/mismatched credential handling in the WebSocket upgrade.
        Request request;
        try {
            request = buildRequest(apiKey.trim());
        } catch (Exception e) {
            status("AI config error: " + e.getMessage());
            return;
        }

        openSocket(request);
    }


    private void openSocket(Request request) {
        socket = client.newWebSocket(request, new WebSocketListener() {
            @Override public void onOpen(WebSocket webSocket, Response response) {
                connected = true;
                sessionReady = false;
                if (RealtimeVoiceController.this.provider == Provider.GEMINI) {
                    configureGeminiSession(lastInstructions);
                    // Gemini signals setupComplete before we show READY/start the mic.
                    status((isRoboticsMode() ? "Gemini Robotics ER 2" : "Gemini") + " connected — waiting for setupComplete (max 30s)…");
                    retryHandler.removeCallbacks(geminiSetupTimeout);
                    retryHandler.postDelayed(geminiSetupTimeout, 30_000L);
                } else {
                    configureOpenAiSession(lastInstructions);
                    status("OpenAI socket connected — validating session…");
                }
            }

            @Override public void onMessage(WebSocket webSocket, String text) {
                if (RealtimeVoiceController.this.provider == Provider.GEMINI) handleGeminiEvent(text);
                else handleOpenAiEvent(text);
            }

            @Override public void onMessage(WebSocket webSocket, ByteString bytes) {
                String text = bytes == null ? "" : bytes.utf8();
                if (RealtimeVoiceController.this.provider == Provider.GEMINI) handleGeminiEvent(text);
                else handleOpenAiEvent(text);
            }

            @Override public void onClosing(WebSocket webSocket, int code, String reason) {
                webSocket.close(code, reason);
            }

            @Override public void onClosed(WebSocket webSocket, int code, String reason) {
                if (webSocket != socket) return;
                connected = false;
                sessionReady = false;
                stopMicrophone();
                stopPlayback();
                if (code == 1008 && RealtimeVoiceController.this.provider == Provider.GEMINI) {
                    String r = reason == null ? "" : reason;
                    if (r.toLowerCase().contains("authentication") || r.toLowerCase().contains("oauth")) {
                        String prefix = "";
                        if (reconnectApiKey != null) {
                            String k = reconnectApiKey.trim();
                            if (k.startsWith("AQ.")) prefix = " Auth-key type detected (AQ.).";
                            else if (k.startsWith("AIza")) prefix = " Standard-key type detected.";
                        }
                        status("Gemini Live disconnected (1008): API-key authentication rejected."
                                + prefix
                                + (r.isEmpty() ? "" : " Server: " + r));
                    } else {
                        status("Gemini Live disconnected (1008)"
                                + (r.isEmpty() ? "" : ": " + r)
                                + " — Live API rejected the session");
                    }
                } else {
                    status("AI disconnected (" + code + ")"
                            + (reason == null || reason.isEmpty() ? "" : ": " + reason));
                }
                if (listener != null) listener.onAiDisconnected();
            }

            @Override public void onFailure(WebSocket webSocket, Throwable t, Response response) {
                if (webSocket != socket) return;
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

    private void reconnectGemini() {
        if (manualDisconnect || reconnectApiKey == null || reconnectApiKey.isEmpty()) return;
        Request request;
        try {
            request = buildRequest(reconnectApiKey.trim());
        } catch (Exception e) {
            status("Gemini retry config error: " + e.getMessage());
            return;
        }
        openSocket(request);
    }

    private static boolean isGeminiLiveModel(String model) {
        if (model == null) return false;
        String m = model.trim().toLowerCase();
        return m.contains("live")
                || m.contains("native-audio")
                || m.contains("gemini-robotics-er-2-streaming");
    }

    public boolean isRoboticsMode() {
        String m = model == null ? "" : model.trim().toLowerCase();
        return provider == Provider.GEMINI && m.contains("gemini-robotics-er-2-streaming");
    }


    /**
     * Provisions a short-lived token for the Gemini Live API, then uses that
     * token to authenticate the WebSocket. This is the correct client-to-server
     * Live API authentication flow for an Android/mobile client.
     */
    private void requestEphemeralLiveTokenAndOpen(String apiKey) {
        final String cleanKey = apiKey == null ? "" : apiKey.trim();
        if (cleanKey.isEmpty()) {
            status("Gemini API key is empty");
            return;
        }

        status("Gemini Live: creating secure session token…");

        JSONObject bodyJson = new JSONObject();
        try {
            bodyJson.put("uses", 1);

            // Lock the temporary credential to this Live model and AUDIO output.
            // The rest of the session setup (system instructions, tools, VAD, etc.)
            // is still sent in configureGeminiSession().
            JSONObject constraints = new JSONObject();
            constraints.put("model", "models/" + model);

            JSONObject constraintsConfig = new JSONObject();
            JSONArray responseModalities = new JSONArray();
            responseModalities.put("AUDIO");
            constraintsConfig.put("responseModalities", responseModalities);

            constraints.put("config", constraintsConfig);
            bodyJson.put("liveConnectConstraints", constraints);
        } catch (JSONException e) {
            status("Gemini Live token config error: " + e.getMessage());
            if (listener != null) listener.onAiDisconnected();
            return;
        }

        okhttp3.RequestBody body = okhttp3.RequestBody.create(
                bodyJson.toString(),
                okhttp3.MediaType.parse("application/json; charset=utf-8"));

        Request tokenRequest = new Request.Builder()
                .url("https://generativelanguage.googleapis.com/v1beta/auth_tokens")
                .addHeader("x-goog-api-key", cleanKey)
                .addHeader("Content-Type", "application/json")
                .post(body)
                .build();

        client.newCall(tokenRequest).enqueue(new Callback() {
            @Override public void onFailure(Call call, IOException e) {
                status("Gemini Live token error: "
                        + (e == null || e.getMessage() == null ? "network error" : e.getMessage()));
                if (listener != null) listener.onAiDisconnected();
            }

            @Override public void onResponse(Call call, Response response) throws IOException {
                String responseText = "";
                try { if (response.body() != null) responseText = response.body().string(); }
                catch (Exception ignored) { }

                if (!response.isSuccessful()) {
                    String detail = responseText == null ? "" : responseText.trim();
                    if (detail.length() > 260) detail = detail.substring(0, 260);
                    status("Gemini Live token rejected: HTTP " + response.code()
                            + (detail.isEmpty() ? "" : " — " + detail));
                    if (listener != null) listener.onAiDisconnected();
                    response.close();
                    return;
                }

                String tokenName = "";
                try {
                    JSONObject tokenJson = new JSONObject(responseText);
                    tokenName = tokenJson.optString("name", "").trim();
                } catch (Exception ignored) { }
                response.close();

                if (tokenName.isEmpty()) {
                    status("Gemini Live token error: server returned no token name");
                    if (listener != null) listener.onAiDisconnected();
                    return;
                }

                try {
                    Request request = buildGeminiLiveRequestWithEphemeralToken(tokenName);
                    status("Gemini Live token ready — opening secure socket…");
                    openSocket(request);
                } catch (Exception e) {
                    status("Gemini Live socket config error: " + e.getMessage());
                    if (listener != null) listener.onAiDisconnected();
                }
            }
        });
    }

    private Request buildGeminiLiveRequestWithEphemeralToken(String tokenName) {
        String cleanToken = tokenName == null ? "" : tokenName.trim();
        if (cleanToken.isEmpty()) throw new IllegalArgumentException("Live token is empty");

        String url = "wss://generativelanguage.googleapis.com/ws/"
                + "google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContentConstrained"
                + "?access_token=" + Uri.encode(cleanToken);

        return new Request.Builder()
                .url(url)
                .addHeader("x-goog-api-client", "mirobotai-android/1.8.1")
                .build();
    }

    private Request buildRequest(String apiKey) {
        if (provider == Provider.GEMINI) {
            String cleanKey = apiKey == null ? "" : apiKey.trim();
            if (cleanKey.isEmpty()) {
                throw new IllegalArgumentException("Gemini API key is empty");
            }

            String url = "wss://generativelanguage.googleapis.com/ws/"
                    + "google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"
                    + "?key=" + Uri.encode(cleanKey);

            return new Request.Builder()
                    .url(url)
                    .addHeader("x-goog-api-client", "mirobotai-android/1.8.2")
                    .build();
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
            String testModel = (model == null || model.trim().isEmpty()) ? Provider.GEMINI.defaultModel : model.trim();
            String cleanKey = apiKey.trim();
            String url = "https://generativelanguage.googleapis.com/v1beta/models/"
                    + Uri.encode(testModel);
            req = new Request.Builder()
                    .url(url)
                    .addHeader("x-goog-api-key", cleanKey)
                    .get()
                    .build();
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
                        String keyType = "";
                        if (provider == Provider.GEMINI) {
                            String k = apiKey == null ? "" : apiKey.trim();
                            if (k.startsWith("AQ.")) keyType = " (Auth key / AQ.)";
                            else if (k.startsWith("AIza")) keyType = " (Standard key)";
                        }
                        status("KEY + MODEL OK ✅" + keyType + " — " + p.label + ". Now tap Connect AI.");
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
        manualDisconnect = true;
        retryHandler.removeCallbacksAndMessages(null);
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

            // Gemini Robotics ER 2 Streaming officially returns TEXT through the Live API.
            // Other Gemini Live models may return AUDIO.
            boolean roboticsStreaming = isRoboticsMode();
            JSONArray modalities = new JSONArray();
            modalities.put(roboticsStreaming ? "TEXT" : "AUDIO");
            JSONObject generationConfig = new JSONObject();
            generationConfig.put("responseModalities", modalities);

            if (!roboticsStreaming) {
                // Native-audio Gemini Live models can use Google's prebuilt voices.
                JSONObject prebuiltVoice = new JSONObject();
                prebuiltVoice.put("voiceName", voiceName);
                JSONObject voiceConfig = new JSONObject();
                voiceConfig.put("prebuiltVoiceConfig", prebuiltVoice);
                JSONObject speechConfig = new JSONObject();
                speechConfig.put("voiceConfig", voiceConfig);
                generationConfig.put("speechConfig", speechConfig);
            }

            setup.put("generationConfig", generationConfig);

            // IMPORTANT FOR GEMINI ROBOTICS ER 2 STREAMING:
            // Keep the initial setup intentionally minimal and aligned with Google's
            // robotics streaming example. The robotics endpoint supports audio input
            // with default server-side activity detection, so do not send optional
            // realtimeInputConfig/VAD fields here. Some unsupported Live options can
            // cause WebSocket close code 1008 during setup.
            if (!roboticsStreaming) {
                // These VAD options are retained only for normal Gemini Live audio models.
                JSONObject automaticActivityDetection = new JSONObject();
                automaticActivityDetection.put("disabled", false);
                automaticActivityDetection.put("startOfSpeechSensitivity", "START_SENSITIVITY_LOW");
                automaticActivityDetection.put("endOfSpeechSensitivity", "END_SENSITIVITY_LOW");
                automaticActivityDetection.put("prefixPaddingMs", 80);
                automaticActivityDetection.put("silenceDurationMs", 450);

                JSONObject realtimeInputConfig = new JSONObject();
                realtimeInputConfig.put("automaticActivityDetection", automaticActivityDetection);
                realtimeInputConfig.put("activityHandling", "START_OF_ACTIVITY_INTERRUPTS");
                setup.put("realtimeInputConfig", realtimeInputConfig);
            }

            // Physical capabilities are exposed as BLOCKING tools, which is the
            // Robotics ER 2 streaming pattern. The app still owns motor values and safety.
            JSONArray declarations = new JSONArray();

            JSONObject moveProps = new JSONObject();
            moveProps.put("direction", enumStringSchema("forward", "backward", "left", "right", "stop"));
            declarations.put(functionDecl(
                    "robot_move",
                    "Move the physical robot a small bounded amount. forward/backward are short nudges; left/right are small in-place turns; stop stops immediately. " +
                            "Forward can be refused by Edge Guard. Backward is refused while Edge Guard is enabled because the rear edge is not visible.",
                    objectSchema(moveProps, "direction")));

            // Keep the Robotics ER 2 tool list simple and explicit. Older aliases
            // are only exposed to non-robotics Gemini Live models.
            if (!roboticsStreaming) {
                declarations.put(functionDecl(
                        "robot_stop",
                        "Immediately stop the physical robot.",
                        new JSONObject()));

                JSONObject turnProps = new JSONObject();
                turnProps.put("direction", enumStringSchema("left", "right"));
                declarations.put(functionDecl(
                        "robot_turn",
                        "Legacy small left/right turn tool. Prefer robot_move.",
                        objectSchema(turnProps, "direction")));

                JSONObject nudgeProps = new JSONObject();
                nudgeProps.put("direction", enumStringSchema("forward"));
                declarations.put(functionDecl(
                        "robot_nudge",
                        "Legacy short forward nudge tool. Prefer robot_move.",
                        objectSchema(nudgeProps, "direction")));
            }

            JSONObject enabledProps = new JSONObject();
            JSONObject boolSchema = new JSONObject();
            boolSchema.put("type", "BOOLEAN");
            enabledProps.put("enabled", boolSchema);
            declarations.put(functionDecl(
                    "set_roam_mode",
                    "Turn gentle autonomous roaming on or off.",
                    objectSchema(enabledProps, "enabled")));
            declarations.put(functionDecl(
                    "set_companion_mode",
                    "Turn person-facing companion mode on or off.",
                    objectSchema(enabledProps, "enabled")));

            JSONObject expressionProps = new JSONObject();
            expressionProps.put("expression", enumStringSchema("happy", "curious", "surprised", "sleepy", "upset", "normal"));
            declarations.put(functionDecl(
                    "set_expression",
                    "Change the robot eye expression briefly.",
                    objectSchema(expressionProps, "expression")));

            JSONObject tool = new JSONObject();
            tool.put("functionDeclarations", declarations);
            JSONArray tools = new JSONArray();
            tools.put(tool);
            setup.put("tools", tools);

            JSONObject systemInstruction = new JSONObject();
            JSONArray parts = new JSONArray();
            JSONObject part = new JSONObject();
            part.put("text", instructions == null ? "" : instructions);
            parts.put(part);
            systemInstruction.put("parts", parts);
            setup.put("systemInstruction", systemInstruction);

            JSONObject root = new JSONObject();
            root.put("setup", setup);
            boolean sent = socket.send(root.toString());
            status(sent
                    ? "Gemini socket open — setup sent, waiting for setupComplete…"
                    : "Gemini setup could not be sent ❌");
        } catch (JSONException e) {
            status("Gemini setup error: " + e.getMessage());
        }
    }

    private static JSONObject functionDecl(String name, String description, JSONObject parameters) throws JSONException {
        JSONObject d = new JSONObject();
        d.put("name", name);
        d.put("description", description);
        d.put("behavior", "BLOCKING");
        if (parameters != null && parameters.length() > 0) d.put("parameters", parameters);
        return d;
    }

    private static JSONObject enumStringSchema(String... values) throws JSONException {
        JSONObject schema = new JSONObject();
        schema.put("type", "STRING");
        JSONArray enums = new JSONArray();
        if (values != null) for (String v : values) enums.put(v);
        schema.put("enum", enums);
        return schema;
    }

    private static JSONObject objectSchema(JSONObject properties, String... required) throws JSONException {
        JSONObject schema = new JSONObject();
        schema.put("type", "OBJECT");
        schema.put("properties", properties == null ? new JSONObject() : properties);
        if (required != null && required.length > 0) {
            JSONArray req = new JSONArray();
            for (String r : required) req.put(r);
            schema.put("required", req);
        }
        return schema;
    }

    /** Send a result for a Gemini Live function call. */
    public void sendRobotToolResult(String id, String name, boolean ok, String message) {
        if (provider != Provider.GEMINI || socket == null || !connected) return;
        try {
            JSONObject response = new JSONObject();
            response.put("ok", ok);
            response.put("result", message == null ? (ok ? "ok" : "failed") : message);

            JSONObject fr = new JSONObject();
            if (id != null && !id.isEmpty()) fr.put("id", id);
            fr.put("name", name == null ? "unknown" : name);
            fr.put("response", response);

            JSONArray responses = new JSONArray();
            responses.put(fr);
            JSONObject toolResponse = new JSONObject();
            toolResponse.put("functionResponses", responses);
            JSONObject root = new JSONObject();
            root.put("toolResponse", toolResponse);
            socket.send(root.toString());
        } catch (JSONException e) {
            status("Tool response error");
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
                    MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                    sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferSize);
            if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
                status("Microphone could not start");
                return;
            }

            // Allow real user barge-in while the robot is speaking.
            // AEC helps remove the phone speaker from the microphone signal.
            try {
                int sessionId = audioRecord.getAudioSessionId();
                if (AcousticEchoCanceler.isAvailable()) {
                    acousticEchoCanceler = AcousticEchoCanceler.create(sessionId);
                    if (acousticEchoCanceler != null) {
                        acousticEchoCanceler.setEnabled(true);
                        echoCancellationActive = acousticEchoCanceler.getEnabled();
                    }
                }
                if (NoiseSuppressor.isAvailable()) {
                    noiseSuppressor = NoiseSuppressor.create(sessionId);
                    if (noiseSuppressor != null) noiseSuppressor.setEnabled(true);
                }
            } catch (Exception ignored) {
                echoCancellationActive = false;
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
                        long nowMs = System.currentTimeMillis();
                        // For Gemini 3.1 Flash Live we keep sending microphone audio
                        // while the assistant is speaking so the user can interrupt it.
                        // Only local TTS / a very short echo tail can temporarily mute input.
                        boolean suppressForSpeaker = provider == Provider.GEMINI
                                && (externalSpeechActive || nowMs < suppressMicUntilMs);

                        if (provider == Provider.GEMINI) {
                            geminiInputPausedForAssistant = false;
                            updateLocalVad(buffer, read);
                        }

                        if (suppressForSpeaker) {
                            continue;
                        }
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
        // While the assistant is speaking, require a stronger local signal.
        // AEC normally removes most speaker echo, so a strong remaining signal
        // is much more likely to be the user talking over the robot.
        int speechThreshold = assistantSpeaking
                ? (echoCancellationActive ? 1500 : 2600)
                : 900;

        if (avg > speechThreshold) {
            lastLoudMs = now;
            if (!localSpeech) {
                localSpeech = true;

                // Immediate local barge-in: stop whatever audio is still queued.
                // Gemini also receives the microphone audio and will send an
                // `interrupted` server event for the current response.
                if (assistantSpeaking || queuedPlaybackBytes.get() > 0L) {
                    flushPlayback();
                    assistantSpeaking = false;
                    transcript.setLength(0);
                    suppressMicUntilMs = 0L;
                    if (listener != null) listener.onAssistantAudioDone();
                }

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
        if (acousticEchoCanceler != null) {
            try { acousticEchoCanceler.release(); } catch (Exception ignored) { }
            acousticEchoCanceler = null;
        }
        if (noiseSuppressor != null) {
            try { noiseSuppressor.release(); } catch (Exception ignored) { }
            noiseSuppressor = null;
        }
        echoCancellationActive = false;

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
            int size = Math.max(min * 6, 19200);
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

    private void ensurePlaybackWorker() {
        synchronized (audioLock) {
            if (playbackWorkerRunning && playbackThread != null) return;
            playbackWorkerRunning = true;
            playbackThread = new Thread(() -> {
                while (playbackWorkerRunning) {
                    byte[] pcm;
                    try {
                        pcm = playbackQueue.take();
                    } catch (InterruptedException e) {
                        break;
                    }
                    if (pcm == null || pcm.length == 0) continue;
                    try {
                        ensureAudioTrack();
                        AudioTrack track;
                        synchronized (audioLock) { track = audioTrack; }
                        if (track != null) {
                            track.write(pcm, 0, pcm.length, AudioTrack.WRITE_BLOCKING);
                        }
                    } catch (Exception ignored) {
                    } finally {
                        queuedPlaybackBytes.addAndGet(-pcm.length);
                    }
                }
            }, "MiRobotAI-Playback");
            playbackThread.start();
        }
    }

    private void playChunk(byte[] pcm) {
        if (pcm == null || pcm.length == 0) return;
        // Keep microphone streaming during Gemini audio so real user speech can
        // interrupt the response (barge-in).
        ensurePlaybackWorker();
        byte[] copy = java.util.Arrays.copyOf(pcm, pcm.length);
        queuedPlaybackBytes.addAndGet(copy.length);
        playbackQueue.offer(copy);
    }

    private void pauseGeminiMicForAssistant() {
        suppressMicUntilMs = Math.max(suppressMicUntilMs, System.currentTimeMillis() + 750L);
        if (provider != Provider.GEMINI || geminiInputPausedForAssistant || socket == null) return;
        geminiInputPausedForAssistant = true;
        try {
            JSONObject realtimeInput = new JSONObject();
            realtimeInput.put("audioStreamEnd", true);
            JSONObject event = new JSONObject();
            event.put("realtimeInput", realtimeInput);
            socket.send(event.toString());
        } catch (JSONException ignored) { }
    }

    private void flushPlayback() {
        playbackQueue.clear();
        queuedPlaybackBytes.set(0L);
        synchronized (audioLock) {
            if (audioTrack != null) {
                try { audioTrack.pause(); audioTrack.flush(); audioTrack.play(); }
                catch (Exception ignored) { }
            }
        }
    }

    private void stopPlayback() {
        playbackWorkerRunning = false;
        Thread worker = playbackThread;
        playbackThread = null;
        if (worker != null) worker.interrupt();
        playbackQueue.clear();
        queuedPlaybackBytes.set(0L);
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
                retryHandler.removeCallbacks(geminiSetupTimeout);
                geminiRetryCount = 0;
                sessionReady = true;
                status(isRoboticsMode() ? "Gemini Robotics ER 2 READY 🤖🎙️" : "Gemini Live READY 🎙️");
                startMicrophone();
                if (listener != null) listener.onAiConnected();
                return;
            }
            if (event.has("error")) {
                JSONObject err = event.optJSONObject("error");
                status("Gemini error: " + (err == null ? text : err.optString("message", "unknown")));
                return;
            }
            JSONObject toolCall = event.optJSONObject("toolCall");
            if (toolCall != null) {
                JSONArray calls = toolCall.optJSONArray("functionCalls");
                if (calls != null && listener != null) {
                    for (int i = 0; i < calls.length(); i++) {
                        JSONObject fc = calls.optJSONObject(i);
                        if (fc == null) continue;
                        String id = fc.optString("id", "");
                        String name = fc.optString("name", "");
                        JSONObject args = fc.optJSONObject("args");
                        listener.onRobotToolCall(id, name, args == null ? new JSONObject() : args);
                    }
                }
                return;
            }

            JSONObject server = event.optJSONObject("serverContent");
            if (server == null) {
                if (!sessionReady) {
                    java.util.Iterator<String> keys = event.keys();
                    String firstKey = keys.hasNext() ? keys.next() : "unknown";
                    status("Gemini replied during setup: " + firstKey);
                }
                return;
            }

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
            String preview = text == null ? "" : text.replace('\n', ' ').replace('\r', ' ').trim();
            if (preview.length() > 180) preview = preview.substring(0, 180);
            status("Gemini event parse error: " + (e.getMessage() == null ? "unknown" : e.getMessage())
                    + (preview.isEmpty() ? "" : " — " + preview));
        }
    }

    private void finishAssistantAudio() {
        if (assistantSpeaking && listener != null) listener.onAssistantAudioDone();
        if (provider == Provider.GEMINI) {
            // Keep only a tiny speaker tail. Long microphone muting would prevent
            // the user from naturally interrupting / continuing the conversation.
            suppressMicUntilMs = Math.max(
                    suppressMicUntilMs,
                    System.currentTimeMillis() + (echoCancellationActive ? 120L : 280L));
        }
        assistantSpeaking = false;
        transcript.setLength(0);
    }

    private void status(String message) {
        if (listener != null) listener.onAiStatus(message);
    }
}
