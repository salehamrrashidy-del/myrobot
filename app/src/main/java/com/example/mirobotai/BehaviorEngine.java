package com.example.mirobotai;

/**
 * Converts goals into behaviors.
 * AI decides goals; this decides safe behavior.
 */
public class BehaviorEngine {

    public enum Goal {
        IDLE,
        EXPLORE,
        FOLLOW_OWNER,
        TALK,
        STAY_NEAR
    }

    private Goal goal = Goal.IDLE;

    public synchronized void setGoal(Goal newGoal) {
        goal = newGoal;
    }

    public synchronized Goal getGoal() {
        return goal;
    }
}