package com.example.mirobotai;

/**
 * v1.5 Real Companion Loop
 *
 * Connects perception, emotion, behavior and safety decisions.
 */
public class CompanionLoop {

    public enum RobotMode {
        IDLE,
        COMPANION,
        EXPLORING,
        FOLLOWING,
        SAFE_STOP
    }

    private RobotMode mode = RobotMode.IDLE;

    public synchronized RobotMode update(
            boolean ownerDetected,
            boolean ownerBusy,
            int happiness,
            int curiosity,
            int boredom,
            boolean safeToMove
    ) {

        if (!safeToMove) {
            mode = RobotMode.SAFE_STOP;
            return mode;
        }

        if (ownerDetected && !ownerBusy && curiosity > 50) {
            mode = RobotMode.COMPANION;
        } else if (boredom > 70) {
            mode = RobotMode.EXPLORING;
        } else {
            mode = RobotMode.IDLE;
        }

        return mode;
    }

    public synchronized RobotMode getMode() {
        return mode;
    }
}