package com.city.sensors.flow;

import java.util.Random;

/**
 * Flow sensor (inductive loop / radar) that counts vehicles passing a cross-section.
 *
 * Flux levels:
 *   LOW    : 0–10 vehicles / 10 s window
 *   OK     : 11–30 vehicles / 10 s window
 *   HIGH   : 31–50 vehicles / 10 s window
 *   CONGESTED : > 50 vehicles / 10 s window
 */
public class FlowSensor {

    public enum FluxLevel { LOW, OK, HIGH, CONGESTED }

    private final String sensorId;
    private final String laneId;
    private int    lastVehicleCount;
    private double avgSpeed;           // km/h
    private static final Random RNG = new Random();

    public FlowSensor(String sensorId, String laneId) {
        this.sensorId         = sensorId;
        this.laneId           = laneId;
        this.lastVehicleCount = 0;
        this.avgSpeed         = 50.0;
    }

    /**
     * Simulate one measurement window.
     * @param trafficLightGreen whether the feeding traffic light is currently green.
     */
    public void measure(boolean trafficLightGreen) {
        // When green: more vehicles pass through
        int baseCount = trafficLightGreen
                ? 15 + RNG.nextInt(40)   // 15–54
                :  0 + RNG.nextInt(12);  //  0–11 (residual / queue clearing)

        // 5 % chance of a spike event (incident, bus, etc.)
        if (RNG.nextDouble() < 0.05) baseCount += RNG.nextInt(20);

        lastVehicleCount = Math.max(0, baseCount);

        // Speed inversely correlated with density
        double densityFactor = Math.min(1.0, lastVehicleCount / 55.0);
        avgSpeed = 70.0 * (1.0 - 0.6 * densityFactor) + RNG.nextGaussian() * 3.0;
        avgSpeed = Math.max(5.0, Math.min(90.0, avgSpeed));
    }

    public int       getVehicleCount()  { return lastVehicleCount; }
    public double    getAvgSpeed()      { return avgSpeed;          }
    public String    getSensorId()      { return sensorId;          }
    public String    getLaneId()        { return laneId;            }

    public FluxLevel getFluxLevel() {
        if (lastVehicleCount <= 10)  return FluxLevel.LOW;
        if (lastVehicleCount <= 30)  return FluxLevel.OK;
        if (lastVehicleCount <= 50)  return FluxLevel.HIGH;
        return FluxLevel.CONGESTED;
    }

    @Override
    public String toString() {
        return String.format("FlowSensor[%s] lane=%s count=%d flux=%s speed=%.1fkm/h",
                sensorId, laneId, lastVehicleCount, getFluxLevel(), avgSpeed);
    }
}
