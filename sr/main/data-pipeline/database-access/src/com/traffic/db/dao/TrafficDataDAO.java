package com.traffic.db.dao;

import com.city.sensors.model.LaneData;
import com.city.sensors.model.RotarySnapshot;
import javax.sql.DataSource;

import java.sql.*;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

/**
 * Data-Access Object for all sensor readings.
 *
 * ── Schema strategy ──────────────────────────────────────────────────────────
 *
 * 1. RAW / real-time tables  →  partitioned by RANGE on `recorded_at` (monthly).
 *    New partitions are created automatically each month by ensurePartition().
 *    These hold every 10-second tick verbatim.
 *
 * 2. Hourly roll-up tables   →  pre-aggregated (AVG / MAX / MIN / COUNT).
 *    A background thread calls rollUpHourly() once per hour.
 *    Hourly tables are small and fast to query for dashboards.
 *
 * 3. Daily summary table     →  rolled up from hourly, kept indefinitely.
 *    Populated by rollUpDaily() once per day.
 *
 * 4. Old raw partitions (> 7 days) are pruned by pruneOldRawData() to avoid
 *    unbounded growth while the roll-up history is preserved.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 *
 * Tables created:
 *   traffic_snapshots          — one row per rotary tick
 *   lane_readings              — one row per lane per tick (8 × ticks)
 *   traffic_light_readings     — one row per light per tick (4 × ticks)
 *   pollution_readings         — one row per tick
 *   lane_hourly_summary        — hourly aggregates per lane
 *   lane_daily_summary         — daily  aggregates per lane
 */
public class TrafficDataDAO {

    private final DataSource ds;

    // ── Constructor ──────────────────────────────────────────────────────────

    public TrafficDataDAO(DataSource dataSource) {
        this.ds = dataSource;
    }

