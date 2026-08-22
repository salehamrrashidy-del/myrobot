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

import java.util.UUID;

public class MiRobotBleManager {
    public static final UUID SERVICE_UUID = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e");
    public static final UUID WRITE_UUID   = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e");
    public static final UUID NOTIFY_UUID  = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e");
    private static final UUID CCCD_UUID   = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    public interface Listener {
        void onStatus(String status);
        void onDeviceFound(BluetoothDevice device, int rssi);
        void onReady();
        void onData(byte[] data);
    }

    private final Context context;
    private final Listener listener;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final BluetoothAdapter adapter;
    private BluetoothLeScanner scanner;
    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic writeCharacteristic;
    private boolean ready = false;

    public MiRobotBleManager(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
        BluetoothManager manager = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        adapter = manager.getAdapter();
    }

    public boolean isBluetoothAvailable() {
        return adapter != null;
    }

    public boolean isBluetoothEnabled() {
        return adapter != null && adapter.isEnabled();
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
        listener.onStatus("Scanning… tap your robot when it appears");
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
        ready = false;
        writeCharacteristic = null;
        if (gatt != null) {
            try { gatt.close(); } catch (Exception ignored) {}
        }
        listener.onStatus("Connecting to " + safeName(device) + "…");
        gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE);
    }

    public boolean isReady() {
        return ready && gatt != null && writeCharacteristic != null;
    }

    @SuppressLint("MissingPermission")
    public boolean write(byte[] packet) {
        if (!isReady()) {
            listener.onStatus("Robot is not ready yet");
            return false;
        }
        int props = writeCharacteristic.getProperties();
        if ((props & BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0) {
            writeCharacteristic.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
        } else {
            writeCharacteristic.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
        }
        writeCharacteristic.setValue(packet);
        boolean queued = gatt.writeCharacteristic(writeCharacteristic);
        if (!queued) listener.onStatus("Bluetooth write was not queued");
        return queued;
    }

    public void sendHandshake() {
        write(MiRobotProtocol.connectPacket());
    }

    public void stop() {
        write(MiRobotProtocol.stopPacket());
    }

    @SuppressLint("MissingPermission")
    public void close() {
        stopScan();
        if (gatt != null) {
            try { gatt.disconnect(); } catch (Exception ignored) {}
            try { gatt.close(); } catch (Exception ignored) {}
            gatt = null;
        }
        ready = false;
    }

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override public void onScanResult(int callbackType, ScanResult result) {
            BluetoothDevice d = result.getDevice();
            handler.post(() -> listener.onDeviceFound(d, result.getRssi()));
        }
    };

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                handler.post(() -> listener.onStatus("Connected. Finding robot controls…"));
                try { g.discoverServices(); } catch (SecurityException e) {
                    handler.post(() -> listener.onStatus("Bluetooth permission missing"));
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                ready = false;
                handler.post(() -> listener.onStatus("Disconnected"));
            }
        }

        @Override public void onServicesDiscovered(BluetoothGatt g, int status) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                handler.post(() -> listener.onStatus("Could not discover robot service"));
                return;
            }
            BluetoothGattService service = g.getService(SERVICE_UUID);
            if (service == null) {
                handler.post(() -> listener.onStatus("Connected device is not the expected Mi Robot controller"));
                return;
            }
            writeCharacteristic = service.getCharacteristic(WRITE_UUID);
            BluetoothGattCharacteristic notify = service.getCharacteristic(NOTIFY_UUID);
            if (writeCharacteristic == null) {
                handler.post(() -> listener.onStatus("Robot write channel not found"));
                return;
            }

            if (notify != null) enableNotifications(g, notify);
            else finishReady();
        }

        @Override public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor descriptor, int status) {
            finishReady();
        }

        @Override public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic characteristic) {
            byte[] value = characteristic.getValue();
            if (value != null) handler.post(() -> listener.onData(value));
        }
    };

    @SuppressLint("MissingPermission")
    private void enableNotifications(BluetoothGatt g, BluetoothGattCharacteristic c) {
        try {
            g.setCharacteristicNotification(c, true);
            BluetoothGattDescriptor d = c.getDescriptor(CCCD_UUID);
            if (d != null) {
                d.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                if (g.writeDescriptor(d)) return;
            }
        } catch (Exception ignored) {}
        finishReady();
    }

    private void finishReady() {
        if (ready) return;
        ready = true;
        handler.post(() -> {
            listener.onStatus("Robot ready ✅");
            listener.onReady();
        });
        handler.postDelayed(this::sendHandshake, 200);
        handler.postDelayed(this::stop, 500);
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
}
