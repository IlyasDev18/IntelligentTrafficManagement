package com.city.sensors.model;

/**
 * Aggregated sensor data for one lane in one snapshot cycle.
 */
public class LaneData {

    private final String laneId;
    private final String flux;
    private final int    vehicleCount;
    private final String noise;
    private final String cameraStatus;
    private final String cameraDetails;   // null when NORMAL

    public LaneData(String laneId, String flux, int vehicleCount,
                    String noise, String cameraStatus, String cameraDetails) {
        this.laneId        = laneId;
        this.flux          = flux;
        this.vehicleCount  = vehicleCount;
        this.noise         = noise;
        this.cameraStatus  = cameraStatus;
        this.cameraDetails = cameraDetails;
    }

    public String getLaneId()        { return laneId;        }
    public String getFlux()          { return flux;          }
    public int    getVehicleCount()  { return vehicleCount;  }
    public String getNoise()         { return noise;         }
    public String getCameraStatus()  { return cameraStatus;  }
    public String getCameraDetails() { return cameraDetails; }
}