    // ════════════════════════════════════════════════════════════════════════
    //  DDL — table creation
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Creates all tables if they do not exist.
     * Call once at start-up before any other method.
     */
    public void initSchema() throws SQLException {
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {

            // ── Master snapshot table (partitioned by month) ──────────────
            st.execute("""
                CREATE TABLE IF NOT EXISTS traffic_snapshots (
                    id           BIGINT       NOT NULL AUTO_INCREMENT,
                    zone_id      VARCHAR(64)  NOT NULL,
                    recorded_at  DATETIME(3)  NOT NULL,
                    PRIMARY KEY (id, recorded_at)
                )
                ENGINE=InnoDB
                PARTITION BY RANGE (TO_DAYS(recorded_at)) (
                    PARTITION p_initial VALUES LESS THAN (TO_DAYS('2025-01-01'))
                );
                """);

            // ── Lane readings (partitioned by month on recorded_at) ───────
            st.execute("""
                CREATE TABLE IF NOT EXISTS lane_readings (
                    id              BIGINT        NOT NULL AUTO_INCREMENT,
                    snapshot_id     BIGINT        NOT NULL,
                    zone_id         VARCHAR(64)   NOT NULL,
                    lane_id         VARCHAR(32)   NOT NULL,
                    flux_level      VARCHAR(16)   NOT NULL,
                    vehicle_count   SMALLINT      NOT NULL,
                    noise_db        DECIMAL(5,1)  NOT NULL,
                    camera_status   VARCHAR(32)   NOT NULL,
                    camera_details  TEXT,
                    recorded_at     DATETIME(3)   NOT NULL,
                    PRIMARY KEY (id, recorded_at),
                    INDEX idx_lane_zone_time (zone_id, lane_id, recorded_at)
                )
                ENGINE=InnoDB
                PARTITION BY RANGE (TO_DAYS(recorded_at)) (
                    PARTITION p_initial VALUES LESS THAN (TO_DAYS('2025-01-01'))
                );
                """);

            // ── Traffic-light readings ─────────────────────────────────────
            st.execute("""
                CREATE TABLE IF NOT EXISTS traffic_light_readings (
                    id           BIGINT       NOT NULL AUTO_INCREMENT,
                    snapshot_id  BIGINT       NOT NULL,
                    zone_id      VARCHAR(64)  NOT NULL,
                    sensor_id    VARCHAR(64)  NOT NULL,
                    state        VARCHAR(8)   NOT NULL,
                    recorded_at  DATETIME(3)  NOT NULL,
                    PRIMARY KEY (id, recorded_at),
                    INDEX idx_tl_zone_time (zone_id, sensor_id, recorded_at)
                )
                ENGINE=InnoDB
                PARTITION BY RANGE (TO_DAYS(recorded_at)) (
                    PARTITION p_initial VALUES LESS THAN (TO_DAYS('2025-01-01'))
                );
                """);

            // ── Pollution readings ─────────────────────────────────────────
            st.execute("""
                CREATE TABLE IF NOT EXISTS pollution_readings (
                    id           BIGINT        NOT NULL AUTO_INCREMENT,
                    snapshot_id  BIGINT        NOT NULL,
                    zone_id      VARCHAR(64)   NOT NULL,
                    pm25         DECIMAL(6,2)  NOT NULL,
                    aqi_category VARCHAR(20)   NOT NULL,
                    recorded_at  DATETIME(3)   NOT NULL,
                    PRIMARY KEY (id, recorded_at),
                    INDEX idx_poll_zone_time (zone_id, recorded_at)
                )
                ENGINE=InnoDB
                PARTITION BY RANGE (TO_DAYS(recorded_at)) (
                    PARTITION p_initial VALUES LESS THAN (TO_DAYS('2025-01-01'))
                );
                """);

            // ── Hourly roll-up (NOT partitioned — always small) ───────────
            st.execute("""
                CREATE TABLE IF NOT EXISTS lane_hourly_summary (
                    id              BIGINT        NOT NULL AUTO_INCREMENT,
                    zone_id         VARCHAR(64)   NOT NULL,
                    lane_id         VARCHAR(32)   NOT NULL,
                    hour_bucket     DATETIME      NOT NULL,   -- truncated to the hour
                    avg_vehicles    DECIMAL(6,1)  NOT NULL,
                    max_vehicles    SMALLINT      NOT NULL,
                    min_vehicles    SMALLINT      NOT NULL,
                    avg_noise_db    DECIMAL(5,1)  NOT NULL,
                    max_noise_db    DECIMAL(5,1)  NOT NULL,
                    sample_count    SMALLINT      NOT NULL,
                    accident_count  SMALLINT      NOT NULL DEFAULT 0,
                    PRIMARY KEY (id),
                    UNIQUE KEY uq_lane_hour (zone_id, lane_id, hour_bucket),
                    INDEX idx_lane_hour (lane_id, hour_bucket)
                ) ENGINE=InnoDB;
                """);

            // ── Daily roll-up ──────────────────────────────────────────────
            st.execute("""
                CREATE TABLE IF NOT EXISTS lane_daily_summary (
                    id              BIGINT        NOT NULL AUTO_INCREMENT,
                    zone_id         VARCHAR(64)   NOT NULL,
                    lane_id         VARCHAR(32)   NOT NULL,
                    day_bucket      DATE          NOT NULL,
                    avg_vehicles    DECIMAL(6,1)  NOT NULL,
                    max_vehicles    SMALLINT      NOT NULL,
                    total_vehicles  INT           NOT NULL,
                    avg_noise_db    DECIMAL(5,1)  NOT NULL,
                    max_noise_db    DECIMAL(5,1)  NOT NULL,
                    accident_count  SMALLINT      NOT NULL DEFAULT 0,
                    sample_count    INT           NOT NULL,
                    PRIMARY KEY (id),
                    UNIQUE KEY uq_lane_day (zone_id, lane_id, day_bucket),
                    INDEX idx_day (day_bucket)
                ) ENGINE=InnoDB;
                """);

            // Ensure a valid starting partition for today's data
            ensurePartition(c, LocalDate.now());
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Partition management
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Adds a RANGE partition covering the month of {@code date} (if absent).
     * Partition name: p_YYYY_MM.  Upper bound: first day of the NEXT month.
     *
     * Safe to call repeatedly — catches the duplicate-partition error silently.
     */
    public void ensurePartition(Connection c, LocalDate date) {
        LocalDate firstOfNextMonth = date.withDayOfMonth(1).plusMonths(1);
        String partName = String.format("p_%04d_%02d", date.getYear(), date.getMonthValue());
        String bound    = firstOfNextMonth.toString(); // "YYYY-MM-DD"

        String sql = "ALTER TABLE %s ADD PARTITION (PARTITION %s VALUES LESS THAN (TO_DAYS('%s')))";

        for (String table : List.of("traffic_snapshots", "lane_readings",
                                    "traffic_light_readings", "pollution_readings")) {
            try (Statement st = c.createStatement()) {
                st.execute(String.format(sql, table, partName, bound));
            } catch (SQLException e) {
                // ER_SAME_NAME_PARTITION (1517) or partition already covers the range (1493) → OK
                if (e.getErrorCode() != 1517 && e.getErrorCode() != 1493) {
                    System.err.printf("[WARN] ensurePartition %s/%s: %s%n", table, partName, e.getMessage());
                }
            }
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    //  INSERT — real-time path
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Persists a full {@link RotarySnapshot} in a single transaction.
     * Ensures the current month's partition exists before writing.
     *
     * @return the generated snapshot_id
     */
    public long insertSnapshot(RotarySnapshot snap) throws SQLException {
        Timestamp ts = new Timestamp(snap.getTimestamp());

        try (Connection c = ds.getConnection()) {
            c.setAutoCommit(false);
            try {
                // Guarantee partition exists for today
                ensurePartition(c, LocalDate.now());

                // 1. Master snapshot row
                long snapshotId = insertMasterRow(c, snap.getZoneId(), ts);

                // 2. Lane rows (batch)
                insertLaneReadings(c, snapshotId, snap.getZoneId(), snap.getLaneData(), ts);

                // 3. Traffic-light rows (batch)
                insertTrafficLightReadings(c, snapshotId, snap.getZoneId(), snap.getTrafficLightData(), ts);

                // 4. Pollution row
                insertPollutionReading(c, snapshotId, snap.getZoneId(), snap.getPollutionData(), ts);

                c.commit();
                return snapshotId;

            } catch (SQLException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(true);
            }
        }
    }

    // ── Private insert helpers ───────────────────────────────────────────────

    private long insertMasterRow(Connection c, String zoneId, Timestamp ts) throws SQLException {
        String sql = "INSERT INTO traffic_snapshots (zone_id, recorded_at) VALUES (?, ?)";
        try (PreparedStatement ps = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, zoneId);
            ps.setTimestamp(2, ts);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private void insertLaneReadings(Connection c, long snapshotId, String zoneId,
                                    Map<String, LaneData> laneData, Timestamp ts) throws SQLException {
        String sql = """
            INSERT INTO lane_readings
                (snapshot_id, zone_id, lane_id, flux_level, vehicle_count,
                 noise_db, camera_status, camera_details, recorded_at)
            VALUES (?,?,?,?,?,?,?,?,?)
            """;
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (LaneData ld : laneData.values()) {
                ps.setLong(1, snapshotId);
                ps.setString(2, zoneId);
                ps.setString(3, ld.getLaneId());
                ps.setString(4, ld.getFlux());
                ps.setInt(5, ld.getVehicleCount());
                // parse "55.3dB" → 55.3
                ps.setBigDecimal(6, parseDb(ld.getNoise()));
                ps.setString(7, ld.getCameraStatus());
                ps.setString(8, ld.getCameraDetails());
                ps.setTimestamp(9, ts);
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    private void insertTrafficLightReadings(Connection c, long snapshotId, String zoneId,
                                            Map<String, String> lights, Timestamp ts) throws SQLException {
        String sql = """
            INSERT INTO traffic_light_readings
                (snapshot_id, zone_id, sensor_id, state, recorded_at)
            VALUES (?,?,?,?,?)
            """;
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (Map.Entry<String, String> e : lights.entrySet()) {
                ps.setLong(1, snapshotId);
                ps.setString(2, zoneId);
                ps.setString(3, e.getKey());
                ps.setString(4, e.getValue());
                ps.setTimestamp(5, ts);
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    private void insertPollutionReading(Connection c, long snapshotId, String zoneId,
                                        String pollutionJson, Timestamp ts) throws SQLException {
        // Parse the inline JSON string: {"sensorId":"...","pm25Level":42.1,"aqi":"Good"}
        double pm25 = extractDouble(pollutionJson, "pm25Level");
        String aqi  = extractString(pollutionJson, "aqi");

        String sql = """
            INSERT INTO pollution_readings
                (snapshot_id, zone_id, pm25, aqi_category, recorded_at)
            VALUES (?,?,?,?,?)
            """;
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, snapshotId);
            ps.setString(2, zoneId);
            ps.setDouble(3, pm25);
            ps.setString(4, aqi);
            ps.setTimestamp(5, ts);
            ps.executeUpdate();
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Roll-up jobs
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Aggregates the previous complete hour into {@code lane_hourly_summary}.
     * Uses INSERT … ON DUPLICATE KEY UPDATE for idempotency (safe to re-run).
     *
     * Call once per hour (e.g. from a ScheduledExecutorService).
     */
    public void rollUpHourly() throws SQLException {
        // Hour bucket = truncate recorded_at to the start of the previous hour
        String sql = """
            INSERT INTO lane_hourly_summary
                (zone_id, lane_id, hour_bucket,
                 avg_vehicles, max_vehicles, min_vehicles,
                 avg_noise_db, max_noise_db,
                 sample_count, accident_count)
            SELECT
                zone_id,
                lane_id,
                DATE_FORMAT(recorded_at, '%Y-%m-%d %H:00:00') AS hour_bucket,
                AVG(vehicle_count),
                MAX(vehicle_count),
                MIN(vehicle_count),
                AVG(noise_db),
                MAX(noise_db),
                COUNT(*),
                SUM(CASE WHEN camera_status = 'ACCIDENT' THEN 1 ELSE 0 END)
            FROM lane_readings
            WHERE recorded_at >= DATE_FORMAT(NOW() - INTERVAL 1 HOUR, '%Y-%m-%d %H:00:00')
              AND recorded_at <  DATE_FORMAT(NOW(),                    '%Y-%m-%d %H:00:00')
            GROUP BY zone_id, lane_id, hour_bucket
            ON DUPLICATE KEY UPDATE
                avg_vehicles   = VALUES(avg_vehicles),
                max_vehicles   = VALUES(max_vehicles),
                min_vehicles   = VALUES(min_vehicles),
                avg_noise_db   = VALUES(avg_noise_db),
                max_noise_db   = VALUES(max_noise_db),
                sample_count   = VALUES(sample_count),
                accident_count = VALUES(accident_count)
            """;

        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            int rows = ps.executeUpdate();
            System.out.printf("[ROLL-UP] Hourly: %d lane-hour rows upserted.%n", rows);
        }
    }

    /**
     * Aggregates yesterday's hourly rows into {@code lane_daily_summary}.
     * Call once per day (e.g. just after midnight).
     */
    public void rollUpDaily() throws SQLException {
        String sql = """
            INSERT INTO lane_daily_summary
                (zone_id, lane_id, day_bucket,
                 avg_vehicles, max_vehicles, total_vehicles,
                 avg_noise_db, max_noise_db,
                 accident_count, sample_count)
            SELECT
                zone_id,
                lane_id,
                DATE(hour_bucket)                 AS day_bucket,
                AVG(avg_vehicles),
                MAX(max_vehicles),
                ROUND(SUM(avg_vehicles * sample_count)), -- weighted total
                AVG(avg_noise_db),
                MAX(max_noise_db),
                SUM(accident_count),
                SUM(sample_count)
            FROM lane_hourly_summary
            WHERE DATE(hour_bucket) = CURDATE() - INTERVAL 1 DAY
            GROUP BY zone_id, lane_id, day_bucket
            ON DUPLICATE KEY UPDATE
                avg_vehicles   = VALUES(avg_vehicles),
                max_vehicles   = VALUES(max_vehicles),
                total_vehicles = VALUES(total_vehicles),
                avg_noise_db   = VALUES(avg_noise_db),
                max_noise_db   = VALUES(max_noise_db),
                accident_count = VALUES(accident_count),
                sample_count   = VALUES(sample_count)
            """;

        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            int rows = ps.executeUpdate();
            System.out.printf("[ROLL-UP] Daily: %d lane-day rows upserted.%n", rows);
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Partition pruning — drop raw partitions older than N days
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Drops monthly partitions from raw tables whose data is older than
     * {@code retentionDays}. Roll-up data in summary tables is kept.
     *
     * @param retentionDays keep raw data for at least this many days (e.g. 7)
     */
    public void pruneOldRawData(int retentionDays) throws SQLException {
        LocalDate cutoff = LocalDate.now().minusDays(retentionDays);

        // List partitions from information_schema
        String listSql = """
            SELECT PARTITION_NAME, TABLE_NAME
            FROM   information_schema.PARTITIONS
            WHERE  TABLE_SCHEMA = DATABASE()
              AND  TABLE_NAME   IN ('traffic_snapshots','lane_readings',
                                    'traffic_light_readings','pollution_readings')
              AND  PARTITION_NAME LIKE 'p_%_%'
            ORDER BY PARTITION_NAME
            """;

        try (Connection c = ds.getConnection();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(listSql)) {

            // Collect table→partitions to drop
            Map<String, List<String>> toDrop = new LinkedHashMap<>();
            while (rs.next()) {
                String partName = rs.getString("PARTITION_NAME");   // p_2024_11
                String tableName = rs.getString("TABLE_NAME");

                // Parse year/month from partition name
                String[] parts = partName.split("_");               // ["p","2024","11"]
                if (parts.length < 3) continue;
                try {
                    int year  = Integer.parseInt(parts[1]);
                    int month = Integer.parseInt(parts[2]);
                    LocalDate partMonth = LocalDate.of(year, month, 1);
                    // Drop if the entire month is before the cutoff
                    if (partMonth.plusMonths(1).isBefore(cutoff)) {
                        toDrop.computeIfAbsent(tableName, k -> new ArrayList<>()).add(partName);
                    }
                } catch (NumberFormatException ignored) { /* skip p_initial etc. */ }
            }

            // Issue ALTER TABLE … DROP PARTITION
            for (Map.Entry<String, List<String>> e : toDrop.entrySet()) {
                String dropSql = "ALTER TABLE " + e.getKey()
                        + " DROP PARTITION " + String.join(", ", e.getValue());
                try (Statement drop = c.createStatement()) {
                    drop.execute(dropSql);
                    System.out.printf("[PRUNE] Dropped from %s: %s%n", e.getKey(), e.getValue());
                }
            }
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    //  READ helpers — recent & aggregated queries
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Returns the latest N raw lane readings for a given lane.
     */
    public List<Map<String, Object>> getLatestLaneReadings(String zoneId, String laneId, int limit)
            throws SQLException {
        String sql = """
            SELECT recorded_at, flux_level, vehicle_count, noise_db, camera_status
            FROM   lane_readings
            WHERE  zone_id = ? AND lane_id = ?
            ORDER  BY recorded_at DESC
            LIMIT  ?
            """;
        return queryToList(sql, zoneId, laneId, limit);
    }

    /**
     * Returns hourly averages for a lane over the past N hours.
     */
    public List<Map<String, Object>> getHourlyAverages(String zoneId, String laneId, int hours)
            throws SQLException {
        String sql = """
            SELECT hour_bucket, avg_vehicles, avg_noise_db, max_vehicles, accident_count
            FROM   lane_hourly_summary
            WHERE  zone_id = ? AND lane_id = ?
              AND  hour_bucket >= NOW() - INTERVAL ? HOUR
            ORDER  BY hour_bucket DESC
            """;
        return queryToList(sql, zoneId, laneId, hours);
    }

    /**
     * Returns daily summaries for a lane over the past N days.
     */
    public List<Map<String, Object>> getDailySummaries(String zoneId, String laneId, int days)
            throws SQLException {
        String sql = """
            SELECT day_bucket, avg_vehicles, total_vehicles,
                   avg_noise_db, accident_count, sample_count
            FROM   lane_daily_summary
            WHERE  zone_id = ? AND lane_id = ?
              AND  day_bucket >= CURDATE() - INTERVAL ? DAY
            ORDER  BY day_bucket DESC
            """;
        return queryToList(sql, zoneId, laneId, days);
    }

    /**
     * Returns recent pollution readings for the past N minutes.
     */
    public List<Map<String, Object>> getRecentPollution(String zoneId, int minutes)
            throws SQLException {
        String sql = """
            SELECT recorded_at, pm25, aqi_category
            FROM   pollution_readings
            WHERE  zone_id = ?
              AND  recorded_at >= NOW() - INTERVAL ? MINUTE
            ORDER  BY recorded_at DESC
            """;
        return queryToList(sql, zoneId, minutes);
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
                    for (int i = 1; i <= cols; i++) {
                        row.put(meta.getColumnLabel(i), rs.getObject(i));
                    }
                    results.add(row);
                }
            }
        }
        return results;
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Micro-parsers (avoid external JSON lib dependency)
    // ════════════════════════════════════════════════════════════════════════

    /** Parses "55.3dB" → 55.3 */
     /** Parses "71,3dB" or "71.3dB" → 71.3 */
    private java.math.BigDecimal parseDb(String noise) {
        if (noise == null || noise.isEmpty()) return java.math.BigDecimal.ZERO;
        try {
            // 1. Remove "dB" suffix
            // 2. Replace comma with dot (handles "71,3" → "71.3")
            // 3. Trim whitespace
            String clean = noise.replace("dB", "").replace(",", ".").trim();
            return new java.math.BigDecimal(clean);
        } catch (NumberFormatException e) {
            return java.math.BigDecimal.ZERO;
        }
    }

    /** Extracts a numeric value from a simple flat JSON string. */
    private double extractDouble(String json, String key) {
        String search = "\"" + key + "\":";
        int start = json.indexOf(search);
        if (start < 0) return 0.0;
        start += search.length();
        int end = json.indexOf(',', start);
        if (end < 0) end = json.indexOf('}', start);
        try { return Double.parseDouble(json.substring(start, end).trim()); }
        catch (NumberFormatException e) { return 0.0; }
    }

    /** Extracts a string value from a simple flat JSON string. */
    private String extractString(String json, String key) {
        String search = "\"" + key + "\":\"";
        int start = json.indexOf(search);
        if (start < 0) return "";
        start += search.length();
        int end = json.indexOf('"', start);
        return end > start ? json.substring(start, end) : "";
    }
}
