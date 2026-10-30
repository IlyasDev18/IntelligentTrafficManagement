package com.city.sensors.camera;

import java.util.Random;

/**
 * Simulated CCTV / smart camera sensor for one lane.
 * Normally reports NORMAL status; occasionally detects incidents.
 *
 * Event probabilities per 10 s window (can be tuned):
 *   NORMAL             : 82 %
 *   STOPPED_VEHICLE    :  8 %
 *   PEDESTRIAN_ON_ROAD :  5 %
 *   WRONG_WAY          :  2 %
 *   ACCIDENT           :  2 %
 *   DEBRIS_ON_ROAD     :  1 %
 */
public class CameraSensor {

    private final String sensorId;
    private final String laneId;
    private CameraEvent  lastEvent;
    private static final Random RNG = new Random();

    private static final double[] THRESHOLDS = { 0.82, 0.90, 0.95, 0.97, 0.99, 1.00 };
    private static final CameraEvent.EventType[] EVENTS = {
        CameraEvent.EventType.NORMAL,
        CameraEvent.EventType.STOPPED_VEHICLE,
        CameraEvent.EventType.PEDESTRIAN_ON_ROAD,
        CameraEvent.EventType.WRONG_WAY,
        CameraEvent.EventType.ACCIDENT,
        CameraEvent.EventType.DEBRIS_ON_ROAD
    };

    public CameraSensor(String sensorId, String laneId) {
        this.sensorId  = sensorId;
        this.laneId    = laneId;
        this.lastEvent = new CameraEvent(CameraEvent.EventType.NORMAL, null);
    }

    /** Run the camera analysis frame for this window. */
    public CameraEvent scan() {
        double roll = RNG.nextDouble();
        CameraEvent.EventType type = CameraEvent.EventType.NORMAL;
        for (int i = 0; i < THRESHOLDS.length; i++) {
            if (roll < THRESHOLDS[i]) { type = EVENTS[i]; break; }
        }

        String details = generateDetails(type);
        lastEvent = new CameraEvent(type, details);
        return lastEvent;
    }

    private String generateDetails(CameraEvent.EventType type) {
        return switch (type) {
            case NORMAL             -> null;
            case ACCIDENT           -> String.format("Severity: %s, Location: %dm into lane",
                                            severity(), 10 + RNG.nextInt(90));
            case STOPPED_VEHICLE    -> String.format("Duration: %dm %ds",
                                            RNG.nextInt(5), RNG.nextInt(60));
            case PEDESTRIAN_ON_ROAD -> "Detected at " + pedestrianPos();
            case WRONG_WAY          -> String.format("Speed: %d km/h, %dm from entry",
                                            20 + RNG.nextInt(60), RNG.nextInt(50));
            case DEBRIS_ON_ROAD     -> String.format("Object size: %s, lane coverage: %d%%",
                                            objectSize(), 10 + RNG.nextInt(60));
        };
    }

    private String severity()      { return new String[]{"LOW","MEDIUM","HIGH"}[RNG.nextInt(3)]; }
    private String pedestrianPos() { return new String[]{"centre of lane","edge of lane","crossing mid-lane"}[RNG.nextInt(3)]; }
    private String objectSize()    { return new String[]{"small","medium","large"}[RNG.nextInt(3)]; }

    public CameraEvent getLastEvent() { return lastEvent;  }
    public String      getSensorId()  { return sensorId;   }
    public String      getLaneId()    { return laneId;     }

    @Override
    public String toString() {
        return String.format("Camera[%s] lane=%s event=%s", sensorId, laneId, lastEvent);
    }
}
