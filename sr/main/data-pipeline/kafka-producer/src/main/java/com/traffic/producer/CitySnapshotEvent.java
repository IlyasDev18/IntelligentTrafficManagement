package com.traffic.producer;

// A simple POJO to hold the aggregated city data
public class CitySnapshotEvent {
    private long timestamp;
    private String pollutionData;
    private String trafficFlowData;
    private String cameraSnapshotData;
    private String trafficLightData;

    public CitySnapshotEvent(long timestamp, String pollutionData, String trafficFlowData, String cameraSnapshotData, String trafficLightData) {
        this.timestamp = timestamp;
        this.pollutionData = pollutionData;
        this.trafficFlowData = trafficFlowData;
        this.cameraSnapshotData = cameraSnapshotData;
        this.trafficLightData = trafficLightData;
    }

    // Getters for JSON serialization
    public long getTimestamp() { return timestamp; }
    public String getPollutionData() { return pollutionData; }
    public String getTrafficFlowData() { return trafficFlowData; }
    public String getCameraSnapshotData() { return cameraSnapshotData; }
    public String getTrafficLightData() { return trafficLightData; }
}