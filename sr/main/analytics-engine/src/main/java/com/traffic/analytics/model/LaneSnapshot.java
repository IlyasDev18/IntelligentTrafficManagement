package com.traffic.analytics.model;

import java.time.Instant;

/**
 * One row from {@code lane_readings} or {@code lane_hourly_summary}.
 */
public class LaneSnapshot {

    private final String  zoneId;
    private final String  laneId;
    private final Instant recordedAt;
    private final String  fluxLevel;       // LOW | OK | HIGH | CONGESTED
    private final int     vehicleCount;
    private final double  noiseDb;
    private final String  cameraStatus;    // NORMAL | ACCIDENT | STOPPED_VEHICLE | …
    private final String  cameraDetails;   // nullable

    public LaneSnapshot(String zoneId, String laneId, Instant recordedAt,
                        String fluxLevel, int vehicleCount, double noiseDb,
                        String cameraStatus, String cameraDetails) {
        this.zoneId        = zoneId;
        this.laneId        = laneId;
        this.recordedAt    = recordedAt;
        this.fluxLevel     = fluxLevel;
        this.vehicleCount  = vehicleCount;
        this.noiseDb       = noiseDb;
        this.cameraStatus  = cameraStatus;
        this.cameraDetails = cameraDetails;
    }

    public String  getZoneId()        { return zoneId;        }
    public String  getLaneId()        { return laneId;        }
    public Instant getRecordedAt()    { return recordedAt;    }
    public String  getFluxLevel()     { return fluxLevel;     }
    public int     getVehicleCount()  { return vehicleCount;  }
    public double  getNoiseDb()       { return noiseDb;       }
    public String  getCameraStatus()  { return cameraStatus;  }
    public String  getCameraDetails() { return cameraDetails; }
}
