package com.example.mirobotai;

import java.util.LinkedList;
import java.util.Queue;

/**
 * Prevents conflicting motor commands.
 */
public class MotorCommandQueue {

    private final Queue<String> commands = new LinkedList<>();

    public synchronized void add(String command) {
        commands.offer(command);
    }

    public synchronized String next() {
        return commands.poll();
    }

    public synchronized void clear() {
        commands.clear();
    }
}