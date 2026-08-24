package com.example.mirobotai;

/**
 * Final checkpoint before movement commands reach hardware.
 */
public class MovementGate {
    private final SafetyEngine safety;
    private final MotorStateManager motors;

    public MovementGate(SafetyEngine safety, MotorStateManager motors) {
        this.safety = safety;
        this.motors = motors;
    }

    public boolean allowForward() {
        if (!safety.allowForward()) {
            motors.stopping();
            return false;
        }
        return true;
    }
}