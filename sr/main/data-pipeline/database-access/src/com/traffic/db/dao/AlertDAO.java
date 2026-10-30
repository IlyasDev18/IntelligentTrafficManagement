package com.traffic.db.dao;

import com.city.sensors.model.RecommendedAction;
import com.city.sensors.model.RotarySnapshot;
import javax.sql.DataSource;

import java.sql.*;
import java.util.*;

/**
 * Data-Access Object for alerts and recommended actions.
 *
 * ── Schema ────────────────────────────────────────────────────────────────
 *
 *  alerts
 *  ──────
 *  One row per RecommendedAction per snapshot.
 *  Partitioned by RANGE on recorded_at (same monthly strategy as raw tables).
 *
 *  alert_stats   (non-partitioned roll-up)
 *  ──────────────────────────────────────
 *  Hourly count of each action_type per zone — used by dashboards to show
 *  alert frequency trends without scanning the raw partition table.
 *
 * ─────────────────────────────────────────────────────────────────────────
 */
public class AlertDAO {

    private final DataSource ds;

    // Alert severity mapping (derived from action type)
    private static final Map<String, String> SEVERITY_MAP = Map.of(
        "CHANGE_LIGHT",    "MEDIUM",
        "DISPATCH_UNIT",   "HIGH",
        "EXTEND_GREEN",    "LOW",
        "ALERT",           "HIGH",
        "RESTRICT_TRAFFIC","HIGH"
    );

    // ── Constructor ──────────────────────────────────────────────────────────

    public AlertDAO(DataSource dataSource) {
        this.ds = dataSource;
    }

