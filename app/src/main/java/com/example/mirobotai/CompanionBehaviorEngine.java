package com.example.mirobotai;

/**
 * v1.4 Companion behavior layer.
 * Turns mood and situations into companion actions.
 */
public class CompanionBehaviorEngine {

    public enum Action {
        IDLE,
        EXPLORE,
        APPROACH_OWNER,
        WAIT,
        TALK
    }

    private Action currentAction = Action.IDLE;

    public synchronized Action decide(
            int happiness,
            int curiosity,
            int boredom,
            boolean ownerDetected,
            boolean ownerBusy
    ) {
        if (ownerDetected && !ownerBusy && curiosity > 60) {
            currentAction = Action.APPROACH_OWNER;
        } else if (boredom > 70) {
            currentAction = Action.EXPLORE;
        } else {
            currentAction = Action.IDLE;
        }

        return currentAction;
    }

    public synchronized Action getCurrentAction() {
        return currentAction;
    }
}