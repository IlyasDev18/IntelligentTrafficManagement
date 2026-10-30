package com.city.sensors.model;

import java.util.List;
import java.util.Map;

/**
 * Full sensor snapshot for one measurement cycle of the rotary.
 */
public class RotarySnapshot {

    private final String                   zoneId;
    private final String                   type;
    private final long                     timestamp;
    private final String                   pollutionData;
    private final Map<String, String>      trafficLightData;   // sensorId → state
    private final Map<String, LaneData>    laneData;           // laneId  → LaneData
    private final List<RecommendedAction>  recommendedActions;

    public RotarySnapshot(String zoneId,
                          long timestamp,
                          String pollutionData,
                          Map<String, String> trafficLightData,
                          Map<String, LaneData> laneData,
                          List<RecommendedAction> recommendedActions) {
        this.zoneId             = zoneId;
        this.type               = "rotary";
        this.timestamp          = timestamp;
        this.pollutionData      = pollutionData;
        this.trafficLightData   = trafficLightData;
        this.laneData           = laneData;
        this.recommendedActions = recommendedActions;
    }

    public String                  getZoneId()             { return zoneId;             }
    public String                  getType()               { return type;               }
    public long                    getTimestamp()          { return timestamp;          }
    public String                  getPollutionData()      { return pollutionData;      }
    public Map<String, String>     getTrafficLightData()   { return trafficLightData;   }
    public Map<String, LaneData>   getLaneData()           { return laneData;           }
    public List<RecommendedAction> getRecommendedActions() { return recommendedActions; }
}
