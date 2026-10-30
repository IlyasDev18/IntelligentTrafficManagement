package com.city.sensors.trafficLight;

import java.util.Random;

/**
 * Simulates a traffic light sensor at a rotary entry.
 * Each of the 4 directions has one traffic light.
 * States: RED, YELLOW, GREEN — cycled in a coordinated way.
 */
public class TrafficLightSensor {

    public enum LightState { RED, YELLOW, GREEN }

    private final String sensorId;
    private final String direction;   // NORTH | SOUTH | EAST | WEST
    private LightState currentState;
    private long lastChangedAt;
    private static final Random RNG = new Random();

    // Green/red durations in milliseconds (simulated)
    private static final long GREEN_DURATION  = 20_000L;
    private static final long YELLOW_DURATION =  3_000L;
    private static final long RED_DURATION    = 23_000L;   // green + yellow of the other phase

    public TrafficLightSensor(String sensorId, String direction, LightState initialState) {
        this.sensorId     = sensorId;
        this.direction    = direction;
        this.currentState = initialState;
        this.lastChangedAt = System.currentTimeMillis();
    }

    /**
     * Advances the state machine based on elapsed time.
     * N/S pair share one phase, E/W share the other.
     */
    public void update() {
        long now     = System.currentTimeMillis();
        long elapsed = now - lastChangedAt;

        switch (currentState) {
            case GREEN  -> { if (elapsed >= GREEN_DURATION)  transition(LightState.YELLOW, now); }
            case YELLOW -> { if (elapsed >= YELLOW_DURATION) transition(LightState.RED,    now); }
            case RED    -> { if (elapsed >= RED_DURATION)    transition(LightState.GREEN,   now); }
        }
    }

    /** Force a specific state (used by coordinator / recommendation engine). */
    public void forceState(LightState state) {
        transition(state, System.currentTimeMillis());
    }

    /** Extend the green light duration by adding time to the current green phase. */
    public void extendGreen(int additionalSeconds) {
        if (currentState == LightState.GREEN) {
            // Convert seconds to milliseconds and subtract from lastChangedAt
            // This makes the green phase appear to have started earlier
            long additionalMs = additionalSeconds * 1000L;
            lastChangedAt -= additionalMs;
        }
    }

    private void transition(LightState next, long now) {
        currentState  = next;
        lastChangedAt = now;
    }

    // ── Accessors ────────────────────────────────────────────────────────────

    public String     getSensorId()    { return sensorId;     }
    public String     getDirection()   { return direction;    }
    public LightState getCurrentState(){ return currentState; }

    @Override
    public String toString() {
        return String.format("TrafficLight[%s] direction=%s state=%s",
                sensorId, direction, currentState);
    }
}