package com.example.mirobotai;

/**
 * Local safety layer. It sits between decisions and motors.
 * Gemini/AI must never bypass this class.
 */
public class SafetyEngine {

    public enum State {
        SAFE,
        CAUTION,
        EMERGENCY_STOP
    }

    private boolean edgeDetected = false;
    private boolean obstacleDetected = false;
    private State state = State.SAFE;

    public synchronized void update(boolean edge, boolean obstacle) {
        edgeDetected = edge;
        obstacleDetected = obstacle;

        if (edge || obstacle) {
            state = State.EMERGENCY_STOP;
        } else {
            state = State.SAFE;
        }
    }

    public synchronized boolean allowForward() {
        return state == State.SAFE;
    }

    public synchronized boolean mustStop() {
        return state == State.EMERGENCY_STOP;
    }

    public synchronized State getState() {
        return state;
    }
}