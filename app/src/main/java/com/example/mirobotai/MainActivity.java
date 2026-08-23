package com.example.mirobotai;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.bluetooth.BluetoothDevice;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.view.MotionEvent;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.ListView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class MainActivity extends Activity implements MiRobotBleManager.Listener {
    private static final int REQ_BLE = 42;

    private MiRobotBleManager ble;
    private TextView statusText;
    private TextView logText;
    private CheckBox invertLeft;
    private CheckBox invertRight;
    private SeekBar speedBar;
    private ArrayAdapter<String> listAdapter;
    private final List<BluetoothDevice> devices = new ArrayList<>();
    private final Map<String, Integer> addressIndex = new LinkedHashMap<>();

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        statusText = findViewById(R.id.statusText);
        logText = findViewById(R.id.logText);
        invertLeft = findViewById(R.id.invertLeft);
        invertRight = findViewById(R.id.invertRight);
        speedBar = findViewById(R.id.speedBar);

        ble = new MiRobotBleManager(this, this);

        ListView list = findViewById(R.id.deviceList);
        listAdapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, new ArrayList<>());
        list.setAdapter(listAdapter);
        list.setOnItemClickListener((parent, view, position, id) -> {
            if (position >= 0 && position < devices.size()) ble.connect(devices.get(position));
        });

        findViewById(R.id.scanButton).setOnClickListener(v -> startScan());
        findViewById(R.id.stopButton).setOnClickListener(v -> stopRobot());

        bindHoldButton(findViewById(R.id.forwardButton), +1, 0);
        bindHoldButton(findViewById(R.id.backButton), -1, 0);
        bindHoldButton(findViewById(R.id.leftButton), 0, -1);
        bindHoldButton(findViewById(R.id.rightButton), 0, +1);

        requestBlePermissionsIfNeeded();
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
            Toast.makeText(this, "Connect to the robot first", Toast.LENGTH_SHORT).show();
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
        appendLog("MOVE A=" + left + " B=" + right);
    }

    private void stopRobot() {
        if (!ble.isReady()) return;
        ble.stop();
        appendLog("STOP");
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
        appendLog("HANDSHAKE OK — robot ready");
    }

    @Override public void onData(byte[] data) {
        appendLog("RX " + hex(data));
    }

    @Override public void onTx(byte[] data) {
        appendLog("TX " + hex(data));
    }

    private void appendLog(String line) {
        runOnUiThread(() -> logText.append(line + "\n"));
    }

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
        stopRobot();
        ble.close();
        super.onDestroy();
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format(Locale.US, "%02X ", x & 0xFF));
        return sb.toString().trim();
    }
}
