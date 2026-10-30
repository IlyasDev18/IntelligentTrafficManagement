package com.city.sensors.simulator;

import com.city.sensors.camera.CameraEvent;
import com.city.sensors.camera.CameraRegistry;
import com.city.sensors.flow.FlowRegistry;
import com.city.sensors.model.LaneData;
import com.city.sensors.model.RecommendedAction;
import com.city.sensors.model.RotarySnapshot;
import com.city.sensors.noise.NoiseRegistry;
import com.city.sensors.pollution.PollutionSensor;
import com.city.sensors.trafficLight.TrafficLightRegistry;
import com.city.sensors.trafficLight.TrafficLightSensor;

import java.util.*;

/**
 * Central coordinator for all sensors in one rotary.
 *
 * Lane naming convention:
 *   NORTH-IN, SOUTH-IN, EAST-IN, WEST-IN   → approach lanes (entering rotary)
 *   NORTH-OUT, SOUTH-OUT, EAST-OUT, WEST-OUT → exit lanes   (leaving rotary)
 */
public class RotarySimulator {

    // ── Lane identifiers ────────────────────────────────────────────────────
    public static final String[] LANE_IDS = {
        "NORTH-IN", "NORTH-OUT",
        "SOUTH-IN", "SOUTH-OUT",
        "EAST-IN",  "EAST-OUT",
        "WEST-IN",  "WEST-OUT"
    };

    // Map from lane-id to the direction whose traffic light governs it
    private static final Map<String, String> LANE_DIRECTION = new LinkedHashMap<>();
    static {
        LANE_DIRECTION.put("NORTH-IN",  "NORTH");
        LANE_DIRECTION.put("NORTH-OUT", "NORTH");
        LANE_DIRECTION.put("SOUTH-IN",  "SOUTH");
        LANE_DIRECTION.put("SOUTH-OUT", "SOUTH");
        LANE_DIRECTION.put("EAST-IN",   "EAST");
        LANE_DIRECTION.put("EAST-OUT",  "EAST");
        LANE_DIRECTION.put("WEST-IN",   "WEST");
        LANE_DIRECTION.put("WEST-OUT",  "WEST");
    }

    // ── Sensor registries ───────────────────────────────────────────────────
    private final String               zoneId;
    private final TrafficLightRegistry trafficLights;
    private final FlowRegistry         flowSensors;
    private final NoiseRegistry        noiseSensors;
    private final CameraRegistry       cameras;
    private final PollutionSensor      pollutionSensor;

    public RotarySimulator(String zoneId) {
        this.zoneId          = zoneId;
        this.trafficLights   = new TrafficLightRegistry(zoneId);
        this.flowSensors     = new FlowRegistry(zoneId, LANE_IDS);
        this.noiseSensors    = new NoiseRegistry(zoneId, LANE_IDS);
        this.cameras         = new CameraRegistry(zoneId, LANE_IDS);
        this.pollutionSensor = new PollutionSensor("POLL-" + zoneId);
    }

    // ── Core simulation step ─────────────────────────────────────────────────

    /**
     * Runs one measurement cycle and returns a fully populated {@link RotarySnapshot}.
     */
    public RotarySnapshot tick() {

        // 1. Advance traffic light state machine
        trafficLights.update();

        // 2. Determine which lanes have green
        Set<String> greenLanes = new HashSet<>();
        LANE_DIRECTION.forEach((laneId, dir) -> {
            if (trafficLights.get(dir).getCurrentState() == TrafficLightSensor.LightState.GREEN) {
                greenLanes.add(laneId);
            }
        });

        // 3. Flow measurement (depends on traffic light state)
        flowSensors.measure(greenLanes);
        Map<String, Integer> vehicleCounts = flowSensors.vehicleCountSnapshot();
        Map<String, String>  fluxLevels    = flowSensors.fluxSnapshot();

        // 4. Noise measurement (depends on vehicle density)
        Map<String, String> noiseReadings = noiseSensors.snapshot(vehicleCounts);

        // 5. Camera scans
        Map<String, CameraEvent> cameraEvents = cameras.scan();

        // 6. Pollution (aggregate vehicle load)
        int totalVehicles = vehicleCounts.values().stream().mapToInt(Integer::intValue).sum();
        pollutionSensor.measure(totalVehicles);

        // 7. Build lane-level aggregation
        Map<String, LaneData> laneDataMap = new LinkedHashMap<>();
        for (String laneId : LANE_IDS) {
            CameraEvent evt = cameraEvents.get(laneId);
            laneDataMap.put(laneId, new LaneData(
                laneId,
                fluxLevels.getOrDefault(laneId, "OK"),
                vehicleCounts.getOrDefault(laneId, 0),
                noiseReadings.getOrDefault(laneId, "0dB"),
                evt.getType().name(),
                evt.getDetails()
            ));
        }

        // 8. Decision / recommendation engine
        List<RecommendedAction> actions = computeRecommendations(laneDataMap);

        // 9. Build and return snapshot
        return new RotarySnapshot(
            zoneId,
            System.currentTimeMillis(),
            pollutionSensor.toJsonString(),
            trafficLights.snapshot(),
            laneDataMap,
            actions
        );
    }

