package com.example.mirobotai;

/**
 * v1.3 integration layer.
 * Connects behavior decisions with safety and motor execution.
 */
public class RobotControllerCoordinator {

    private final SafetyEngine safety;
    private final BehaviorEngine behavior;

    public RobotControllerCoordinator(
            SafetyEngine safety,
            BehaviorEngine behavior
    ) {
        this.safety = safety;
        this.behavior = behavior;
    }

    public boolean requestForwardMovement() {
        return safety.allowForward();
    }

    public BehaviorEngine.Goal getCurrentGoal() {
        return behavior.getGoal();
    }
}