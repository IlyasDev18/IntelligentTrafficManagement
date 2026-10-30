package com.traffic.bruit.model;

public class NoiseData {
    private String sensorId;       // e.g., "NOISE_SENSOR_B2"
    private double decibels;       // e.g., 75.5
    private long timestamp;

    public String getSensorId() {
        return sensorId;
    }

    public void setSensorId(String sensorId) {
        this.sensorId = sensorId;
    }

    public double getDecibels() {
        return decibels;
    }

    public void setDecibels(double decibels) {
        this.decibels = decibels;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(long timestamp) {
        this.timestamp = timestamp;
    }
}