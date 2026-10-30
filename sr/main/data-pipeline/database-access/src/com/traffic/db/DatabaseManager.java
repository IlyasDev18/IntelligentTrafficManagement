package com.traffic.db;

import com.traffic.db.dao.AlertDAO;
import com.traffic.db.dao.TrafficDataDAO;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.concurrent.*;

/**
 * Central entry-point and lifecycle manager for the database access layer.
 *
 * Responsibilities
 * ────────────────
 *  1. Build and configure a HikariCP connection pool.
 *  2. Initialize the database schema (tables + starting partitions).
 *  3. Schedule background maintenance jobs:
 *       • Hourly roll-up  (lane_hourly_summary, alert_stats)
 *       • Daily  roll-up  (lane_daily_summary)
 *       • Partition creation for the next month (runs on the 28th)
 *       • Raw data pruning (keeps last RAW_RETENTION_DAYS days)
 *
 * Configuration
 * ─────────────
 * All tuneable values are static constants at the top of the class.
 * For production use, externalise them to a .properties file or env vars.
 */
public class DatabaseManager {

    // ── Database connection settings ─────────────────────────────────────────
    private static final String DB_URL      = "jdbc:mysql://localhost:3306/traffic_db"
                                            + "?useSSL=false"
                                            + "&serverTimezone=UTC"
                                            + "&rewriteBatchedStatements=true"
                                            + "&cachePrepStmts=true"
                                            + "&prepStmtCacheSize=250"
                                            + "&prepStmtCacheSqlLimit=2048";
    private static final String DB_USER     = "traffic_user";
    private static final String DB_PASSWORD = "traffic_pass";

    // ── Pool tuning ───────────────────────────────────────────────────────────
    private static final int  POOL_MIN_IDLE        = 4;
    private static final int  POOL_MAX_SIZE         = 16;
    private static final long POOL_CONN_TIMEOUT_MS  = 5_000L;
    private static final long POOL_MAX_LIFETIME_MS  = 1_800_000L;  // 30 min
    private static final long POOL_KEEPALIVE_MS     = 60_000L;

    // ── Maintenance settings ──────────────────────────────────────────────────
    private static final int RAW_RETENTION_DAYS     = 7;

    // ── Instance fields ───────────────────────────────────────────────────────
    private final HikariDataSource    dataSource;
    private final TrafficDataDAO      trafficDAO;
    private final AlertDAO            alertDAO;
    private final ScheduledExecutorService scheduler;

    // ════════════════════════════════════════════════════════════════════════
    //  Boot
    // ════════════════════════════════════════════════════════════════════════

    public DatabaseManager() {
        this.dataSource = buildPool();
        this.trafficDAO = new TrafficDataDAO(dataSource);
        this.alertDAO   = new AlertDAO(dataSource);
        this.scheduler  = Executors.newScheduledThreadPool(4,
                r -> { Thread t = new Thread(r, "db-maintenance"); t.setDaemon(true); return t; });
        initSchema();
        scheduleMaintenanceJobs();
    }

    private void initSchema() {
        try {
            System.out.println("[INIT] Initialising database schema …");
            trafficDAO.initSchema();
            alertDAO.initSchema();
            System.out.println("[INIT] Schema ready.");
        } catch (SQLException e) {
            throw new RuntimeException("Failed to initialize schema", e);
        }
    }


    // ════════════════════════════════════════════════════════════════════════
    //  Lifecycle
    // ════════════════════════════════════════════════════════════════════════

