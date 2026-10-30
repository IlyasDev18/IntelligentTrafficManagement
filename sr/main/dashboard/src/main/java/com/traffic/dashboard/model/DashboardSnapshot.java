package com.traffic.dashboard.model;

import java.util.List;
import java.util.Map;

/**
 * Full dashboard payload pushed over WebSocket every tick.
 */
public class DashboardSnapshot {

    private long                 timestamp;
    private String               zoneId;
    private ZoneSummary          zoneSummary;
    private List<LaneStatus>     lanes;
    private List<LightStatus>    trafficLights;
    private PollutionStatus      pollution;
    private List<ActiveAlert>    activeAlerts;
    private List<AnalyticsItem>  analytics;
    private List<Recommendation> recommendations;

    // ── Nested models ────────────────────────────────────────────────────────

    public static class ZoneSummary {
        public String congestionLevel;   // GREEN | YELLOW | ORANGE | RED
        public int    totalVehicles;
        public double avgNoise;
        public int    activeIncidents;
        public int    activeAlertCount;
        public String aqiCategory;
        public double pm25;
    }

    public static class LaneStatus {
        public String  laneId;
        public String  direction;        // NORTH | SOUTH | EAST | WEST
        public String  type;             // IN | OUT
        public String  fluxLevel;        // LOW | OK | HIGH | CONGESTED
        public int     vehicleCount;
        public double  noiseDb;
        public String  cameraStatus;     // NORMAL | ACCIDENT | STOPPED_VEHICLE | …
        public String  cameraDetails;
        public String  statusColor;      // CSS color derived from flux + incident
    }

    public static class LightStatus {
        public String sensorId;
        public String direction;
        public String state;             // RED | YELLOW | GREEN
    }

    public static class PollutionStatus {
        public double pm25;
        public String aqiCategory;
        public String trend;             // RISING | STABLE | FALLING
        public double trendSlope;
    }

    public static class ActiveAlert {
        public long   id;
        public String actionType;
        public String target;
        public String reason;
        public String severity;
        public long   recordedAt;
    }

    public static class AnalyticsItem {
        public String category;
        public String severity;
        public String laneId;
        public String title;
        public String description;
        public Map<String, Object> metrics;
    }

    public static class Recommendation {
        public String severity;
        public String title;
        public String description;
        public String action;
        public String laneId;
    }

    // ── Getters / Setters ────────────────────────────────────────────────────

    public long getTimestamp()                          { return timestamp; }
    public void setTimestamp(long v)                   { this.timestamp = v; }
    public String getZoneId()                          { return zoneId; }
    public void setZoneId(String v)                    { this.zoneId = v; }
    public ZoneSummary getZoneSummary()                { return zoneSummary; }
    public void setZoneSummary(ZoneSummary v)          { this.zoneSummary = v; }
    public List<LaneStatus> getLanes()                 { return lanes; }
    public void setLanes(List<LaneStatus> v)           { this.lanes = v; }
    public List<LightStatus> getTrafficLights()        { return trafficLights; }
    public void setTrafficLights(List<LightStatus> v)  { this.trafficLights = v; }
    public PollutionStatus getPollution()              { return pollution; }
    public void setPollution(PollutionStatus v)        { this.pollution = v; }
    public List<ActiveAlert> getActiveAlerts()         { return activeAlerts; }
    public void setActiveAlerts(List<ActiveAlert> v)   { this.activeAlerts = v; }
    public List<AnalyticsItem> getAnalytics()          { return analytics; }
    public void setAnalytics(List<AnalyticsItem> v)    { this.analytics = v; }
    public List<Recommendation> getRecommendations()   { return recommendations; }
    public void setRecommendations(List<Recommendation> v) { this.recommendations = v; }
}
