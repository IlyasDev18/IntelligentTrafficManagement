package com.city.sensors.noise;

import java.util.Random;

/**
 * Noise sensor mounted at the roadside of each lane.
 * Generates a decibel reading influenced by simulated vehicle density.
 *
 *  Baseline urban quiet  : ~45 dB
 *  Normal traffic         : 55–65 dB
 *  Heavy / incident       : 70–85 dB
 */
public class NoiseSensor {

    private final String sensorId;
    private final String laneId;
    private double currentDb;
    private static final Random RNG = new Random();

    // Noise profile thresholds
    public enum NoiseLevel { LOW, MODERATE, HIGH, CRITICAL }

    public NoiseSensor(String sensorId, String laneId) {
        this.sensorId  = sensorId;
        this.laneId    = laneId;
        this.currentDb = 50.0 + RNG.nextGaussian() * 5.0;
    }

    /**
     * Simulates a new reading.
     * @param vehicleCount current vehicle count in this lane (influences noise).
     */
    public double measure(int vehicleCount) {
        // Base noise from vehicle density
        double base   = 45.0 + (vehicleCount * 0.55);
        // Random event spike (10 % chance: horn, brake screech, etc.)
        double spike  = (RNG.nextDouble() < 0.10) ? 5.0 + RNG.nextDouble() * 15.0 : 0.0;
        // Gaussian environmental variation ±3 dB
        double jitter = RNG.nextGaussian() * 3.0;

        currentDb = Math.max(35.0, Math.min(95.0, base + spike + jitter));
        return currentDb;
    }

    public double getCurrentDb()  { return currentDb; }
    public String formatted()     { return String.format("%.1fdB", currentDb); }
    public String getSensorId()   { return sensorId;  }
    public String getLaneId()     { return laneId;    }

    public NoiseLevel getLevel() {
        if (currentDb < 55) return NoiseLevel.LOW;
        if (currentDb < 65) return NoiseLevel.MODERATE;
        if (currentDb < 75) return NoiseLevel.HIGH;
        return NoiseLevel.CRITICAL;
    }

    @Override
    public String toString() {
        return String.format("NoiseSensor[%s] lane=%s reading=%s level=%s",
                sensorId, laneId, formatted(), getLevel());
    }
}
