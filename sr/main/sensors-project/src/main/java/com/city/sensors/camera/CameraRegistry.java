package com.city.sensors.camera;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Holds one CameraSensor per lane (8 lanes total).
 */
public class CameraRegistry {

    private final Map<String, CameraSensor> sensors = new LinkedHashMap<>();

    public CameraRegistry(String zoneId, String[] laneIds) {
        int idx = 1;
        for (String laneId : laneIds) {
            String id = String.format("CAM-%s-%s-%02d", zoneId, laneId, idx++);
            sensors.put(laneId, new CameraSensor(id, laneId));
        }
    }

    /** Scan all cameras and return laneId → CameraEvent snapshot. */
    public Map<String, CameraEvent> scan() {
        Map<String, CameraEvent> snap = new LinkedHashMap<>();
        sensors.forEach((laneId, cam) -> snap.put(laneId, cam.scan()));
        return snap;
    }

    public CameraSensor get(String laneId) { return sensors.get(laneId); }
    public Map<String, CameraSensor> getSensors() { return sensors; }
}
