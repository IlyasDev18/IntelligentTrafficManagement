package com.traffic.dashboard.service;

import com.traffic.dashboard.model.DashboardSnapshot;
import com.traffic.dashboard.model.DashboardSnapshot.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Pulls all data needed for the dashboard from traffic_db and assembles
 * a {@link DashboardSnapshot} ready to push to clients.
 */
@Service
public class DashboardDataService {

    private final JdbcTemplate jdbc;

    @Value("${dashboard.zone-id}")
    private String zoneId;

    public DashboardDataService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        System.out.println("[DEBUG] DashboardDataService initialized with zoneId: " + zoneId);
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Build full snapshot
    // ════════════════════════════════════════════════════════════════════════

    public DashboardSnapshot buildSnapshot() {
        System.out.println("[DEBUG] DashboardDataService.buildSnapshot() called");
        DashboardSnapshot snap = new DashboardSnapshot();
        snap.setTimestamp(System.currentTimeMillis());
        snap.setZoneId(zoneId);
        System.out.println("[DEBUG] DashboardDataService.buildSnapshot() - base snapshot created");

        try {
            List<LaneStatus>   lanes  = fetchLaneStatuses();
            PollutionStatus    poll   = fetchPollution();
            List<LightStatus>  lights = fetchTrafficLights();
            List<ActiveAlert>  alerts = fetchActiveAlerts();

            snap.setLanes(lanes);
            snap.setPollution(poll);
            snap.setTrafficLights(lights);
            snap.setActiveAlerts(alerts);
            snap.setZoneSummary(buildSummary(lanes, poll, alerts));
            snap.setAnalytics(fetchRecentAnalytics());
            snap.setRecommendations(fetchRecommendations(lanes, poll, alerts));

            System.out.println("[DEBUG] DashboardDataService.buildSnapshot() - snapshot assembled with:");
            System.out.println("  - Lanes: " + (lanes != null ? lanes.size() : 0));
            System.out.println("  - Pollution: " + (poll != null ? "PM25=" + poll.pm25 : "null"));
            System.out.println("  - Traffic lights: " + (lights != null ? lights.size() : 0));
            System.out.println("  - Alerts: " + (alerts != null ? alerts.size() : 0));
            System.out.println("  - Analytics: " + (snap.getAnalytics() != null ? snap.getAnalytics().size() : 0));
            System.out.println("  - Recommendations: " + (snap.getRecommendations() != null ? snap.getRecommendations().size() : 0));

            return snap;
        } catch (Exception e) {
            System.err.println("[DEBUG] DashboardDataService.buildSnapshot() failed: " + e.getMessage());
            e.printStackTrace();
            throw e;
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Lane statuses — latest reading per lane
    // ════════════════════════════════════════════════════════════════════════

 private List<LaneStatus> fetchLaneStatuses() {
    System.out.println("[DEBUG] DashboardDataService.fetchLaneStatuses() called");
    try {
        // UPDATED: Removed the time filter to allow fetching the 2026 test data
        String sql = """
            SELECT lane_id, flux_level, vehicle_count,
                   noise_db, camera_status, camera_details
            FROM   lane_readings
            WHERE  zone_id = ?
            ORDER  BY recorded_at DESC
            LIMIT  8
            """;

        List<LaneStatus> result = jdbc.query(sql, (rs, i) -> {
            LaneStatus l = new LaneStatus();
            l.laneId        = rs.getString("lane_id");
            l.direction     = l.laneId.contains("-") ? l.laneId.split("-")[0] : l.laneId;
            l.type          = l.laneId.endsWith("-IN") ? "IN" : "OUT";
            l.fluxLevel     = rs.getString("flux_level");
            l.vehicleCount  = rs.getInt("vehicle_count");
            l.noiseDb       = rs.getDouble("noise_db");
            l.cameraStatus  = rs.getString("camera_status");
            l.cameraDetails = rs.getString("camera_details");
            l.statusColor   = deriveStatusColor(l.fluxLevel, l.cameraStatus);
            return l;
        }, zoneId);
        
        System.out.println("[DEBUG] DashboardDataService.fetchLaneStatuses() returned " + result.size() + " lanes");
        return result;
    } catch (Exception e) {
        System.err.println("[DEBUG] DashboardDataService.fetchLaneStatuses() failed: " + e.getMessage());
        e.printStackTrace();
        return Collections.emptyList();
    }
}

    // ════════════════════════════════════════════════════════════════════════
    //  Traffic lights — latest state per sensor
    // ════════════════════════════════════════════════════════════════════════

 private List<LightStatus> fetchTrafficLights() {
    System.out.println("[DEBUG] DashboardDataService.fetchTrafficLights() called");
    try {
        // UPDATED: Filter by sensor_id pattern matching the zone, removed the extra semicolon
        String sql = """
            SELECT sensor_id, state 
            FROM   traffic_light_readings 
            WHERE  sensor_id LIKE ?
            ORDER  BY recorded_at DESC 
            LIMIT  4
            """;

        // Pass the pattern "TL-MAIN-STREET-ROTARY-%" to match the sensor IDs
        String sensorPattern = "TL-" + zoneId + "-%";
        
        List<LightStatus> result = jdbc.query(sql, (rs, i) -> {
            LightStatus l = new LightStatus();
            l.sensorId  = rs.getString("sensor_id");
            l.state     = rs.getString("state");
            String[] parts = l.sensorId.split("-");
            l.direction = parts[parts.length - 1];
            return l;
        }, sensorPattern);
        
        System.out.println("[DEBUG] DashboardDataService.fetchTrafficLights() returned " + result.size() + " lights");
        return result;
    } catch (Exception e) {
        System.err.println("[DEBUG] DashboardDataService.fetchTrafficLights() failed: " + e.getMessage());
        e.printStackTrace();
        return Collections.emptyList();
    }
}

    // ════════════════════════════════════════════════════════════════════════
    //  Pollution — latest + trend
    // ════════════════════════════════════════════════════════════════════════

    private PollutionStatus fetchPollution() {
        System.out.println("[DEBUG] DashboardDataService.fetchPollution() called");
        PollutionStatus p = new PollutionStatus();
        p.pm25 = 0;
        p.aqiCategory = "Unknown";
        p.trend = "STABLE";
        p.trendSlope = 0;

        try {
            String latestSql = """
                SELECT pm25, aqi_category
                FROM   pollution_readings
                WHERE  zone_id = ?
                ORDER  BY recorded_at DESC
                LIMIT  1
                """;

            Map<String, Object> row = jdbc.queryForMap(latestSql, zoneId);
            p.pm25 = ((Number) row.get("pm25")).doubleValue();
            p.aqiCategory = (String) row.get("aqi_category");
            System.out.println("[DEBUG] DashboardDataService.fetchPollution() - latest reading: PM25=" + p.pm25 + ", AQI=" + p.aqiCategory);
        } catch (Exception e) {
            System.err.println("[DEBUG] DashboardDataService.fetchPollution() - no latest reading: " + e.getMessage());
            return p;
        }

        // Calculate trend slope over last 10 readings
        try {
            String trendSql = """
                SELECT pm25
                FROM   pollution_readings
                WHERE  zone_id = ?
                ORDER  BY recorded_at DESC
                LIMIT  10
                """;

            List<Double> readings = jdbc.queryForList(trendSql, Double.class, zoneId);
            System.out.println("[DEBUG] DashboardDataService.fetchPollution() - trend calculation with " + readings.size() + " readings");
            
            if (readings.size() >= 3) {
                // Reverse so oldest-first for slope calculation
                Collections.reverse(readings);
                int n = readings.size();
                double sumX=0,sumY=0,sumXY=0,sumX2=0;
                for (int i = 0; i < n; i++) {
                    double x=i, y=readings.get(i);
                    sumX+=x; sumY+=y; sumXY+=x*y; sumX2+=x*x;
                }
                p.trendSlope = (n*sumXY - sumX*sumY) / (n*sumX2 - sumX*sumX + 1e-9);
                p.trend = p.trendSlope > 1.0 ? "RISING" : p.trendSlope < -1.0 ? "FALLING" : "STABLE";
                System.out.println("[DEBUG] DashboardDataService.fetchPollution() - trend: " + p.trend + ", slope: " + p.trendSlope);
            }
        } catch (Exception e) {
            System.err.println("[DEBUG] DashboardDataService.fetchPollution() - trend calculation failed: " + e.getMessage());
        }
        return p;
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Active alerts
    // ════════════════════════════════════════════════════════════════════════

    private List<ActiveAlert> fetchActiveAlerts() {
        System.out.println("[DEBUG] DashboardDataService.fetchActiveAlerts() called");
        try {
            String sql = """
                SELECT id, action_type, target, reason, severity,
                       UNIX_TIMESTAMP(recorded_at) * 1000 AS recorded_ms
                FROM   alerts
                WHERE  zone_id = ? AND resolved = 0
                ORDER  BY recorded_at DESC
                LIMIT  50
                """;

            List<ActiveAlert> result = jdbc.query(sql, (rs, i) -> {
                ActiveAlert a = new ActiveAlert();
                a.id = rs.getLong("id");
                a.actionType = rs.getString("action_type");
                a.target = rs.getString("target");
                a.reason = rs.getString("reason");
                a.severity = rs.getString("severity");
                a.recordedAt = rs.getLong("recorded_ms");
                return a;
            }, zoneId);
            
            System.out.println("[DEBUG] DashboardDataService.fetchActiveAlerts() returned " + result.size() + " alerts");
            return result;
        } catch (Exception e) {
            System.err.println("[DEBUG] DashboardDataService.fetchActiveAlerts() failed: " + e.getMessage());
            e.printStackTrace();
            return Collections.emptyList();
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Recent analytics results (if analytics_results table exists)
    // ════════════════════════════════════════════════════════════════════════

    private List<AnalyticsItem> fetchRecentAnalytics() {
        System.out.println("[DEBUG] DashboardDataService.fetchRecentAnalytics() called");
        try {
            String sql = """
                SELECT category, severity, lane_id, title, description
                FROM   analytics_results
                WHERE  zone_id = ?
                  AND  generated_at >= NOW() - INTERVAL 1 MINUTE
                ORDER  BY generated_at DESC
                LIMIT  30
                """;
            List<AnalyticsItem> result = jdbc.query(sql, (rs, i) -> {
                AnalyticsItem item = new AnalyticsItem();
                item.category = rs.getString("category");
                item.severity = rs.getString("severity");
                item.laneId = rs.getString("lane_id");
                item.title = rs.getString("title");
                item.description = rs.getString("description");
                item.metrics = Map.of();
                return item;
            }, zoneId);
            
            System.out.println("[DEBUG] DashboardDataService.fetchRecentAnalytics() returned " + result.size() + " items");
            return result;
        } catch (Exception e) {
            // Table may not exist yet — return empty list
            System.err.println("[DEBUG] DashboardDataService.fetchRecentAnalytics() - table may not exist: " + e.getMessage());
            return Collections.emptyList();
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    //  On-the-fly recommendations (derived from current data, no separate table)
    // ════════════════════════════════════════════════════════════════════════

    private List<Recommendation> fetchRecommendations(List<LaneStatus> lanes,
                                                       PollutionStatus poll,
                                                       List<ActiveAlert> alerts) {
        System.out.println("[DEBUG] DashboardDataService.fetchRecommendations() called with " + 
            (lanes != null ? lanes.size() : 0) + " lanes, " + 
            (poll != null ? "pollution data" : "no pollution") + ", " +
            (alerts != null ? alerts.size() : 0) + " alerts");
        
        List<Recommendation> recs = new ArrayList<>();

        try {
            // Incident on any lane
            for (LaneStatus l : lanes) {
                if (!"NORMAL".equals(l.cameraStatus)) {
                    System.out.println("[DEBUG] DashboardDataService.fetchRecommendations() - incident detected on lane: " + l.laneId + ", status: " + l.cameraStatus);
                    Recommendation r = new Recommendation();
                    r.severity = "CRITICAL".equals(l.cameraStatus) ? "CRITICAL" : "HIGH";
                    r.laneId = l.laneId;
                    r.action = incidentAction(l.cameraStatus);
                    r.title = l.cameraStatus + " detected on " + l.laneId;
                    r.description = l.cameraDetails != null ? l.cameraDetails
                                  : "Camera event on lane " + l.laneId;
                    recs.add(r);
                }
            }

            // Congestion
            long congestedCount = lanes.stream()
                .filter(l -> "CONGESTED".equals(l.fluxLevel) || "HIGH".equals(l.fluxLevel))
                .count();
            if (congestedCount >= 4) {
                System.out.println("[DEBUG] DashboardDataService.fetchRecommendations() - zone-wide congestion detected: " + congestedCount + " lanes");
                Recommendation r = new Recommendation();
                r.severity = "CRITICAL";
                r.title = "Zone-wide congestion — activate diversion";
                r.description = congestedCount + " lanes heavily congested. Recommend diversion signs.";
                r.action = "ACTIVATE_DIVERSION";
                recs.add(r);
            }

            // Pollution
            if (poll.pm25 > 150) {
                System.out.println("[DEBUG] DashboardDataService.fetchRecommendations() - hazardous pollution detected: PM25=" + poll.pm25);
                Recommendation r = new Recommendation();
                r.severity = "CRITICAL";
                r.title = "Hazardous air quality — restrict traffic";
                r.description = String.format("PM2.5=%.1f µg/m³. Shorten GREEN phases by 40%%.", poll.pm25);
                r.action = "RESTRICT_TRAFFIC";
                recs.add(r);
            } else if (poll.pm25 > 55) {
                System.out.println("[DEBUG] DashboardDataService.fetchRecommendations() - unhealthy pollution detected: PM25=" + poll.pm25);
                Recommendation r = new Recommendation();
                r.severity = "HIGH";
                r.title = "Very unhealthy air — shorten GREEN cycles 20%";
                r.description = String.format("PM2.5=%.1f µg/m³ exceeds safe threshold.", poll.pm25);
                r.action = "SHORTEN_GREEN";
                recs.add(r);
            }

            // Stale high alerts
            long staleHigh = alerts.stream()
                .filter(a -> "HIGH".equals(a.severity) || "CRITICAL".equals(a.severity))
                .filter(a -> System.currentTimeMillis() - a.recordedAt > 300_000L)
                .count();
            if (staleHigh > 0) {
                System.out.println("[DEBUG] DashboardDataService.fetchRecommendations() - stale high alerts detected: " + staleHigh);
                Recommendation r = new Recommendation();
                r.severity = "HIGH";
                r.title = staleHigh + " unresolved HIGH alerts — escalate to operator";
                r.description = "Alerts have been open for more than 5 minutes without resolution.";
                r.action = "ESCALATE_TO_OPERATOR";
                recs.add(r);
            }

            // Sort: CRITICAL first
            recs.sort(Comparator.comparingInt(rec ->
                "CRITICAL".equals(rec.severity) ? 0 : "HIGH".equals(rec.severity) ? 1 : 2));

            System.out.println("[DEBUG] DashboardDataService.fetchRecommendations() returning " + recs.size() + " recommendations");
            return recs;
        } catch (Exception e) {
            System.err.println("[DEBUG] DashboardDataService.fetchRecommendations() failed: " + e.getMessage());
            e.printStackTrace();
            return Collections.emptyList();
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Zone summary — aggregate KPIs
    // ════════════════════════════════════════════════════════════════════════

    private ZoneSummary buildSummary(List<LaneStatus> lanes, PollutionStatus poll,
                                      List<ActiveAlert> alerts) {
        System.out.println("[DEBUG] DashboardDataService.buildSummary() called");
        ZoneSummary s = new ZoneSummary();
        s.totalVehicles = lanes.stream().mapToInt(l -> l.vehicleCount).sum();
        s.avgNoise = lanes.stream().mapToDouble(l -> l.noiseDb).average().orElse(0);
        s.activeIncidents = (int) lanes.stream().filter(l -> !"NORMAL".equals(l.cameraStatus)).count();
        s.activeAlertCount = alerts.size();
        s.aqiCategory = poll.aqiCategory;
        s.pm25 = poll.pm25;

        long congested = lanes.stream()
            .filter(l -> "CONGESTED".equals(l.fluxLevel) || "HIGH".equals(l.fluxLevel)).count();
        double fraction = lanes.isEmpty() ? 0 : (double) congested / lanes.size();

        s.congestionLevel = fraction >= 0.6 ? "RED"
                          : fraction >= 0.4 ? "ORANGE"
                          : fraction >= 0.2 ? "YELLOW"
                          : "GREEN";
        
        System.out.println("[DEBUG] DashboardDataService.buildSummary() - totalVehicles=" + s.totalVehicles + 
            ", avgNoise=" + s.avgNoise + ", activeIncidents=" + s.activeIncidents + 
            ", activeAlertCount=" + s.activeAlertCount + ", congestionLevel=" + s.congestionLevel);
        
        return s;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String deriveStatusColor(String flux, String camera) {
        System.out.println("[DEBUG] DashboardDataService.deriveStatusColor() called with flux=" + flux + ", camera=" + camera);
        if ("ACCIDENT".equals(camera) || "WRONG_WAY".equals(camera))  return "#ff1744";
        if ("PEDESTRIAN_ON_ROAD".equals(camera))                       return "#ff6d00";
        if ("STOPPED_VEHICLE".equals(camera))                          return "#ffab00";
        String result = switch (flux) {
            case "CONGESTED" -> "#ff1744";
            case "HIGH"      -> "#ff6d00";
            case "OK"        -> "#00e676";
            default          -> "#40c4ff"; // LOW
        };
        System.out.println("[DEBUG] DashboardDataService.deriveStatusColor() returning " + result);
        return result;
    }

    private String incidentAction(String cameraStatus) {
        System.out.println("[DEBUG] DashboardDataService.incidentAction() called with cameraStatus=" + cameraStatus);
        String result = switch (cameraStatus) {
            case "ACCIDENT"           -> "DISPATCH_EMERGENCY";
            case "PEDESTRIAN_ON_ROAD" -> "SET_RED_HALT_TRAFFIC";
            case "STOPPED_VEHICLE"    -> "DISPATCH_TRAFFIC_OFFICER";
            case "WRONG_WAY"          -> "RED_ALL_LIGHTS_BROADCAST";
            default                   -> "MONITOR";
        };
        System.out.println("[DEBUG] DashboardDataService.incidentAction() returning " + result);
        return result;
    }
}