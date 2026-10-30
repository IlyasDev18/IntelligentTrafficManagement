package com.traffic.consumer.model;

public class LaneData {
    private String flux;
    private int vehicleCount;
    private String noise;
    private String cameraStatus;
    private String cameraDetails;

    // Getters
    public String getFlux() {
        return flux;
    }

    public int getVehicleCount() {
        return vehicleCount;
    }

    public String getNoise() {
        return noise;
    }

    public String getCameraStatus() {
        return cameraStatus;
    }

    public String getCameraDetails() {
        return cameraDetails;
    }
}
