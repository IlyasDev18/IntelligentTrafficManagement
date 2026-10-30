package com.city.sensors.camera;

/**
 * Represents a detected event reported by a camera.
 */
public class CameraEvent {

    public enum EventType {
        NORMAL,
        ACCIDENT,
        STOPPED_VEHICLE,
        PEDESTRIAN_ON_ROAD,
        WRONG_WAY,
        DEBRIS_ON_ROAD
    }

    private final EventType type;
    private final String    details;    // human-readable extra info

    public CameraEvent(EventType type, String details) {
        this.type    = type;
        this.details = details;
    }

    public EventType getType()    { return type;    }
    public String    getDetails() { return details; }

    public boolean isAnomalous()  { return type != EventType.NORMAL; }

    @Override
    public String toString() {
        return type + (details != null && !details.isEmpty() ? ": " + details : "");
    }
}
