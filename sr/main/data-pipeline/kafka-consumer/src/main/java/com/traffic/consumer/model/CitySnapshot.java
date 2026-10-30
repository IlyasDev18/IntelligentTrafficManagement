package com.traffic.consumer.model;

import java.util.List;
import java.util.Map;

public class CitySnapshot {
    private String zoneId;
    private String type;
    private long timestamp;
    private String pollutionData;
    private Map<String, String> trafficLightData;
    private Map<String, LaneData> laneData;
    private List<RecommendedAction> recommendedActions;

    // Getters
    public String getZoneId() {
        return zoneId;
    }

    public String getType() {
        return type;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public String getPollutionData() {
        return pollutionData;
    }

    public Map<String, String> getTrafficLightData() {
        return trafficLightData;
    }

    public Map<String, LaneData> getLaneData() {
        return laneData;
    }

    public List<RecommendedAction> getRecommendedActions() {
        return recommendedActions;
    }
}
