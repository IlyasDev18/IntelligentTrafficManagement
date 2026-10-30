package com.traffic.analytics.db;

import com.traffic.analytics.model.AlertRecord;
import com.traffic.analytics.model.LaneSnapshot;
import com.traffic.analytics.model.PollutionSnapshot;
import com.zaxxer.hikari.HikariDataSource;

import java.sql.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Read-only repository: pulls data from the traffic_db tables produced by
 * the database-access layer.
 *
 * All methods are stateless and safe to call from multiple threads.
 */
public class AnalyticsRepository {

    private final HikariDataSource ds;

    public AnalyticsRepository(HikariDataSource ds) { this.ds = ds; }

    // ════════════════════════════════════════════════════════════════════════
    //  Lane readings
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Returns raw lane readings for a zone recorded in the last {@code minutes}.
     * UPDATED: Anchors window to MAX(recorded_at) to support historical/test data.
     */
    public List<LaneSnapshot> getRecentLaneReadings(String zoneId, int minutes) throws SQLException {
        String sql = """
            SELECT zone_id, lane_id, recorded_at, flux_level,
                   vehicle_count, noise_db, camera_status, camera_details
            FROM   lane_readings
            WHERE  zone_id = ?
              AND  recorded_at >= (
                       SELECT MAX(recorded_at) 
                       FROM   lane_readings 
                       WHERE  zone_id = ?
                   ) - INTERVAL ? MINUTE
            ORDER  BY lane_id, recorded_at
            """;
        // Pass zoneId twice: once for main WHERE, once for subquery
        return queryLanes(sql, zoneId, zoneId, minutes);
    }

    /**
     * Returns the single most-recent reading per lane for a zone.
     * (No changes needed here, logic is already relative to data)
     */
    public List<LaneSnapshot> getLatestPerLane(String zoneId) throws SQLException {
        String sql = """
            SELECT lr.zone_id, lr.lane_id, lr.recorded_at, lr.flux_level,
                   lr.vehicle_count, lr.noise_db, lr.camera_status, lr.camera_details
            FROM   lane_readings lr
            INNER JOIN (
                SELECT lane_id, MAX(recorded_at) AS max_ts
                FROM   lane_readings
                WHERE  zone_id = ?
                GROUP  BY lane_id
            ) latest ON lr.lane_id = latest.lane_id AND lr.recorded_at = latest.max_ts
            WHERE  lr.zone_id = ?
            ORDER  BY lr.lane_id
            """;
        return queryLanes(sql, zoneId, zoneId);
    }

    /**
     * Returns hourly averages for all lanes in the past {@code hours}.
     * UPDATED: Anchors to MAX(hour_bucket).
     */
    public List<LaneSnapshot> getHourlySummaries(String zoneId, int hours) throws SQLException {
        String sql = """
            SELECT zone_id, lane_id, hour_bucket AS recorded_at,
                   'HOURLY' AS flux_level,
                   CAST(avg_vehicles AS UNSIGNED) AS vehicle_count,
                   avg_noise_db AS noise_db,
                   CONCAT('ACCIDENTS:', accident_count) AS camera_status,
                   CONCAT('samples=', sample_count,
                          ' max_v=', max_vehicles,
                          ' min_v=', min_vehicles) AS camera_details
            FROM   lane_hourly_summary
            WHERE  zone_id = ?
              AND  hour_bucket >= (
                       SELECT MAX(hour_bucket) 
                       FROM   lane_hourly_summary 
                       WHERE  zone_id = ?
                   ) - INTERVAL ? HOUR
            ORDER  BY lane_id, hour_bucket
            """;
        return queryLanes(sql, zoneId, zoneId, hours);
    }

