package com.city.sensors.flow;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Registry of flow sensors, one per lane.
 */
public class FlowRegistry {

    private final Map<String, FlowSensor> sensors = new LinkedHashMap<>();

    public FlowRegistry(String zoneId, String[] laneIds) {
        int idx = 1;
        for (String laneId : laneIds) {
            String id = String.format("FLOW-%s-%s-%02d", zoneId, laneId, idx++);
            sensors.put(laneId, new FlowSensor(id, laneId));
        }
    }

    /**
     * Runs a measurement round.
     * @param greenLanes set of lane ids whose feeding traffic light is green.
     */
    public void measure(Set<String> greenLanes) {
        sensors.forEach((laneId, sensor) ->
                sensor.measure(greenLanes.contains(laneId)));
    }

    /** Returns laneId → vehicle count. */
    public Map<String, Integer> vehicleCountSnapshot() {
        Map<String, Integer> snap = new LinkedHashMap<>();
        sensors.forEach((laneId, s) -> snap.put(laneId, s.getVehicleCount()));
        return snap;
    }

    /** Returns laneId → flux level string. */
    public Map<String, String> fluxSnapshot() {
        Map<String, String> snap = new LinkedHashMap<>();
        sensors.forEach((laneId, s) -> snap.put(laneId, s.getFluxLevel().name()));
        return snap;
    }

    public FlowSensor get(String laneId) { return sensors.get(laneId); }
    public Map<String, FlowSensor> getSensors() { return sensors; }
}
