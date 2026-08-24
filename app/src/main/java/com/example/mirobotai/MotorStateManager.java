package com.example.mirobotai;

/**
 * Keeps movement state separated from AI decisions.
 */
public class MotorStateManager {

    public enum State {
        IDLE,
        MOVING,
        TURNING,
        STOPPING,
        ERROR
    }

    private State state = State.IDLE;

    public synchronized void moving() {
        state = State.MOVING;
    }

    public synchronized void turning() {
        state = State.TURNING;
    }

    public synchronized void stopping() {
        state = State.STOPPING;
    }

    public synchronized void idle() {
        state = State.IDLE;
    }

    public synchronized State getState() {
        return state;
    }
}