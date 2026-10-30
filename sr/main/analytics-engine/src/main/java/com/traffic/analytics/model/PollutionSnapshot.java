package com.traffic.analytics.model;

import java.time.Instant;

/**
 * One row from {@code pollution_readings}.
 */
public class PollutionSnapshot {

    private final String  zoneId;
    private final Instant recordedAt;
    private final double  pm25;
    private final String  aqiCategory;

    public PollutionSnapshot(String zoneId, Instant recordedAt, double pm25, String aqiCategory) {
        this.zoneId      = zoneId;
        this.recordedAt  = recordedAt;
        this.pm25        = pm25;
        this.aqiCategory = aqiCategory;
    }

    public String  getZoneId()      { return zoneId;      }
    public Instant getRecordedAt()  { return recordedAt;  }
    public double  getPm25()        { return pm25;        }
    public String  getAqiCategory() { return aqiCategory; }
}
