package com.traffic.analytics.engine;

import com.traffic.analytics.alerts.AlertAnalyser;
import com.traffic.analytics.congestion.CongestionAnalyser;
import com.traffic.analytics.db.AnalyticsRepository;
import com.traffic.analytics.environment.EnvironmentAnalyser;
import com.traffic.analytics.model.AlertRecord;
import com.traffic.analytics.model.AnalyticsResult;
import com.traffic.analytics.model.LaneSnapshot;
import com.traffic.analytics.model.PollutionSnapshot;
import com.traffic.analytics.recommendations.RecommendationEngine;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.sql.SQLException;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Analytics Engine — main entry point.
 *
 * Every ANALYSIS_INTERVAL_MS milliseconds the engine:
 *
 *  1.  Pulls a rolling window of lane + pollution data from the DB.
 *  2.  Pulls active and recent alerts.
 *  3.  Runs all four analysers in sequence:
 *        CongestionAnalyser → EnvironmentAnalyser → AlertAnalyser →
 *        RecommendationEngine
 *  4.  Prints a JSON report to stdout.
 *  5.  (Optional extension point) Writes results back to an
 *      analytics_results table — stub shown in persistResults().
 *
 * Run:
 *   mvn package
 *   java -jar target/analytics-engine-1.0.0.jar
 */
public class AnalyticsEngine {

    // ── Config ────────────────────────────────────────────────────────────────
    private static final String DB_URL      = "jdbc:mysql://localhost:3306/traffic_db"
                                            + "?useSSL=false&serverTimezone=UTC"
                                            + "&rewriteBatchedStatements=true";
    private static final String DB_USER     = "ilyas";
    private static final String DB_PASSWORD = "MyPass123!";

    private static final String ZONE_ID             = "MAIN-STREET-ROTARY";
    private static final long   ANALYSIS_INTERVAL_MS = 10_000L;   // 10 s — matches sensor cadence
    private static final int    WINDOW_MINUTES        = 5;         // rolling window for analysis
    private static final int    ALERT_WINDOW_MINUTES  = 10;        // alert lookback

    // ── Components ────────────────────────────────────────────────────────────
    private final HikariDataSource       dataSource;
    private final AnalyticsRepository    repo;
    private final CongestionAnalyser     congestionAnalyser;
    private final EnvironmentAnalyser    environmentAnalyser;
    private final AlertAnalyser          alertAnalyser;
    private final RecommendationEngine   recommendationEngine;
    private final ScheduledExecutorService scheduler;

    // ════════════════════════════════════════════════════════════════════════
    //  Main
    // ════════════════════════════════════════════════════════════════════════

