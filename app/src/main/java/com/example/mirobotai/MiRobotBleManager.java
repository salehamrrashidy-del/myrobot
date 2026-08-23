package com.example.mirobotai;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Locale;
import java.util.Queue;
import java.util.UUID;

public class MiRobotBleManager {
    public static final UUID SERVICE_UUID = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e");
    public static final UUID WRITE_UUID   = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e");
    public static final UUID NOTIFY_UUID  = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e");
    private static final UUID CCCD_UUID   = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    // The original app retries its 4-byte handshake three times at ~150 ms.
    private static final int MAX_HANDSHAKE_ATTEMPTS = 3;
    private static final long HANDSHAKE_RETRY_MS = 150L;

    public interface Listener {
        void onStatus(String status);
        void onDeviceFound(BluetoothDevice device, int rssi);
        void onReady();
        void onData(byte[] data);
        void onTx(byte[] data);
    }

    private final Context context;
    private final Listener listener;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final BluetoothAdapter adapter;

    private BluetoothLeScanner scanner;
    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic writeCharacteristic;
    private BluetoothGattCharacteristic notifyCharacteristic;

    private final Queue<byte[]> writeQueue = new ArrayDeque<>();
    private boolean writeInFlight = false;
    private boolean ready = false;
    private boolean handshaking = false;
    private int handshakeAttempts = 0;

    public MiRobotBleManager(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
        BluetoothManager manager = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        adapter = manager == null ? null : manager.getAdapter();
    }

    public boolean isBluetoothAvailable() {
        return adapter != null;
    }

    public boolean isBluetoothEnabled() {
        return adapter != null && adapter.isEnabled();
    }

    public boolean isReady() {
        return ready && gatt != null && writeCharacteristic != null;
    }

    @SuppressLint("MissingPermission")
    public void scan() {
        if (!isBluetoothEnabled()) {
            listener.onStatus("Turn Bluetooth on first");
            return;
        }
        scanner = adapter.getBluetoothLeScanner();
        if (scanner == null) {
            listener.onStatus("BLE scanner unavailable");
            return;
        }
        listener.onStatus("1/4 Scanning… tap the Mi Robot when it appears");
        scanner.startScan(scanCallback);
        handler.postDelayed(this::stopScan, 8000);
    }

    @SuppressLint("MissingPermission")
    public void stopScan() {
        if (scanner != null) {
            try { scanner.stopScan(scanCallback); } catch (Exception ignored) {}
        }
    }