    /**
     * Returns lane readings for a specific lane over a window.
     * UPDATED: Anchors to MAX(recorded_at).
     */
    public List<LaneSnapshot> getLaneHistory(String zoneId, String laneId, int minutes)
            throws SQLException {
        String sql = """
            SELECT zone_id, lane_id, recorded_at, flux_level,
                   vehicle_count, noise_db, camera_status, camera_details
            FROM   lane_readings
            WHERE  zone_id = ? AND lane_id = ?
              AND  recorded_at >= (
                       SELECT MAX(recorded_at) 
                       FROM   lane_readings 
                       WHERE  zone_id = ? AND lane_id = ?
                   ) - INTERVAL ? MINUTE
            ORDER  BY recorded_at
            """;
        return queryLanes(sql, zoneId, laneId, zoneId, laneId, minutes);
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Pollution readings
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Returns pollution readings for the last {@code minutes}.
     * UPDATED: Anchors to MAX(recorded_at).
     */
    public List<PollutionSnapshot> getRecentPollution(String zoneId, int minutes)
            throws SQLException {
        String sql = """
            SELECT zone_id, recorded_at, pm25, aqi_category
            FROM   pollution_readings
            WHERE  zone_id = ?
              AND  recorded_at >= (
                       SELECT MAX(recorded_at) 
                       FROM   pollution_readings 
                       WHERE  zone_id = ?
                   ) - INTERVAL ? MINUTE
            ORDER  BY recorded_at
            """;
        List<PollutionSnapshot> list = new ArrayList<>();
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, zoneId);
            ps.setString(2, zoneId); // param for subquery
            ps.setInt(3, minutes);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(new PollutionSnapshot(
                        rs.getString("zone_id"),
                        rs.getTimestamp("recorded_at").toInstant(),
                        rs.getDouble("pm25"),
                        rs.getString("aqi_category")
                    ));
                }
            }
        }
        return list;
    }

    /**
     * Returns the single most-recent pollution reading for a zone.
     * (No changes needed)
     */
    public PollutionSnapshot getLatestPollution(String zoneId) throws SQLException {
        String sql = """
            SELECT zone_id, recorded_at, pm25, aqi_category
            FROM   pollution_readings
            WHERE  zone_id = ?
            ORDER  BY recorded_at DESC
            LIMIT  1
            """;
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, zoneId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return new PollutionSnapshot(
                        rs.getString("zone_id"),
                        rs.getTimestamp("recorded_at").toInstant(),
                        rs.getDouble("pm25"),
                        rs.getString("aqi_category")
                    );
                }
            }
        }
        return null;
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Alert records
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Returns all unresolved alerts for a zone.
     * (No changes needed - independent of time window)
     */
    public List<AlertRecord> getActiveAlerts(String zoneId) throws SQLException {
        String sql = """
            SELECT id, zone_id, action_type, target, new_value,
                   reason, severity, resolved, recorded_at
            FROM   alerts
            WHERE  zone_id = ? AND resolved = 0
            ORDER  BY recorded_at DESC
            LIMIT  500
            """;
        return queryAlerts(sql, zoneId);
    }

    /**
     * Returns alerts (any status) from the last {@code minutes}.
     * UPDATED: Anchors to MAX(recorded_at).
     */
    public List<AlertRecord> getRecentAlerts(String zoneId, int minutes) throws SQLException {
        String sql = """
            SELECT id, zone_id, action_type, target, new_value,
                   reason, severity, resolved, recorded_at
            FROM   alerts
            WHERE  zone_id = ?
              AND  recorded_at >= (
                       SELECT MAX(recorded_at) 
                       FROM   alerts 
                       WHERE  zone_id = ?
                   ) - INTERVAL ? MINUTE
            ORDER  BY recorded_at DESC
            """;
        return queryAlerts(sql, zoneId, zoneId, minutes);
    }

    /**
     * Returns HIGH/CRITICAL severity alerts from the last {@code minutes}.
     * UPDATED: Anchors to MAX(recorded_at).
     */
    public List<AlertRecord> getHighSeverityAlerts(String zoneId, int minutes) throws SQLException {
        String sql = """
            SELECT id, zone_id, action_type, target, new_value,
                   reason, severity, resolved, recorded_at
            FROM   alerts
            WHERE  zone_id  = ?
              AND  severity IN ('HIGH','CRITICAL')
              AND  recorded_at >= (
                       SELECT MAX(recorded_at) 
                       FROM   alerts 
                       WHERE  zone_id = ?
                   ) - INTERVAL ? MINUTE
            ORDER  BY recorded_at DESC
            """;
        return queryAlerts(sql, zoneId, zoneId, minutes);
    }

    /**
     * Returns alert frequency per action_type for the past {@code hours}.
     * UPDATED: Anchors to MAX(hour_bucket).
     */
    public List<AlertRecord> getAlertFrequency(String zoneId, int hours) throws SQLException {
        String sql = """
            SELECT 0 AS id, zone_id,
                   action_type, CAST(alert_count AS CHAR) AS target,
                   CAST(high_count AS CHAR) AS new_value,
                   hour_bucket AS reason,
                   'STATS'     AS severity,
                   0           AS resolved,
                   hour_bucket AS recorded_at
            FROM   alert_stats
            WHERE  zone_id = ?
              AND  hour_bucket >= (
                       SELECT MAX(hour_bucket) 
                       FROM   alert_stats 
                       WHERE  zone_id = ?
                   ) - INTERVAL ? HOUR
            ORDER  BY hour_bucket DESC, alert_count DESC
            """;
        return queryAlerts(sql, zoneId, zoneId, hours);
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Generic helpers
    // ════════════════════════════════════════════════════════════════════════

    private List<LaneSnapshot> queryLanes(String sql, Object... params) throws SQLException {
        List<LaneSnapshot> list = new ArrayList<>();
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) ps.setObject(i + 1, params[i]);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(new LaneSnapshot(
                        rs.getString("zone_id"),
                        rs.getString("lane_id"),
                        rs.getTimestamp("recorded_at").toInstant(),
                        rs.getString("flux_level"),
                        rs.getInt("vehicle_count"),
                        rs.getDouble("noise_db"),
                        rs.getString("camera_status"),
                        rs.getString("camera_details")
                    ));
                }
            }
        }
        return list;
    }

    private List<AlertRecord> queryAlerts(String sql, Object... params) throws SQLException {
        List<AlertRecord> list = new ArrayList<>();
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) ps.setObject(i + 1, params[i]);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Timestamp ts = rs.getTimestamp("recorded_at");
                    list.add(new AlertRecord(
                        rs.getLong("id"),
                        rs.getString("zone_id"),
                        rs.getString("action_type"),
                        rs.getString("target"),
                        rs.getString("new_value"),
                        rs.getString("reason"),
                        rs.getString("severity"),
                        rs.getBoolean("resolved"),
                        ts != null ? ts.toInstant() : Instant.now()
                    ));
                }
            }
        }
        return list;
    }
}