    public static void main(String[] args) {
        AnalyticsEngine engine = new AnalyticsEngine();
        engine.start();
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Constructor
    // ════════════════════════════════════════════════════════════════════════

    public AnalyticsEngine() {
        this.dataSource          = buildPool();
        this.repo                = new AnalyticsRepository(dataSource);
        this.congestionAnalyser  = new CongestionAnalyser();
        this.environmentAnalyser = new EnvironmentAnalyser();
        this.alertAnalyser       = new AlertAnalyser();
        this.recommendationEngine= new RecommendationEngine();
        this.scheduler           = Executors.newSingleThreadScheduledExecutor(
                r -> { Thread t = new Thread(r, "analytics-loop"); t.setDaemon(false); return t; });
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Lifecycle
    // ════════════════════════════════════════════════════════════════════════

    public void start() {
        banner();
        Runtime.getRuntime().addShutdownHook(new Thread(this::shutdown, "shutdown"));

        scheduler.scheduleAtFixedRate(
            this::runCycle,
            0L, ANALYSIS_INTERVAL_MS, TimeUnit.MILLISECONDS
        );

        // Keep main thread alive
        try { Thread.currentThread().join(); }
        catch (InterruptedException ignored) { shutdown(); }
    }

    private void shutdown() {
        System.out.println("\n[ANALYTICS] Shutting down …");
        scheduler.shutdownNow();
        if (!dataSource.isClosed()) dataSource.close();
        System.out.println("[ANALYTICS] Stopped.");
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Core analysis cycle
    // ════════════════════════════════════════════════════════════════════════

    private void runCycle() {
        long cycleStart = System.currentTimeMillis();
        System.out.printf("%n══════ Analytics cycle @ %s ══════%n", Instant.now());

        try {
            // ── 1. Fetch data ────────────────────────────────────────────
            List<LaneSnapshot>     laneWindow    = repo.getRecentLaneReadings(ZONE_ID, WINDOW_MINUTES);
            List<LaneSnapshot>     latestLanes   = repo.getLatestPerLane(ZONE_ID);
            List<PollutionSnapshot> pollWindow   = repo.getRecentPollution(ZONE_ID, WINDOW_MINUTES);
            PollutionSnapshot      latestPoll    = repo.getLatestPollution(ZONE_ID);
            List<AlertRecord>      activeAlerts  = repo.getActiveAlerts(ZONE_ID);
            List<AlertRecord>      windowAlerts  = repo.getRecentAlerts(ZONE_ID, ALERT_WINDOW_MINUTES);

            System.out.printf("[DATA] laneReadings=%d latestLanes=%d pollReadings=%d "
                + "activeAlerts=%d windowAlerts=%d%n",
                laneWindow.size(), latestLanes.size(), pollWindow.size(),
                activeAlerts.size(), windowAlerts.size());

            if (laneWindow.isEmpty() && pollWindow.isEmpty()) {
                System.out.println("[DATA] No data in window yet — waiting for sensor data.");
                return;
            }

            // ── 2. Run analysers ─────────────────────────────────────────
            List<AnalyticsResult> congestionResults  = congestionAnalyser.analyse(ZONE_ID, laneWindow);
            List<AnalyticsResult> envResults         = environmentAnalyser.analyse(ZONE_ID, pollWindow, laneWindow);
            List<AnalyticsResult> alertResults       = alertAnalyser.analyse(ZONE_ID, activeAlerts, windowAlerts);
            List<AnalyticsResult> recommendations    = recommendationEngine.recommend(
                ZONE_ID, congestionResults, envResults, alertResults, latestLanes, latestPoll);

            // ── 3. Build full report ─────────────────────────────────────
            List<AnalyticsResult> all = new ArrayList<>();
            all.addAll(congestionResults);
            all.addAll(envResults);
            all.addAll(alertResults);
            all.addAll(recommendations);

            // ── 4. Print report ──────────────────────────────────────────
            printReport(all, cycleStart);

            // ── 5. Persist (extension point) ─────────────────────────────
            persistResults(all);

        } catch (SQLException e) {
            System.err.println("[ERROR] DB error in analytics cycle: " + e.getMessage());
        } catch (Exception e) {
            System.err.println("[ERROR] Unexpected error: " + e.getMessage());
            e.printStackTrace();
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Report printer
    // ════════════════════════════════════════════════════════════════════════

    private void printReport(List<AnalyticsResult> results, long cycleStart) {
        // Bucket by category
        Map<AnalyticsResult.Category, List<AnalyticsResult>> byCategory = results.stream()
            .collect(Collectors.groupingBy(AnalyticsResult::getCategory,
                     () -> new EnumMap<>(AnalyticsResult.Category.class),
                     Collectors.toList()));

        // Count by severity
        Map<AnalyticsResult.Severity, Long> bySeverity = results.stream()
            .collect(Collectors.groupingBy(AnalyticsResult::getSeverity, Collectors.counting()));

        long elapsed = System.currentTimeMillis() - cycleStart;

        System.out.println("{");
        System.out.println("  \"zone\": \"" + ZONE_ID + "\",");
        System.out.println("  \"analysisTimestamp\": " + Instant.now().toEpochMilli() + ",");
        System.out.println("  \"processingMs\": " + elapsed + ",");
        System.out.println("  \"summary\": {");
        System.out.println("    \"total\": "    + results.size() + ",");
        System.out.println("    \"critical\": " + bySeverity.getOrDefault(AnalyticsResult.Severity.CRITICAL, 0L) + ",");
        System.out.println("    \"high\": "     + bySeverity.getOrDefault(AnalyticsResult.Severity.HIGH, 0L) + ",");
        System.out.println("    \"medium\": "   + bySeverity.getOrDefault(AnalyticsResult.Severity.MEDIUM, 0L) + ",");
        System.out.println("    \"low\": "      + bySeverity.getOrDefault(AnalyticsResult.Severity.LOW, 0L) + ",");
        System.out.println("    \"info\": "     + bySeverity.getOrDefault(AnalyticsResult.Severity.INFO, 0L));
        System.out.println("  },");

        System.out.println("  \"results\": [");
        for (int i = 0; i < results.size(); i++) {
            // Indent each result's JSON
            String json = results.get(i).toJson()
                .lines()
                .map(l -> "    " + l)
                .collect(Collectors.joining("\n"));
            System.out.print(json);
            System.out.println(i < results.size() - 1 ? "," : "");
        }
        System.out.println("  ]");
        System.out.println("}");

        // Console summary line
        System.out.printf("[ANALYTICS] %d results — %d CRITICAL, %d HIGH, %d MEDIUM "
            + "| processed in %d ms%n",
            results.size(),
            bySeverity.getOrDefault(AnalyticsResult.Severity.CRITICAL, 0L),
            bySeverity.getOrDefault(AnalyticsResult.Severity.HIGH,     0L),
            bySeverity.getOrDefault(AnalyticsResult.Severity.MEDIUM,   0L),
            elapsed);
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Persistence (extension point)
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Extension point: write analytics results back to the DB.
     *
     * The table below can be created in traffic_db to store results:
     *
     * CREATE TABLE analytics_results (
     *     id           BIGINT       NOT NULL AUTO_INCREMENT,
     *     zone_id      VARCHAR(64)  NOT NULL,
     *     category     VARCHAR(20)  NOT NULL,
     *     severity     VARCHAR(10)  NOT NULL,
     *     lane_id      VARCHAR(32),
     *     title        VARCHAR(255) NOT NULL,
     *     description  TEXT,
     *     metrics_json TEXT,
     *     generated_at DATETIME(3)  NOT NULL,
     *     PRIMARY KEY (id),
     *     INDEX idx_zone_time (zone_id, generated_at),
     *     INDEX idx_severity  (severity, generated_at)
     * ) ENGINE=InnoDB;
     *
     * Uncomment and implement when the table is ready.
    */
    private void persistResults(List<AnalyticsResult> results) {
        // TODO: implement INSERT INTO analytics_results …
        // For now, results are only printed to stdout.
    }

    // ════════════════════════════════════════════════════════════════════════
    //  HikariCP pool
    // ════════════════════════════════════════════════════════════════════════

    private static HikariDataSource buildPool() {
        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl(DB_URL);
        cfg.setUsername(DB_USER);
        cfg.setPassword(DB_PASSWORD);
        cfg.setMinimumIdle(2);
        cfg.setMaximumPoolSize(8);
        cfg.setConnectionTimeout(5_000L);
        cfg.setMaxLifetime(1_800_000L);
        cfg.setPoolName("AnalyticsPool");
        cfg.setConnectionTestQuery("SELECT 1");
        cfg.addDataSourceProperty("cachePrepStmts",          "true");
        cfg.addDataSourceProperty("prepStmtCacheSize",       "100");
        cfg.addDataSourceProperty("useServerPrepStmts",      "true");
        return new HikariDataSource(cfg);
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Banner
    // ════════════════════════════════════════════════════════════════════════

    private static void banner() {
        System.out.println("╔════════════════════════════════════════════════════════╗");
        System.out.println("║         Traffic Analytics Engine  v1.0.0              ║");
        System.out.println("╠════════════════════════════════════════════════════════╣");
        System.out.println("║  Zone     : " + ZONE_ID);
        System.out.println("║  Interval : " + (ANALYSIS_INTERVAL_MS / 1000) + " s");
        System.out.println("║  Window   : " + WINDOW_MINUTES + " min lane / " + ALERT_WINDOW_MINUTES + " min alerts");
        System.out.println("╚════════════════════════════════════════════════════════╝");
    }
}
