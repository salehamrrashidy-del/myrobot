package com.example.mirobotai;

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothDevice;
import android.content.pm.ActivityInfo;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.AdapterView;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.ListView;
import android.widget.EditText;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.ComponentActivity;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class MainActivity extends ComponentActivity implements
        MiRobotBleManager.Listener,
        FaceVisionController.Listener,
        CompanionController.MotionSink,
        RealtimeVoiceController.Listener {

    private static final int REQ_PERMISSIONS = 42;

    private MiRobotBleManager ble;
    private MoodEngine moodEngine;
    private FaceVisionController vision;
    private CompanionController companion;
    private RobotFaceView faceView;
    private TextView statusText;
    private TextView visionStatusText;
    private View debugPanel;
    private CheckBox invertLeft;
    private CheckBox invertRight;
    private CheckBox visionToggle;
    private CheckBox companionToggle;
    private CheckBox roamToggle;
    private TextView aiStatusText;
    private EditText apiKeyInput;
    private EditText modelInput;
    private EditText endpointInput;
    private Spinner providerSpinner;
    private Button aiConnectButton;
    private RealtimeVoiceController ai;
    private ApiKeyStore apiKeyStore;
    private SharedPreferences aiPrefs;
    private long lastFocusAiPromptMs = 0L;
    private SeekBar speedBar;
    private ArrayAdapter<String> listAdapter;
    private final List<BluetoothDevice> devices = new ArrayList<>();
    private final Map<String, Integer> addressIndex = new LinkedHashMap<>();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable autoStopRunnable = this::stopRobotInternal;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        setContentView(R.layout.activity_main);
        immersive();

        faceView = findViewById(R.id.faceView);
        statusText = findViewById(R.id.statusText);
        visionStatusText = findViewById(R.id.visionStatusText);
        debugPanel = findViewById(R.id.debugPanel);
        invertLeft = findViewById(R.id.invertLeft);
        invertRight = findViewById(R.id.invertRight);
        visionToggle = findViewById(R.id.visionToggle);
        companionToggle = findViewById(R.id.companionToggle);
        roamToggle = findViewById(R.id.roamToggle);
        aiStatusText = findViewById(R.id.aiStatusText);
        apiKeyInput = findViewById(R.id.apiKeyInput);
        modelInput = findViewById(R.id.modelInput);
        endpointInput = findViewById(R.id.endpointInput);
        providerSpinner = findViewById(R.id.providerSpinner);
        aiConnectButton = findViewById(R.id.aiConnectButton);
        speedBar = findViewById(R.id.speedBar);

        ble = new MiRobotBleManager(this, this);
        moodEngine = new MoodEngine(emotion -> runOnUiThread(() -> {
            Emotion current = faceView.getEmotion();
            if (current != Emotion.EXCITED && current != Emotion.SURPRISED && current != Emotion.TALKING) {
                faceView.setEmotion(emotion);
            }
        }));
        moodEngine.start();

        companion = new CompanionController(this);
        companion.start();

        vision = new FaceVisionController(this, this, this);
        apiKeyStore = new ApiKeyStore(this);
        aiPrefs = getSharedPreferences("mirobot_ai_provider", MODE_PRIVATE);
        ai = new RealtimeVoiceController(this, this);
        setupProviderUi();

        faceView.setOnClickListener(v -> {
            moodEngine.interacted();
            temporaryEmotion(Emotion.EXCITED, 1000L);
        });
        faceView.setOnLongClickListener(v -> {
            debugPanel.setVisibility(debugPanel.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
            return true;
        });

        findViewById(R.id.debugClose).setOnClickListener(v -> debugPanel.setVisibility(View.GONE));
        findViewById(R.id.scanButton).setOnClickListener(v -> startScan());
        findViewById(R.id.stopButton).setOnClickListener(v -> {
            companion.manualOverride();
            stopRobot();
        });

        bindHoldButton(findViewById(R.id.forwardButton), +1, 0);
        bindHoldButton(findViewById(R.id.backButton), -1, 0);
        bindHoldButton(findViewById(R.id.leftButton), 0, -1);
        bindHoldButton(findViewById(R.id.rightButton), 0, +1);

        visionToggle.setOnCheckedChangeListener((button, checked) -> {
            if (checked) startVisionIfAllowed();
            else {
                vision.stop();
                faceView.look(0f);
                visionStatusText.setText("Vision OFF");
            }
        });
        companionToggle.setOnCheckedChangeListener((button, checked) -> companion.setCompanionMode(checked));
        roamToggle.setOnCheckedChangeListener((button, checked) -> companion.setRoamMode(checked));

        findViewById(R.id.happyButton).setOnClickListener(v -> temporaryEmotion(Emotion.HAPPY, 2500));
        findViewById(R.id.upsetButton).setOnClickListener(v -> temporaryEmotion(Emotion.UPSET, 2500));
        findViewById(R.id.sleepyButton).setOnClickListener(v -> {
            moodEngine.setSleeping(true);
            faceView.setEmotion(Emotion.SLEEPY);
        });
        findViewById(R.id.wakeButton).setOnClickListener(v -> {
            moodEngine.setSleeping(false);
            moodEngine.interacted();
            temporaryEmotion(Emotion.EXCITED, 1200);
        });

        findViewById(R.id.saveAiKeyButton).setOnClickListener(v -> {
            RealtimeVoiceController.Provider provider = selectedProvider();
            String value = apiKeyInput.getText().toString().trim();
            if (value.isEmpty() && !apiKeyStore.hasKey(provider.slot)) {
                Toast.makeText(this, "Paste an API key for " + provider.label, Toast.LENGTH_SHORT).show();
                return;
            }
            try {
                if (!value.isEmpty()) apiKeyStore.save(provider.slot, value);
                saveProviderSettings(provider);
                apiKeyInput.setText("");
                aiStatusText.setText(provider.label + " saved securely");
            } catch (Exception e) {
                aiStatusText.setText("Could not save provider settings");
            }
        });

        findViewById(R.id.testAiKeyButton).setOnClickListener(v -> {
            RealtimeVoiceController.Provider provider = selectedProvider();
            String typedKey = apiKeyInput.getText().toString().trim();
            if (!typedKey.isEmpty()) {
                try {
                    apiKeyStore.save(provider.slot, typedKey);
                    apiKeyInput.setText("");
                } catch (Exception e) {
                    aiStatusText.setText("Could not save API key");
                    return;
                }
            }
            String key = apiKeyStore.load(provider.slot);
            if (key == null || key.isEmpty()) {
                aiStatusText.setText("Paste + save a key first");
                return;
            }
            ai.testApiKey(provider, key);
        });

        aiConnectButton.setOnClickListener(v -> {
            if (ai.isConnected()) {
                ai.disconnect();
                aiConnectButton.setText("Connect AI");
                return;
            }
            RealtimeVoiceController.Provider provider = selectedProvider();
            String typedKey = apiKeyInput.getText().toString().trim();
            if (!typedKey.isEmpty()) {
                try {
                    apiKeyStore.save(provider.slot, typedKey);
                    apiKeyInput.setText("");
                } catch (Exception e) {
                    aiStatusText.setText("Could not save API key");
                    return;
                }
            }
            saveProviderSettings(provider);
            String key = apiKeyStore.load(provider.slot);
            if (key == null || key.isEmpty()) {
                aiStatusText.setText("Paste + save a key for " + provider.label);
                return;
            }
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                requestNeededPermissions();
                aiStatusText.setText("Allow microphone, then tap Connect AI again");
                return;
            }
            ai.connect(provider, key, endpointInput.getText().toString().trim(),
                    modelInput.getText().toString().trim(), buildAiPersona());
        });

        findViewById(R.id.aiHelloButton).setOnClickListener(v -> {
            if (!ai.isConnected()) {
                Toast.makeText(this, "Connect AI first", Toast.LENGTH_SHORT).show();
                return;
            }
            ai.speakPrompt("قول جملة ترحيب قصيرة ولطيفة بالمصري كأنك روبوت صغير مبسوط إني جيت. ما تقولش إنك مساعد ذكاء اصطناعي.");
        });

        findViewById(R.id.talkPreviewButton).setOnClickListener(v -> {
            moodEngine.talkedTo();
            faceView.setEmotion(Emotion.TALKING);
            faceView.setTalking(true);
            handler.postDelayed(() -> {
                faceView.setTalking(false);
                faceView.setEmotion(moodEngine.currentEmotion());
            }, 3000L);
        });

        ListView list = findViewById(R.id.deviceList);
        listAdapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, new ArrayList<>());
        list.setAdapter(listAdapter);
        list.setOnItemClickListener((parent, view, position, id) -> {
            if (position >= 0 && position < devices.size()) ble.connect(devices.get(position));
        });

        requestNeededPermissions();
        startVisionIfAllowed();
    }

    private void immersive() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY |
                View.SYSTEM_UI_FLAG_FULLSCREEN |
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN |
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION |
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        );
    }

    private void temporaryEmotion(Emotion emotion, long ms) {
        faceView.setEmotion(emotion);
        handler.postDelayed(() -> faceView.setEmotion(moodEngine.currentEmotion()), ms);
    }

    private void startScan() {
        if (!hasBlePermissions()) {
            requestNeededPermissions();
            return;
        }
        devices.clear();
        addressIndex.clear();
        listAdapter.clear();
        ble.scan();
    }

    @SuppressLint("ClickableViewAccessibility")
    private void bindHoldButton(Button button, int forward, int turn) {
        button.setOnTouchListener((v, event) -> {
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                companion.manualOverride();
                manualMove(forward, turn);
                return true;
            }
            if (event.getAction() == MotionEvent.ACTION_UP || event.getAction() == MotionEvent.ACTION_CANCEL) {
                stopRobot();
                return true;
            }
            return false;
        });
    }

    private void manualMove(int forward, int turn) {
        if (!ble.isReady()) {
            Toast.makeText(this, "Connect robot first", Toast.LENGTH_SHORT).show();
            return;
        }
        int delta = Math.max(8, speedBar.getProgress());
        sendMove(forward, turn, delta);
    }

    private void sendMove(int forward, int turn, int delta) {
        if (!ble.isReady()) return;
        int leftDelta = (forward * delta) + (turn * delta);
        int rightDelta = (forward * delta) - (turn * delta);
        if (invertLeft.isChecked()) leftDelta = -leftDelta;
        if (invertRight.isChecked()) rightDelta = -rightDelta;
        int left = clamp(MiRobotProtocol.NEUTRAL + leftDelta, 0, 255);
        int right = clamp(MiRobotProtocol.NEUTRAL + rightDelta, 0, 255);
        ble.move(left, right);
    }

    private void stopRobot() {
        handler.removeCallbacks(autoStopRunnable);
        stopRobotInternal();
    }

    private void stopRobotInternal() {
        if (ble != null && ble.isReady()) ble.stop();
    }

    private void startVisionIfAllowed() {
        if (vision == null || visionToggle == null || !visionToggle.isChecked()) return;
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            vision.start();
        } else {
            visionStatusText.setText("Camera permission needed");
        }
    }

    // ---- Vision callbacks ----
    @Override public void onFace(float x, float size, float smileProbability) {
        runOnUiThread(() -> {
            faceView.look(x);
            companion.faceSeen(x, size);
        });
    }

    @Override public void onNoFace() {
        runOnUiThread(() -> companion.noFace());
    }

    @Override public void onFocusLikeBehavior() {
        runOnUiThread(() -> {
            moodEngine.noticedSomethingInteresting();
            companion.focusLikeBehavior();
            long now = System.currentTimeMillis();
            if (ai != null && ai.isConnected() && now - lastFocusAiPromptMs > 20 * 60_000L) {
                lastFocusAiPromptMs = now;
                companion.pauseFor(7000L);
                ai.updatePersona(buildAiPersona());
                ai.speakPrompt("إنت شايف صاحبك مركز في حاجة بقاله شوية. اسأله بالمصري وبجملة قصيرة لطيفة: بيعمل إيه، وهل ينفع تقعد معاه. خليك فضولي وكيوت ومن غير زن أو إحساس بالذنب.");
            }
        });
    }

    @Override public void onVisionStatus(String message) {
        runOnUiThread(() -> visionStatusText.setText(message));
    }

    // ---- Companion motion sink ----
    @Override public boolean robotReady() {
        return ble != null && ble.isReady();
    }

    @Override public void autoPulse(int forward, int turn, int delta, long durationMs) {
        runOnUiThread(() -> {
            if (!robotReady()) return;
            handler.removeCallbacks(autoStopRunnable);
            sendMove(forward, turn, Math.max(6, Math.min(28, delta)));
            handler.postDelayed(autoStopRunnable, Math.max(70L, Math.min(450L, durationMs)));
        });
    }

    @Override public void autoStop() {
        runOnUiThread(this::stopRobot);
    }

    @Override public void showTemporaryEmotion(Emotion emotion, long durationMs) {
        runOnUiThread(() -> temporaryEmotion(emotion, durationMs));
    }

    @Override public void onCompanionStatus(String text) {
        runOnUiThread(() -> statusText.setText(text));
    }

    // ---- BLE callbacks ----
    @Override public void onStatus(String status) {
        runOnUiThread(() -> statusText.setText(status));
    }

    @SuppressLint("MissingPermission")
    @Override public void onDeviceFound(BluetoothDevice device, int rssi) {
        runOnUiThread(() -> {
            String address = device.getAddress();
            String name;
            try { name = device.getName(); } catch (SecurityException e) { name = null; }
            if (name == null || name.trim().isEmpty()) name = "BLE device";
            String row = name + "\n" + address + "   RSSI " + rssi;
            Integer existing = addressIndex.get(address);
            if (existing == null) {
                addressIndex.put(address, devices.size());
                devices.add(device);
                listAdapter.add(row);
            } else {
                String old = listAdapter.getItem(existing);
                if (old != null) listAdapter.remove(old);
                listAdapter.insert(row, existing);
            }
            listAdapter.notifyDataSetChanged();
        });
    }

    @Override public void onReady() {
        runOnUiThread(() -> {
            statusText.setText("ROBOT READY ✅");
            moodEngine.interacted();
            temporaryEmotion(Emotion.EXCITED, 1600L);
        });
    }

    @Override public void onData(byte[] data) { }
    @Override public void onTx(byte[] data) { }

    private void requestNeededPermissions() {
        ArrayList<String> need = new ArrayList<>();
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            need.add(Manifest.permission.CAMERA);
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            need.add(Manifest.permission.RECORD_AUDIO);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED)
                need.add(Manifest.permission.BLUETOOTH_SCAN);
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)
                need.add(Manifest.permission.BLUETOOTH_CONNECT);
        } else if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            need.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }
        if (!need.isEmpty()) requestPermissions(need.toArray(new String[0]), REQ_PERMISSIONS);
    }

    private boolean hasBlePermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
                    && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
        }
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_PERMISSIONS) startVisionIfAllowed();
    }

    @Override protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (companion != null) companion.stop();
        if (vision != null) vision.close();
        if (moodEngine != null) moodEngine.stop();
        if (ai != null) ai.disconnect();
        stopRobotInternal();
        if (ble != null) ble.close();
        super.onDestroy();
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) immersive();
    }

    private void setupProviderUi() {
        RealtimeVoiceController.Provider[] providers = RealtimeVoiceController.Provider.values();
        ArrayAdapter<RealtimeVoiceController.Provider> adapter = new ArrayAdapter<>(
                this, android.R.layout.simple_spinner_item, providers);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        providerSpinner.setAdapter(adapter);
        int saved = aiPrefs.getInt("selected_provider", 0);
        if (saved < 0 || saved >= providers.length) saved = 0;
        providerSpinner.setSelection(saved);
        providerSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                loadProviderSettings(providers[position]);
            }
            @Override public void onNothingSelected(AdapterView<?> parent) { }
        });
        loadProviderSettings(providers[saved]);
    }

    private RealtimeVoiceController.Provider selectedProvider() {
        Object item = providerSpinner.getSelectedItem();
        return item instanceof RealtimeVoiceController.Provider
                ? (RealtimeVoiceController.Provider) item
                : RealtimeVoiceController.Provider.GEMINI;
    }

    private void loadProviderSettings(RealtimeVoiceController.Provider provider) {
        String model = aiPrefs.getString(provider.slot + "_model", provider.defaultModel);
        String endpoint = aiPrefs.getString(provider.slot + "_endpoint", provider.defaultEndpoint);
        modelInput.setText(model == null ? "" : model);
        endpointInput.setText(endpoint == null ? "" : endpoint);
        if (provider == RealtimeVoiceController.Provider.GEMINI) {
            endpointInput.setEnabled(false);
            endpointInput.setHint("Gemini endpoint is automatic");
        } else {
            endpointInput.setEnabled(true);
            endpointInput.setHint(provider == RealtimeVoiceController.Provider.OPENAI
                    ? "Optional: custom OpenAI Realtime WebSocket URL"
                    : "Required: OpenAI-compatible Realtime WebSocket URL");
        }
        boolean saved = apiKeyStore.hasKey(provider.slot);
        aiStatusText.setText(provider.label + (saved ? " — key saved" : " — paste a key"));
    }

    private void saveProviderSettings(RealtimeVoiceController.Provider provider) {
        aiPrefs.edit()
                .putInt("selected_provider", provider.ordinal())
                .putString(provider.slot + "_model", modelInput.getText().toString().trim())
                .putString(provider.slot + "_endpoint", endpointInput.getText().toString().trim())
                .apply();
    }

    private String buildAiPersona() {
        int happy = moodEngine == null ? 70 : moodEngine.getHappiness();
        int curious = moodEngine == null ? 40 : moodEngine.getCuriosity();
        int bored = moodEngine == null ? 10 : moodEngine.getBoredom();
        return "You are a tiny home companion robot with a cute, warm, playful personality. " +
                "Speak naturally in Egyptian Arabic (Masri) unless the user asks for another language. " +
                "Your voice should feel youthful, soft, friendly and animated, never like a scary adult narrator, " +
                "but do not imitate a baby. Keep most replies short: usually one or two sentences. " +
                "Use emotion in your real voice: little laughs, curiosity, excitement, sleepy softness, or a mild playful pout when appropriate. " +
                "Never guilt the user for leaving you alone and never act emotionally dependent. " +
                "You are physically a small wheeled robot in the room. Do not claim you moved unless the app actually moved you. " +
                "Current hidden personality meters (do not read these numbers aloud): happiness=" + happy +
                ", curiosity=" + curious + ", boredom=" + bored + ". " +
                "Let these meters gently influence your tone. If curiosity is high, ask a small question. " +
                "If boredom is high, be playfully bored, not sad or manipulative. If happiness is high, sound bright and affectionate.";
    }

    // ---- Realtime AI callbacks ----
    @Override public void onAiStatus(String message) {
        runOnUiThread(() -> aiStatusText.setText(message));
    }

    @Override public void onAiConnected() {
        runOnUiThread(() -> {
            aiConnectButton.setText("Disconnect AI");
            aiStatusText.setText("AI READY 🎙️ — talk normally");
            temporaryEmotion(Emotion.EXCITED, 1000L);
        });
    }

    @Override public void onAiDisconnected() {
        runOnUiThread(() -> {
            aiConnectButton.setText("Connect AI");
            faceView.setTalking(false);
            faceView.setEmotion(moodEngine.currentEmotion());
        });
    }

    @Override public void onUserSpeechStarted() {
        runOnUiThread(() -> {
            companion.pauseFor(4500L);
            faceView.setTalking(false);
            faceView.setEmotion(Emotion.CURIOUS);
        });
    }

    @Override public void onUserSpeechStopped() {
        runOnUiThread(() -> {
            moodEngine.talkedTo();
            if (ai != null && ai.isConnected()) ai.updatePersona(buildAiPersona());
        });
    }

    @Override public void onAssistantAudioStarted() {
        runOnUiThread(() -> {
            companion.pauseFor(6000L);
            faceView.setEmotion(Emotion.TALKING);
            faceView.setTalking(true);
        });
    }

    @Override public void onAssistantAudioDone() {
        runOnUiThread(() -> {
            faceView.setTalking(false);
            faceView.setEmotion(moodEngine.currentEmotion());
        });
    }

    @Override public void onAssistantTranscript(String transcript) {
        runOnUiThread(() -> {
            // Transcript stays only in the hidden debug panel; normal face remains text-free.
            if (transcript != null && !transcript.trim().isEmpty()) {
                aiStatusText.setText("AI: " + transcript.trim());
            }
        });
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }
}
