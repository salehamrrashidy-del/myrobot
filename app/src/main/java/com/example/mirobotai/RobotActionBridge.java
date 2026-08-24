package com.example.mirobotai;

/**
 * Converts companion decisions into safe robot actions.
 */
public class RobotActionBridge {

    private final MovementGate gate;

    public RobotActionBridge(MovementGate gate) {
        this.gate = gate;
    }

    public boolean requestMove(String action) {
        if (!gate.canMove()) {
            return false;
        }

        // Motor commands should be sent through the existing BLE layer.
        return true;
    }
}