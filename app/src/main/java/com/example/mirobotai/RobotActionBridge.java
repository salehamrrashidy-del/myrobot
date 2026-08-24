package com.example.mirobotai;

/**
 * Converts companion decisions into safe robot actions.
 * Fixed v1.5.1: uses existing MovementGate API.
 */
public class RobotActionBridge {

    private final MovementGate gate;

    public RobotActionBridge(MovementGate gate) {
        this.gate = gate;
    }

    public boolean requestMove(String action) {
        if ("forward".equals(action)) {
            if (!gate.allowForward()) {
                return false;
            }
        }

        // Motor commands should be sent through the existing BLE layer.
        return true;
    }
}