    private void shutdown() {
        System.out.println("\n[SHUTDOWN] Stopping scheduler …");
        scheduler.shutdownNow();
        if (!dataSource.isClosed()) {
            System.out.println("[SHUTDOWN] Closing connection pool …");
            dataSource.close();
        }
        System.out.println("[SHUTDOWN] Clean exit.");
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Maintenance scheduler
    // ════════════════════════════════════════════════════════════════════════

    private void scheduleMaintenanceJobs() {

        // ── Hourly roll-up — runs at the top of every hour ────────────────
        long initialHourDelay = secondsUntilNextHour();
        scheduler.scheduleAtFixedRate(() -> {
            try {
                trafficDAO.rollUpHourly();
                alertDAO.rollUpAlertStats();
            } catch (SQLException e) {
                System.err.println("[MAINT] Hourly roll-up error: " + e.getMessage());
            }
        }, initialHourDelay, 3600L, TimeUnit.SECONDS);

        System.out.printf("[MAINT] Hourly roll-up scheduled (first run in %ds).%n", initialHourDelay);

        // ── Daily roll-up — runs at 00:05 every day ───────────────────────
        long initialDayDelay = secondsUntilMidnightPlus(5 * 60);
        scheduler.scheduleAtFixedRate(() -> {
            try {
                trafficDAO.rollUpDaily();
                // Also ensure next month's partition exists (runs daily — cheap)
                ensureNextMonthPartition();
            } catch (SQLException e) {
                System.err.println("[MAINT] Daily roll-up error: " + e.getMessage());
            }
        }, initialDayDelay, TimeUnit.DAYS.toSeconds(1), TimeUnit.SECONDS);

        System.out.printf("[MAINT] Daily roll-up scheduled (first run in %ds).%n", initialDayDelay);

        // ── Partition pruning — runs every 6 hours ─────────────────────────
        scheduler.scheduleAtFixedRate(() -> {
            try {
                trafficDAO.pruneOldRawData(RAW_RETENTION_DAYS);
                alertDAO.pruneOldAlerts(RAW_RETENTION_DAYS);
            } catch (SQLException e) {
                System.err.println("[MAINT] Pruning error: " + e.getMessage());
            }
        }, 1L, 6L, TimeUnit.HOURS);

        System.out.println("[MAINT] Partition pruning scheduled (every 6 h).");
    }

    private void ensureNextMonthPartition() {
        LocalDate nextMonth = LocalDate.now().plusMonths(1);
        try (var c = dataSource.getConnection()) {
            trafficDAO.ensurePartition(c, nextMonth);
            alertDAO.ensurePartition(c, nextMonth);
            System.out.printf("[MAINT] Ensured partition for %s.%n", nextMonth.withDayOfMonth(1));
        } catch (SQLException e) {
            System.err.println("[MAINT] ensureNextMonthPartition error: " + e.getMessage());
        }
    }

    // ── Time helpers ─────────────────────────────────────────────────────────

    private static long secondsUntilNextHour() {
        LocalDateTime now  = LocalDateTime.now();
        LocalDateTime next = now.withMinute(0).withSecond(5).withNano(0).plusHours(1);
        return java.time.Duration.between(now, next).getSeconds();
    }

    private static long secondsUntilMidnightPlus(long extraSeconds) {
        LocalDateTime now  = LocalDateTime.now();
        LocalDateTime next = now.toLocalDate().plusDays(1).atStartOfDay()
                               .plusSeconds(extraSeconds);
        return java.time.Duration.between(now, next).getSeconds();
    }

    // ════════════════════════════════════════════════════════════════════════
    //  HikariCP pool builder
    // ════════════════════════════════════════════════════════════════════════

    private static HikariDataSource buildPool() {
        HikariConfig cfg = new HikariConfig();

        cfg.setJdbcUrl(DB_URL);
        cfg.setUsername(DB_USER);
        cfg.setPassword(DB_PASSWORD);
        cfg.setDriverClassName("com.mysql.cj.jdbc.Driver");

        // Pool sizing
        cfg.setMinimumIdle(POOL_MIN_IDLE);
        cfg.setMaximumPoolSize(POOL_MAX_SIZE);

        // Timeouts
        cfg.setConnectionTimeout(POOL_CONN_TIMEOUT_MS);
        cfg.setMaxLifetime(POOL_MAX_LIFETIME_MS);
        cfg.setKeepaliveTime(POOL_KEEPALIVE_MS);

        // Health check query
        cfg.setConnectionTestQuery("SELECT 1");

        // Pool name (shows up in JMX / logs)
        cfg.setPoolName("TrafficPool");

        // MySQL-specific performance flags already embedded in the URL above,
        // but we reinforce them via datasource properties for older driver compat.
        cfg.addDataSourceProperty("cachePrepStmts",          "true");
        cfg.addDataSourceProperty("prepStmtCacheSize",       "250");
        cfg.addDataSourceProperty("prepStmtCacheSqlLimit",   "2048");
        cfg.addDataSourceProperty("useServerPrepStmts",      "true");
        cfg.addDataSourceProperty("useLocalSessionState",    "true");
        cfg.addDataSourceProperty("rewriteBatchedStatements","true");
        cfg.addDataSourceProperty("cacheResultSetMetadata",  "true");
        cfg.addDataSourceProperty("cacheServerConfiguration","true");
        cfg.addDataSourceProperty("elideSetAutoCommits",     "true");
        cfg.addDataSourceProperty("maintainTimeStats",       "false");

        System.out.println("[POOL] Building HikariCP pool → " + DB_URL);
        return new HikariDataSource(cfg);
    }
}
