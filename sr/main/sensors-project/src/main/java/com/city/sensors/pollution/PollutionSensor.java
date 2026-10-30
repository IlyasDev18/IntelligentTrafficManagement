package com.city.sensors.pollution;

import java.util.Random;

/**
 * Environmental pollution sensor placed at the centre of the rotary.
 * Measures PM2.5 particulate concentration and derives an AQI category.
 *
 * AQI categories (simplified EPA scale):
 *   Good        :   0–12   µg/m³
 *   Moderate    :  12–35   µg/m³
 *   Unhealthy   :  35–55   µg/m³
 *   Very Unhealthy: 55–150 µg/m³
 *   Hazardous   : >150     µg/m³
 */
public class PollutionSensor {

    public enum AqiCategory { Good, Moderate, Unhealthy, VeryUnhealthy, Hazardous }

    private final String sensorId;
    private double pm25;
    private static final Random RNG = new Random();

    public PollutionSensor(String sensorId) {
        this.sensorId = sensorId;
        this.pm25     = 15.0 + RNG.nextDouble() * 20.0;
    }

    /**
     * Takes a new reading.
     * @param totalVehicles combined vehicle count across all lanes (drives pollution).
     */
    public void measure(int totalVehicles) {
        double base   = 8.0 + (totalVehicles * 0.35);
        double jitter = RNG.nextGaussian() * 4.0;
        // 3 % chance of emission peak (idling heavy vehicles, wind pocket, etc.)
        double peak   = (RNG.nextDouble() < 0.03) ? RNG.nextDouble() * 60.0 : 0.0;

        pm25 = Math.max(1.0, Math.min(250.0, base + jitter + peak));
    }

    public double       getPm25()       { return pm25;      }
    public String       getSensorId()   { return sensorId;  }

    public AqiCategory getAqi() {
        if (pm25 <= 12)  return AqiCategory.Good;
        if (pm25 <= 35)  return AqiCategory.Moderate;
        if (pm25 <= 55)  return AqiCategory.Unhealthy;
        if (pm25 <= 150) return AqiCategory.VeryUnhealthy;
        return AqiCategory.Hazardous;
    }

    /** JSON-ready string matching the sample format. */
    public String toJsonString() {
        return String.format(
            "{\"sensorId\":\"%s\",\"pm25Level\":%.1f,\"aqi\":\"%s\"}",
            sensorId, pm25, getAqi());
    }

    @Override
    public String toString() {
        return String.format("PollutionSensor[%s] PM2.5=%.1f µg/m³ AQI=%s",
                sensorId, pm25, getAqi());
    }
}
