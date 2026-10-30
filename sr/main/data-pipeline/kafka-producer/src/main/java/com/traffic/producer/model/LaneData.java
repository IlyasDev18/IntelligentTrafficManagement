package com.traffic.producer.model;

public class LaneData {
    private String flux;
    private int vehicleCount;
    private String noise;
    private String cameraStatus;
    private String cameraDetails;

    // Getters and setters
    public String getFlux() {
        return flux;
    }

    public void setFlux(String flux) {
        this.flux = flux;
    }

    public int getVehicleCount() {
        return vehicleCount;
    }

    public void setVehicleCount(int vehicleCount) {
        this.vehicleCount = vehicleCount;
    }

    public String getNoise() {
        return noise;
    }

    public void setNoise(String noise) {
        this.noise = noise;
    }

    public String getCameraStatus() {
        return cameraStatus;
    }

    public void setCameraStatus(String cameraStatus) {
        this.cameraStatus = cameraStatus;
    }

    public String getCameraDetails() {
        return cameraDetails;
    }

    public void setCameraDetails(String cameraDetails) {
        this.cameraDetails = cameraDetails;
    }
}
