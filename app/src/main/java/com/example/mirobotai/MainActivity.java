package com.example.mirobotai;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
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
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class MainActivity extends Activity implements MiRobotBleManager.Listener {
    private static final int REQ_BLE = 42;

    private MiRobotBleManager ble;
    private MoodEngine moodEngine;
    private RobotFaceView faceView;
    private TextView statusText;
    private TextView moodText;
    private LinearLayout debugPanel;
    private CheckBox invertLeft;
    private CheckBox invertRight;
    private SeekBar speedBar;
    private ArrayAdapter<String> listAdapter;
    private final List<BluetoothDevice> devices = new ArrayList<>();
    private final Map<String, Integer> addressIndex = new LinkedHashMap<>();
    private final Handler handler = new Handler(Looper.getMainLooper());

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        setContentView(R.layout.activity_main);
        immersive();

        faceView = findViewById(R.id.faceView);
        statusText = findViewById(R.id.statusText);
        moodText = findViewById(R.id.moodText);
        debugPanel = findViewById(R.id.debugPanel);
        invertLeft = findViewById(R.id.invertLeft);
        invertRight = findViewById(R.id.invertRight);
        speedBar = findViewById(R.id.speedBar);

        ble = new MiRobotBleManager(this, this);
        moodEngine = new MoodEngine((mood, emotion) -> runOnUiThread(() -> {
            if (faceView.getEmotion() != Emotion.EXCITED && faceView.getEmotion() != Emotion.SURPRISED) {
                faceView.setEmotion(emotion);
            }
            moodText.setText("mood " + mood + "%");
        }));
        moodEngine.start();

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
        findViewById(R.id.stopButton).setOnClickListener(v -> stopRobot());

        bindHoldButton(findViewById(R.id.forwardButton), +1, 0);
        bindHoldButton(findViewById(R.id.backButton), -1, 0);
        bindHoldButton(findViewById(R.id.leftButton), 0, -1);
        bindHoldButton(findViewById(R.id.rightButton), 0, +1);

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
            // Visual-only preview. There is deliberately NO Android TTS in this app.
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

        requestBlePermissionsIfNeeded();
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
            requestBlePermissionsIfNeeded();
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
                move(forward, turn);
                return true;
            }
            if (event.getAction() == MotionEvent.ACTION_UP || event.getAction() == MotionEvent.ACTION_CANCEL) {
                stopRobot();
                return true;
            }
            return false;
        });
    }

    private void move(int forward, int turn) {
        if (!ble.isReady()) {
            Toast.makeText(this, "Connect robot first", Toast.LENGTH_SHORT).show();
            return;
        }
        int delta = Math.max(8, speedBar.getProgress());
        int leftDelta = (forward * delta) + (turn * delta);
        int rightDelta = (forward * delta) - (turn * delta);
        if (invertLeft.isChecked()) leftDelta = -leftDelta;
        if (invertRight.isChecked()) rightDelta = -rightDelta;
        int left = clamp(MiRobotProtocol.NEUTRAL + leftDelta, 0, 255);
        int right = clamp(MiRobotProtocol.NEUTRAL + rightDelta, 0, 255);
        ble.move(left, right);
    }

    private void stopRobot() {
        if (ble != null && ble.isReady()) ble.stop();
    }

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
                listAdapter.remove(listAdapter.getItem(existing));
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

    private void requestBlePermissionsIfNeeded() {
        if (hasBlePermissions()) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            requestPermissions(new String[]{Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT}, REQ_BLE);
        } else {
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, REQ_BLE);
        }
    }

    private boolean hasBlePermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
                    && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
        }
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    @Override protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (moodEngine != null) moodEngine.stop();
        stopRobot();
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