    // ── Decision engine ──────────────────────────────────────────────────────

    private List<RecommendedAction> computeRecommendations(Map<String, LaneData> laneDataMap) {
        List<RecommendedAction> actions = new ArrayList<>();

        for (Map.Entry<String, LaneData> entry : laneDataMap.entrySet()) {
            String   laneId = entry.getKey();
            LaneData ld     = entry.getValue();
            String   dir    = LANE_DIRECTION.get(laneId);

            switch (ld.getCameraStatus()) {

                case "ACCIDENT" -> {
                    // Red all lights except the opposite direction to clear escape path
                    String opposite = opposite(dir);
                    for (String d : List.of("NORTH","SOUTH","EAST","WEST")) {
                        if (!d.equals(opposite)) {
                            String tlId = "TL-" + zoneId + "-" + d;
                            TrafficLightSensor tl = trafficLights.get(d);
                            if (tl.getCurrentState() != TrafficLightSensor.LightState.RED) {
                                actions.add(new RecommendedAction(
                                    "CHANGE_LIGHT", tlId, "RED",
                                    "ACCIDENT detected on " + laneId + ", clear incoming traffic."
                                ));
                            }
                        }
                    }
                    actions.add(new RecommendedAction(
                        "DISPATCH_UNIT", "EMERGENCY-RESPONSE", "AMBULANCE",
                        "Accident severity detected on lane " + laneId
                    ));
                }

                case "PEDESTRIAN_ON_ROAD" -> {
                    String tlId = "TL-" + zoneId + "-" + dir;
                    TrafficLightSensor tl = trafficLights.get(dir);
                    if (tl.getCurrentState() == TrafficLightSensor.LightState.GREEN) {
                        actions.add(new RecommendedAction(
                            "CHANGE_LIGHT", tlId, "RED",
                            "Pedestrian detected on road in lane " + laneId
                        ));
                    }
                }

                case "CONGESTED" -> {
                    if ("CONGESTED".equals(ld.getFlux())) {
                        String tlId = "TL-" + zoneId + "-" + dir;
                        actions.add(new RecommendedAction(
                            "EXTEND_GREEN", tlId, "GREEN+10s",
                            "Congestion on lane " + laneId + " — extend green window."
                        ));
                    }
                }

                default -> { /* no action needed */ }
            }
        }

        // Pollution alert
        if (pollutionSensor.getAqi() == com.city.sensors.pollution.PollutionSensor.AqiCategory.VeryUnhealthy
         || pollutionSensor.getAqi() == com.city.sensors.pollution.PollutionSensor.AqiCategory.Hazardous) {
            actions.add(new RecommendedAction(
                "ALERT", "ENVIRONMENTAL-CONTROL", "RESTRICT_TRAFFIC",
                String.format("PM2.5 = %.1f µg/m³ — hazardous air quality.", pollutionSensor.getPm25())
            ));
        }

        return actions;
    }

    private static String opposite(String dir) {
        return switch (dir) {
            case "NORTH" -> "SOUTH";
            case "SOUTH" -> "NORTH";
            case "EAST"  -> "WEST";
            default      -> "EAST";
        };
    }

    // ── Accessors ────────────────────────────────────────────────────────────

    public String getZoneId() { return zoneId; }
}
