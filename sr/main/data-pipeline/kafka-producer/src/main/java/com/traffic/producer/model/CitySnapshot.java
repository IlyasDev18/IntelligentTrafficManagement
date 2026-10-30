package com.traffic.producer.model;

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

    // Getters and setters
    public String getZoneId() {
        return zoneId;
    }

    public void setZoneId(String zoneId) {
        this.zoneId = zoneId;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(long timestamp) {
        this.timestamp = timestamp;
    }

    public String getPollutionData() {
        return pollutionData;
    }

    public void setPollutionData(String pollutionData) {
        this.pollutionData = pollutionData;
    }

    public Map<String, String> getTrafficLightData() {
        return trafficLightData;
    }

    public void setTrafficLightData(Map<String, String> trafficLightData) {
        this.trafficLightData = trafficLightData;
    }

    public Map<String, LaneData> getLaneData() {
        return laneData;
    }

    public void setLaneData(Map<String, LaneData> laneData) {
        this.laneData = laneData;
    }

    public List<RecommendedAction> getRecommendedActions() {
        return recommendedActions;
    }

    public void setRecommendedActions(List<RecommendedAction> recommendedActions) {
        this.recommendedActions = recommendedActions;
    }
}
