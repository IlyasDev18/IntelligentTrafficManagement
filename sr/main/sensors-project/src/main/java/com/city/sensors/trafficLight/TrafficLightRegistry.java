package com.city.sensors.trafficLight;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Manages the 4 traffic lights of the rotary.
 * N/S pair = Phase A   E/W pair = Phase B
 * When Phase A is GREEN, Phase B is RED and vice-versa.
 */
public class TrafficLightRegistry {
    private int remainingGreenTime = 10;

    private final Map<String, TrafficLightSensor> lights = new LinkedHashMap<>();

    public TrafficLightRegistry(String zoneId) {
        // Phase A starts GREEN, Phase B starts RED
        lights.put("NORTH", new TrafficLightSensor("TL-" + zoneId + "-NORTH", "NORTH", TrafficLightSensor.LightState.GREEN));
        lights.put("SOUTH", new TrafficLightSensor("TL-" + zoneId + "-SOUTH", "SOUTH", TrafficLightSensor.LightState.GREEN));
        lights.put("EAST",  new TrafficLightSensor("TL-" + zoneId + "-EAST",  "EAST",  TrafficLightSensor.LightState.RED));
        lights.put("WEST",  new TrafficLightSensor("TL-" + zoneId + "-WEST",  "WEST",  TrafficLightSensor.LightState.RED));
    }

    /** Tick all lights forward. */
    public void update() {
        lights.values().forEach(TrafficLightSensor::update);
    }

    public Map<String, TrafficLightSensor> getLights() { return lights; }

    public TrafficLightSensor get(String direction) { return lights.get(direction); }

    /** Returns a direction→state snapshot (string keys for JSON). */
    public Map<String, String> snapshot() {
        Map<String, String> snap = new LinkedHashMap<>();
        lights.forEach((dir, tl) -> snap.put(tl.getSensorId(), tl.getCurrentState().name()));
        return snap;
    }
    public void extendGreenTime(String direction, int seconds) {
    TrafficLightSensor target = lights.get(direction);

    if (target == null) return;

    // Only extend if it's currently GREEN
    if (target.getCurrentState() == TrafficLightSensor.LightState.GREEN) {
        target.extendGreen(seconds);
    }
} // example default

   public void extendGreen(int extraSeconds) {
    this.remainingGreenTime += extraSeconds;
}
}