    // ════════════════════════════════════════════════════════════════════════
    //  DDL
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Creates alert tables if they do not exist.
     * Call once at start-up after {@code TrafficDataDAO.initSchema()}.
     */
    public void initSchema() throws SQLException {
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {

            // ── Raw alerts table (partitioned by month) ───────────────────
            st.execute("""
                CREATE TABLE IF NOT EXISTS alerts (
                    id             BIGINT        NOT NULL AUTO_INCREMENT,
                    snapshot_id    BIGINT        NOT NULL,
                    zone_id        VARCHAR(64)   NOT NULL,
                    action_type    VARCHAR(32)   NOT NULL,
                    target         VARCHAR(64)   NOT NULL,
                    new_value      VARCHAR(32),
                    reason         TEXT          NOT NULL,
                    severity       VARCHAR(8)    NOT NULL DEFAULT 'MEDIUM',
                    resolved       TINYINT(1)    NOT NULL DEFAULT 0,
                    resolved_at    DATETIME(3),
                    recorded_at    DATETIME(3)   NOT NULL,
                    PRIMARY KEY (id, recorded_at),
                    INDEX idx_alert_zone_time   (zone_id, recorded_at),
                    INDEX idx_alert_unresolved  (zone_id, resolved, recorded_at),
                    INDEX idx_alert_target      (target, recorded_at)
                )
                ENGINE=InnoDB
                PARTITION BY RANGE (TO_DAYS(recorded_at)) (
                    PARTITION p_initial VALUES LESS THAN (TO_DAYS('2025-01-01'))
                );
                """);

            // ── Hourly alert frequency stats ──────────────────────────────
            st.execute("""
                CREATE TABLE IF NOT EXISTS alert_stats (
                    id           BIGINT       NOT NULL AUTO_INCREMENT,
                    zone_id      VARCHAR(64)  NOT NULL,
                    action_type  VARCHAR(32)  NOT NULL,
                    hour_bucket  DATETIME     NOT NULL,
                    alert_count  INT          NOT NULL DEFAULT 0,
                    high_count   INT          NOT NULL DEFAULT 0,
                    PRIMARY KEY (id),
                    UNIQUE KEY uq_stats_hour (zone_id, action_type, hour_bucket),
                    INDEX idx_stats_zone_hour (zone_id, hour_bucket)
                ) ENGINE=InnoDB;
                """);

            ensurePartition(c, java.time.LocalDate.now());
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Partition management
    // ════════════════════════════════════════════════════════════════════════

    public void ensurePartition(Connection c, java.time.LocalDate date) {
        java.time.LocalDate next = date.withDayOfMonth(1).plusMonths(1);
        String partName = String.format("p_%04d_%02d", date.getYear(), date.getMonthValue());
        String bound    = next.toString();
        String sql = "ALTER TABLE alerts ADD PARTITION (PARTITION %s VALUES LESS THAN (TO_DAYS('%s')))";
        try (Statement st = c.createStatement()) {
            st.execute(String.format(sql, partName, bound));
        } catch (SQLException e) {
            if (e.getErrorCode() != 1517 && e.getErrorCode() != 1493) {
                System.err.printf("[WARN] ensurePartition alerts/%s: %s%n", partName, e.getMessage());
            }
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    //  INSERT
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Persists all {@link RecommendedAction}s from a snapshot in one batch.
     * Skips gracefully if the list is empty.
     *
     * @param snapshotId the master snapshot id from {@code TrafficDataDAO.insertSnapshot()}
     */
    public void insertAlerts(long snapshotId, RotarySnapshot snap) throws SQLException {
        List<RecommendedAction> actions = snap.getRecommendedActions();
        if (actions == null || actions.isEmpty()) return;

        String sql = """
            INSERT INTO alerts
                (snapshot_id, zone_id, action_type, target, new_value,
                 reason, severity, recorded_at)
            VALUES (?,?,?,?,?,?,?,?)
            """;

        Timestamp ts = new Timestamp(snap.getTimestamp());

        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {

            ensurePartition(c, java.time.LocalDate.now());

            for (RecommendedAction a : actions) {
                ps.setLong(1,   snapshotId);
                ps.setString(2, snap.getZoneId());
                ps.setString(3, a.getAction());
                ps.setString(4, a.getTarget());
                ps.setString(5, a.getNewValue());
                ps.setString(6, a.getReason());
                ps.setString(7, SEVERITY_MAP.getOrDefault(a.getAction(), "MEDIUM"));
                ps.setTimestamp(8, ts);
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Roll-up — alert frequency stats
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Aggregates the previous hour's alert counts into {@code alert_stats}.
     * Idempotent via ON DUPLICATE KEY UPDATE.
     * Call once per hour.
     */
    public void rollUpAlertStats() throws SQLException {
        String sql = """
            INSERT INTO alert_stats (zone_id, action_type, hour_bucket, alert_count, high_count)
            SELECT
                zone_id,
                action_type,
                DATE_FORMAT(recorded_at, '%Y-%m-%d %H:00:00') AS hour_bucket,
                COUNT(*)                                        AS alert_count,
                SUM(CASE WHEN severity = 'HIGH' THEN 1 ELSE 0 END) AS high_count
            FROM alerts
            WHERE recorded_at >= DATE_FORMAT(NOW() - INTERVAL 1 HOUR, '%Y-%m-%d %H:00:00')
              AND recorded_at <  DATE_FORMAT(NOW(),                    '%Y-%m-%d %H:00:00')
            GROUP BY zone_id, action_type, hour_bucket
            ON DUPLICATE KEY UPDATE
                alert_count = VALUES(alert_count),
                high_count  = VALUES(high_count)
            """;
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            int rows = ps.executeUpdate();
            System.out.printf("[ROLL-UP] Alert stats: %d rows upserted.%n", rows);
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Partition pruning
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Drops alert partitions older than {@code retentionDays}.
     * Alert stats in the summary table are preserved.
     */
    public void pruneOldAlerts(int retentionDays) throws SQLException {
        java.time.LocalDate cutoff = java.time.LocalDate.now().minusDays(retentionDays);

        String listSql = """
            SELECT PARTITION_NAME
            FROM   information_schema.PARTITIONS
            WHERE  TABLE_SCHEMA = DATABASE()
              AND  TABLE_NAME   = 'alerts'
              AND  PARTITION_NAME LIKE 'p_%_%'
            ORDER BY PARTITION_NAME
            """;

        try (Connection c = ds.getConnection();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(listSql)) {

            List<String> toDrop = new ArrayList<>();
            while (rs.next()) {
                String partName = rs.getString("PARTITION_NAME");
                String[] parts = partName.split("_");
                if (parts.length < 3) continue;
                try {
                    int year  = Integer.parseInt(parts[1]);
                    int month = Integer.parseInt(parts[2]);
                    java.time.LocalDate partMonth = java.time.LocalDate.of(year, month, 1);
                    if (partMonth.plusMonths(1).isBefore(cutoff)) toDrop.add(partName);
                } catch (NumberFormatException ignored) {}
            }

            if (!toDrop.isEmpty()) {
                String dropSql = "ALTER TABLE alerts DROP PARTITION " + String.join(", ", toDrop);
                try (Statement drop = c.createStatement()) {
                    drop.execute(dropSql);
                    System.out.printf("[PRUNE] Dropped alert partitions: %s%n", toDrop);
                }
            }
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    //  READ helpers
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Returns all unresolved (active) alerts for a zone, newest first.
     */
    public List<Map<String, Object>> getActiveAlerts(String zoneId) throws SQLException {
        String sql = """
            SELECT id, action_type, target, new_value, reason, severity, recorded_at
            FROM   alerts
            WHERE  zone_id = ? AND resolved = 0
            ORDER  BY recorded_at DESC
            LIMIT  200
            """;
        return queryToList(sql, zoneId);
    }

    /**
     * Returns alerts for a specific target (e.g. a traffic light sensor id).
     */
    public List<Map<String, Object>> getAlertsByTarget(String zoneId, String target, int limit)
            throws SQLException {
        String sql = """
            SELECT id, action_type, new_value, reason, severity, resolved, recorded_at
            FROM   alerts
            WHERE  zone_id = ? AND target = ?
            ORDER  BY recorded_at DESC
            LIMIT  ?
            """;
        return queryToList(sql, zoneId, target, limit);
    }

    /**
     * Returns HIGH-severity alerts from the last N minutes.
     */
    public List<Map<String, Object>> getRecentHighSeverityAlerts(String zoneId, int minutes)
            throws SQLException {
        String sql = """
            SELECT id, action_type, target, reason, recorded_at
            FROM   alerts
            WHERE  zone_id   = ?
              AND  severity  = 'HIGH'
              AND  recorded_at >= NOW() - INTERVAL ? MINUTE
            ORDER  BY recorded_at DESC
            """;
        return queryToList(sql, zoneId, minutes);
    }

    /**
     * Returns hourly alert frequency for the past N hours.
     */
    public List<Map<String, Object>> getAlertFrequency(String zoneId, int hours)
            throws SQLException {
        String sql = """
            SELECT hour_bucket, action_type, alert_count, high_count
            FROM   alert_stats
            WHERE  zone_id = ?
              AND  hour_bucket >= NOW() - INTERVAL ? HOUR
            ORDER  BY hour_bucket DESC, alert_count DESC
            """;
        return queryToList(sql, zoneId, hours);
    }

    /**
     * Marks an alert as resolved.
     */
    public void resolveAlert(long alertId) throws SQLException {
        String sql = "UPDATE alerts SET resolved = 1, resolved_at = NOW(3) WHERE id = ?";
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, alertId);
            ps.executeUpdate();
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Generic query helper
    // ════════════════════════════════════════════════════════════════════════

    private List<Map<String, Object>> queryToList(String sql, Object... params) throws SQLException {
        List<Map<String, Object>> results = new ArrayList<>();
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) ps.setObject(i + 1, params[i]);
            try (ResultSet rs = ps.executeQuery()) {
                ResultSetMetaData meta = rs.getMetaData();
                int cols = meta.getColumnCount();
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (int i = 1; i <= cols; i++) row.put(meta.getColumnLabel(i), rs.getObject(i));
                    results.add(row);
                }
            }
        }
        return results;
    }
}
