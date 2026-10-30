package com.city.sensors;

import com.city.sensors.model.RotarySnapshot;
import com.city.sensors.simulator.JsonSerializer;
import com.city.sensors.simulator.RotarySimulator;

import com.traffic.db.SimpleDriverDataSource;
import com.traffic.db.dao.AlertDAO;
import com.traffic.db.dao.TrafficDataDAO;
import java.sql.DriverManager;
import java.sql.SQLException;

/**
 * Entry point for the City Sensor Simulation.
 *
 * Launches a {@link RotarySimulator} for the MAIN-STREET-ROTARY zone and
 * prints a full JSON snapshot every 10 seconds.
 *
 * Run:
 *   javac -d out $(find src -name "*.java")
 *   java  -cp out com.city.sensors.Main
 *
 * Stop with Ctrl+C.
 */
public class Main {

    private static final String ZONE_ID       = "MAIN-STREET-ROTARY";
    private static final long   INTERVAL_MS   = 10_000L;   // 10 seconds

    // ── Database Settings ────────────────────────────────────────────────────
    private static final String DB_URL      = "jdbc:mysql://localhost:3306/traffic_db?useSSL=false&serverTimezone=UTC&rewriteBatchedStatements=true";
    private static final String DB_USER     = "ilyas";
    private static final String DB_PASSWORD = "MyPass123!";

    public static void main(String[] args) throws InterruptedException {

        System.out.println("╔══════════════════════════════════════════════════════╗");
        System.out.println("║      City-Centre Rotary Sensor Simulator v1.0       ║");
        System.out.println("╠══════════════════════════════════════════════════════╣");
        System.out.println("║  Zone   : " + ZONE_ID);
        System.out.println("║  Lanes  : 8  (NORTH/SOUTH/EAST/WEST × IN/OUT)");
        System.out.println("║  Lights : 4  traffic lights");
        System.out.println("║  Cameras: 8  (one per lane)");
        System.out.println("║  Flow   : 8  sensors (one per lane)");
        System.out.println("║  Noise  : 8  sensors (one per lane)");
        System.out.println("║  Pollution: 1 (rotary centre)");
        System.out.println("║  Interval: " + (INTERVAL_MS / 1000) + " s");
        System.out.println("╚══════════════════════════════════════════════════════╝");
        System.out.println();

        // ── Initialize Database Connection (DriverManager-based) ─────────────
        System.out.println("[INIT] Connecting to database...");
        try {
            Class.forName("com.mysql.cj.jdbc.Driver");
        } catch (ClassNotFoundException e) {
            System.err.println("[WARN] MySQL driver not found on classpath: " + e.getMessage());
        }
        com.traffic.db.SimpleDriverDataSource dataSource = new SimpleDriverDataSource(DB_URL, DB_USER, DB_PASSWORD);
        TrafficDataDAO trafficDAO = new TrafficDataDAO(dataSource);
        AlertDAO alertDAO = new AlertDAO(dataSource);

        try {
            trafficDAO.initSchema();
            alertDAO.initSchema();
        } catch (SQLException e) {
            System.err.println("[ERROR] Failed to init DB schema: " + e.getMessage());
        }

        RotarySimulator simulator = new RotarySimulator(ZONE_ID);

        int cycle = 0;
        while (true) {
            cycle++;
            System.out.printf("── Cycle %d  [%s] ──────────────────────────────%n",
                    cycle, new java.util.Date());

            RotarySnapshot snapshot = simulator.tick();
            String         json     = JsonSerializer.toJson(snapshot);

            System.out.println(json);
            System.out.println();

            // ── Persist to Database ──────────────────────────────────────────
            try {
                long snapshotId = trafficDAO.insertSnapshot(snapshot);
                alertDAO.insertAlerts(snapshotId, snapshot);
                System.out.println("[DB] Saved Snapshot ID: " + snapshotId);
            } catch (SQLException e) {
                System.err.println("[DB] Error saving snapshot: " + e.getMessage());  
            }

            Thread.sleep(INTERVAL_MS);
        }
    }
}
