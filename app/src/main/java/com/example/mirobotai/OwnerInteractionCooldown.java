package com.example.mirobotai;

/**
 * Prevents the robot from interrupting the owner too often.
 */
public class OwnerInteractionCooldown {

    private long lastInteraction = 0;

    public boolean canInteract(long cooldownMs) {
        return System.currentTimeMillis() - lastInteraction > cooldownMs;
    }

    public void markInteraction() {
        lastInteraction = System.currentTimeMillis();
    }
}