    @SuppressLint("MissingPermission")
    public void connect(BluetoothDevice device) {
        stopScan();
        resetState();
        if (gatt != null) {
            try { gatt.close(); } catch (Exception ignored) {}
            gatt = null;
        }
        listener.onStatus("2/4 Bluetooth connecting to " + safeName(device) + "…");
        gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE);
    }

    public void move(int axisA, int axisB) {
        if (!isReady()) {
            listener.onStatus("Robot handshake is not ready yet");
            return;
        }
        enqueueWrite(MiRobotProtocol.rockerPacket(axisA, axisB));
    }

    public void stop() {
        if (!isReady()) return;
        enqueueWrite(MiRobotProtocol.stopPacket());
    }

    @SuppressLint("MissingPermission")
    public void close() {
        stopScan();
        handler.removeCallbacksAndMessages(null);
        if (gatt != null) {
            try { gatt.disconnect(); } catch (Exception ignored) {}
            try { gatt.close(); } catch (Exception ignored) {}
            gatt = null;
        }
        resetState();
    }

    private void resetState() {
        ready = false;
        handshaking = false;
        handshakeAttempts = 0;
        writeInFlight = false;
        writeCharacteristic = null;
        notifyCharacteristic = null;
        writeQueue.clear();
    }

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override public void onScanResult(int callbackType, ScanResult result) {
            BluetoothDevice d = result.getDevice();
            handler.post(() -> listener.onDeviceFound(d, result.getRssi()));
        }

        @Override public void onScanFailed(int errorCode) {
            handler.post(() -> listener.onStatus("Scan failed: " + errorCode));
        }
    };

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                handler.post(() -> listener.onStatus("2/4 BLE connected ✅  Finding robot controls…"));
                try {
                    if (!g.discoverServices()) {
                        handler.post(() -> listener.onStatus("Could not start service discovery"));
                    }
                } catch (SecurityException e) {
                    handler.post(() -> listener.onStatus("Bluetooth permission missing"));
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                resetState();
                handler.post(() -> listener.onStatus("Disconnected"));
            } else if (status != BluetoothGatt.GATT_SUCCESS) {
                handler.post(() -> listener.onStatus("Bluetooth error " + status));
            }
        }

        @Override public void onServicesDiscovered(BluetoothGatt g, int status) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                handler.post(() -> listener.onStatus("Could not discover robot service"));
                return;
            }

            BluetoothGattService service = g.getService(SERVICE_UUID);
            if (service == null) {
                handler.post(() -> listener.onStatus("Wrong device: Mi Robot BLE service not found"));
                return;
            }

            writeCharacteristic = service.getCharacteristic(WRITE_UUID);
            notifyCharacteristic = service.getCharacteristic(NOTIFY_UUID);

            if (writeCharacteristic == null) {
                handler.post(() -> listener.onStatus("Robot command channel not found"));
                return;
            }

            handler.post(() -> listener.onStatus("3/4 Robot controls found ✅  Opening reply channel…"));

            if (notifyCharacteristic != null) {
                enableNotifications(g, notifyCharacteristic);
            } else {
                // The original app expects this characteristic, but allow the
                // handshake to continue for diagnostics if firmware omits it.
                handler.postDelayed(MiRobotBleManager.this::beginHandshake, HANDSHAKE_RETRY_MS);
            }
        }

        @Override public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor descriptor, int status) {
            if (CCCD_UUID.equals(descriptor.getUuid())) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    handler.post(() -> listener.onStatus("3/4 Reply channel open ✅  Handshaking…"));
                } else {
                    handler.post(() -> listener.onStatus("Reply channel returned error " + status + ". Trying handshake anyway…"));
                }
                handler.postDelayed(MiRobotBleManager.this::beginHandshake, HANDSHAKE_RETRY_MS);
            }
        }

        @Override public void onCharacteristicWrite(BluetoothGatt g,
                                                      BluetoothGattCharacteristic characteristic,
                                                      int status) {
            byte[] written = characteristic.getValue();
            writeInFlight = false;

            if (status == BluetoothGatt.GATT_SUCCESS) {
                if (handshaking && isHandshake(written)) {
                    handshaking = false;
                    ready = true;
                    writeQueue.clear();
                    handler.removeCallbacks(handshakeRetryRunnable);
                    handler.post(() -> {
                        listener.onStatus("4/4 ROBOT READY ✅");
                        listener.onReady();
                    });
                    // Centre the rocker after the handshake, just like returning
                    // the original joystick to its neutral position.
                    handler.postDelayed(() -> enqueueWrite(MiRobotProtocol.stopPacket()), 120L);
                    return;
                }
            } else if (handshaking && isHandshake(written)) {
                handler.post(() -> listener.onStatus("Handshake write error " + status + ". Retrying…"));
            }

            pumpWrites();
        }

        @Override public void onCharacteristicChanged(BluetoothGatt g,
                                                       BluetoothGattCharacteristic characteristic) {
            byte[] value = characteristic.getValue();
            if (value != null) {
                byte[] copy = Arrays.copyOf(value, value.length);
                handler.post(() -> listener.onData(copy));
            }
        }
    };

    @SuppressLint("MissingPermission")
    private void enableNotifications(BluetoothGatt g, BluetoothGattCharacteristic c) {
        try {
            boolean localEnabled = g.setCharacteristicNotification(c, true);
            BluetoothGattDescriptor d = c.getDescriptor(CCCD_UUID);
            if (d != null) {
                d.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                if (g.writeDescriptor(d)) return;
            }
            // If no descriptor write could be queued, still try after a short delay.
            handler.post(() -> listener.onStatus(localEnabled
                    ? "Reply notifications enabled; handshaking…"
                    : "Could not enable notifications; trying handshake…"));
        } catch (Exception e) {
            handler.post(() -> listener.onStatus("Notification setup issue; trying handshake…"));
        }
        handler.postDelayed(this::beginHandshake, HANDSHAKE_RETRY_MS);
    }

    private void beginHandshake() {
        if (ready || handshaking || writeCharacteristic == null || gatt == null) return;
        handshaking = true;
        handshakeAttempts = 0;
        writeQueue.clear();
        writeInFlight = false;
        sendHandshakeAttempt();
    }

    private final Runnable handshakeRetryRunnable = new Runnable() {
        @Override public void run() {
            if (!handshaking || ready) return;
            if (handshakeAttempts >= MAX_HANDSHAKE_ATTEMPTS) {
                handshaking = false;
                listener.onStatus("Handshake failed ❌  Turn robot off/on and reconnect");
                return;
            }
            if (!writeInFlight) sendHandshakeAttempt();
            else handler.postDelayed(this, HANDSHAKE_RETRY_MS);
        }
    };

    private void sendHandshakeAttempt() {
        if (!handshaking || ready) return;
        handshakeAttempts++;
        byte[] h = MiRobotProtocol.bleHandshakePacket();
        listener.onStatus("4/4 Handshake " + handshakeAttempts + "/" + MAX_HANDSHAKE_ATTEMPTS + "…");
        writeNow(h);
        handler.removeCallbacks(handshakeRetryRunnable);
        handler.postDelayed(handshakeRetryRunnable, HANDSHAKE_RETRY_MS * 2);
    }

    private boolean isHandshake(byte[] value) {
        return value != null && Arrays.equals(value, MiRobotProtocol.bleHandshakePacket());
    }

    private void enqueueWrite(byte[] packet) {
        if (packet == null || gatt == null || writeCharacteristic == null) return;
        writeQueue.offer(Arrays.copyOf(packet, packet.length));
        pumpWrites();
    }

    private void pumpWrites() {
        if (writeInFlight || gatt == null || writeCharacteristic == null) return;
        byte[] next = writeQueue.poll();
        if (next == null) return;
        writeNow(next);
    }

    @SuppressLint("MissingPermission")
    private void writeNow(byte[] packet) {
        if (gatt == null || writeCharacteristic == null) return;
        try {
            // IMPORTANT: the original Xiaomi app does NOT force
            // WRITE_NO_RESPONSE. It writes using the characteristic's normal
            // write type and waits for onCharacteristicWrite.
            writeCharacteristic.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
            writeCharacteristic.setValue(packet);
            writeInFlight = gatt.writeCharacteristic(writeCharacteristic);
            listener.onTx(Arrays.copyOf(packet, packet.length));
            if (!writeInFlight) {
                handler.post(() -> listener.onStatus("Bluetooth write busy; retrying…"));
                if (handshaking) {
                    handler.postDelayed(handshakeRetryRunnable, HANDSHAKE_RETRY_MS);
                } else {
                    handler.postDelayed(this::pumpWrites, 80L);
                }
            }
        } catch (Exception e) {
            writeInFlight = false;
            handler.post(() -> listener.onStatus("Bluetooth write error: " + e.getClass().getSimpleName()));
            if (handshaking) handler.postDelayed(handshakeRetryRunnable, HANDSHAKE_RETRY_MS);
        }
    }

    @SuppressLint("MissingPermission")
    private String safeName(BluetoothDevice d) {
        try {
            String n = d.getName();
            return n == null ? d.getAddress() : n;
        } catch (SecurityException e) {
            return d.getAddress();
        }
    }

    public static String hex(byte[] b) {
        if (b == null) return "";
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format(Locale.US, "%02X ", x & 0xFF));
        return sb.toString().trim();
    }
}
