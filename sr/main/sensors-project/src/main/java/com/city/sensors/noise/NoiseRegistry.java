package com.city.sensors.noise;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Holds one NoiseSensor per lane (8 lanes total for the rotary).
 */
public class NoiseRegistry {

    private final Map<String, NoiseSensor> sensors = new LinkedHashMap<>();

    public NoiseRegistry(String zoneId, String[] laneIds) {
        int idx = 1;
        for (String laneId : laneIds) {
            String id = String.format("NOISE-%s-%s-%02d", zoneId, laneId, idx++);
            sensors.put(laneId, new NoiseSensor(id, laneId));
        }
    }

    /** Takes a fresh measurement for every lane using its current vehicle count. */
    public Map<String, String> snapshot(Map<String, Integer> vehicleCountPerLane) {
        Map<String, String> snap = new LinkedHashMap<>();
        sensors.forEach((laneId, sensor) -> {
            int count = vehicleCountPerLane.getOrDefault(laneId, 0);
            sensor.measure(count);
            snap.put(laneId, sensor.formatted());
        });
        return snap;
    }

    public NoiseSensor get(String laneId) { return sensors.get(laneId); }
    public Map<String, NoiseSensor> getSensors() { return sensors; }
}
