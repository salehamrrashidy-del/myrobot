package com.example.mirobotai;

/**
 * Connects mood, behavior and safety decisions.
 * This layer decides intent, not raw motor commands.
 */
public class CompanionBrain {
    private final BehaviorEngine behavior;
    private final SafetyEngine safety;

    public CompanionBrain(BehaviorEngine behavior, SafetyEngine safety) {
        this.behavior = behavior;
        this.safety = safety;
    }

    public BehaviorEngine.Goal decide(float happiness, float curiosity, float boredom) {
        if (safety.mustStop()) {
            return BehaviorEngine.Goal.IDLE;
        }

        if (boredom > 70) {
            return BehaviorEngine.Goal.EXPLORE;
        }

        if (curiosity > 70) {
            return BehaviorEngine.Goal.STAY_NEAR;
        }

        return BehaviorEngine.Goal.IDLE;
    }
}