package com.example.mirobotai;

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothDevice;
import android.content.pm.ActivityInfo;
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
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.ListView;
import android.widget.SeekBar;
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
        CompanionController.MotionSink {

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
            sendMove(forward, turn, Math.max(6, Math.min(14, delta)));
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
        stopRobotInternal();
        if (ble != null) ble.close();
        super.onDestroy();
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) immersive();
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }
}
