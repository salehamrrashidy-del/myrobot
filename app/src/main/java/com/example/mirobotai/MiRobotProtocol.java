package com.example.mirobotai;

public final class MiRobotProtocol {
    private MiRobotProtocol() {}

    public static final int NEUTRAL = 128;

    /*
     * BLE-level handshake copied from the original Mi Robot Builder Android app.
     * The original app stores this exact 4-byte array as HANDSHAKE:
     *   57 01 02 AA
     */
    public static byte[] bleHandshakePacket() {
        return new byte[] {
                (byte) 0x57, (byte) 0x01, (byte) 0x02, (byte) 0xAA
        };
    }

    /*
     * Rocker packet used by the original native code.
     * For the common two-motor rocker setup, motor/axis A is byte 4 and
     * motor/axis B is the low byte at byte 6. Byte 5 is the high byte of
     * the second integer and is normally zero for values 0..255.
     *
     * MICommand::getHexcheckData XORs bytes 0..17 into byte 18.
     */
    public static byte[] rockerPacket(int axisA, int axisB) {
        axisA = clampByte(axisA);
        axisB = clampWord(axisB);

        byte[] p = new byte[20];
        p[0] = 0x55;
        p[1] = 0x02;
        p[2] = 0x02;
        p[3] = 0x00;
        p[4] = (byte) (axisA & 0xFF);
        p[5] = (byte) ((axisB >> 8) & 0xFF);
        p[6] = (byte) (axisB & 0xFF);
        addXorAndTail(p);
        return p;
    }

    public static byte[] stopPacket() {
        // This matches BlueService::move(128, 128, false, false), which the
        // original joystick screen uses when it returns to centre.
        return rockerPacket(NEUTRAL, NEUTRAL);
    }

    private static void addXorAndTail(byte[] p) {
        int xor = 0;
        for (int i = 0; i <= 17; i++) {
            xor ^= (p[i] & 0xFF);
        }
        p[18] = (byte) xor;
        p[19] = (byte) 0xAA;
    }

    private static int clampByte(int value) {
        return Math.max(0, Math.min(255, value));
    }

    private static int clampWord(int value) {
        return Math.max(0, Math.min(0xFFFF, value));
    }
}
