package com.example.mirobotai;

public final class MiRobotProtocol {
    private MiRobotProtocol() {}

    public static final int NEUTRAL = 128;

    // Recovered from the original Mi Robot Builder 1.5.0 native library:
    // MIConnectCommand::MIConnectCommand()
    public static byte[] connectPacket() {
        byte[] p = new byte[20];
        p[0] = 0x55;
        p[1] = 0x01;
        p[2] = 0x01;
        p[3] = 0x00;
        p[19] = (byte) 0xAA;
        return p;
    }

    // Recovered from MIRockerCommand::MIRockerCommand(int, int).
    // Values are centered around 128. 128/128 = stop.
    public static byte[] rockerPacket(int motorA, int motorB) {
        motorA = clampByte(motorA);
        motorB = clampWord(motorB);

        byte[] p = new byte[20];
        p[0] = 0x55;
        p[1] = 0x02;
        p[2] = 0x02;
        p[3] = 0x00;
        p[4] = (byte) (motorA & 0xFF);
        p[5] = (byte) ((motorB >> 8) & 0xFF);
        p[6] = (byte) (motorB & 0xFF);
        p[19] = (byte) 0xAA;
        return p;
    }

    public static byte[] stopPacket() {
        return rockerPacket(NEUTRAL, NEUTRAL);
    }

    private static int clampByte(int value) {
        return Math.max(0, Math.min(255, value));
    }

    private static int clampWord(int value) {
        return Math.max(0, Math.min(0xFFFF, value));
    }
}
