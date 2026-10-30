package com.traffic.dashboard.controller;

import com.traffic.dashboard.model.DashboardSnapshot;
import com.traffic.dashboard.service.DashboardDataService;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Controller
public class DashboardController {

    private final DashboardDataService dataService;
    private final JdbcTemplate         jdbc;

    public DashboardController(DashboardDataService dataService, JdbcTemplate jdbc) {
        this.dataService = dataService;
        this.jdbc        = jdbc;
        System.out.println("[DEBUG] DashboardController initialized");
    }

    /** Serve the single-page dashboard. */
    @GetMapping("/")
    public String index() { 
        System.out.println("[DEBUG] DashboardController.index() called");
        return "forward:/static/index.html"; 
    }

    /** REST fallback — full snapshot as JSON (for initial page load). */
    @GetMapping("/api/snapshot")
    @ResponseBody
    public DashboardSnapshot snapshot() {
        System.out.println("[DEBUG] DashboardController.snapshot() called");
        try {
            DashboardSnapshot result = dataService.buildSnapshot();
            System.out.println("[DEBUG] DashboardController.snapshot() returned snapshot with " + 
                (result.getLanes() != null ? result.getLanes().size() : 0) + " lanes, " +
                (result.getPollution() != null ? "pollution data" : "no pollution data"));
            return result;
        } catch (Exception e) {
            System.err.println("[DEBUG] DashboardController.snapshot() failed: " + e.getMessage());
            e.printStackTrace();
            throw e;
        }
    }

    /** Historical lane data for sparklines — last N minutes. */
    @GetMapping("/api/lane/{laneId}/history")
    @ResponseBody
    public ResponseEntity<?> laneHistory(@PathVariable String laneId,
                                          @RequestParam(defaultValue = "30") int minutes) {
        System.out.println("[DEBUG] DashboardController.laneHistory() called for lane: " + laneId + ", minutes: " + minutes);
        try {
            String sql = """
                SELECT UNIX_TIMESTAMP(recorded_at)*1000 AS ts,
                       vehicle_count, noise_db, flux_level, camera_status
                FROM   lane_readings
                WHERE  zone_id = 'MAIN-STREET-ROTARY'
                  AND  lane_id = ?
                  AND  recorded_at >= NOW() - INTERVAL ? MINUTE
                ORDER  BY recorded_at
                """;
            var rows = jdbc.queryForList(sql, laneId, minutes);
            System.out.println("[DEBUG] DashboardController.laneHistory() returned " + rows.size() + " rows");
            return ResponseEntity.ok(rows);
        } catch (Exception e) {
            System.err.println("[DEBUG] DashboardController.laneHistory() failed: " + e.getMessage());
            e.printStackTrace();
            throw e;
        }
    }

    /** Historical pollution for chart — last N minutes. */
    @GetMapping("/api/pollution/history")
    @ResponseBody
    public ResponseEntity<?> pollutionHistory(@RequestParam(defaultValue = "30") int minutes) {
        System.out.println("[DEBUG] DashboardController.pollutionHistory() called for minutes: " + minutes);
        try {
            String sql = """
                SELECT UNIX_TIMESTAMP(recorded_at)*1000 AS ts, pm25, aqi_category
                FROM   pollution_readings
                WHERE  zone_id = 'MAIN-STREET-ROTARY'
                  AND  recorded_at >= NOW() - INTERVAL ? MINUTE
                ORDER  BY recorded_at
                """;
            var result = jdbc.queryForList(sql, minutes);
            System.out.println("[DEBUG] DashboardController.pollutionHistory() returned " + result.size() + " rows");
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            System.err.println("[DEBUG] DashboardController.pollutionHistory() failed: " + e.getMessage());
            e.printStackTrace();
            throw e;
        }
    }

    /** Resolve an alert. */
    @PostMapping("/api/alert/{id}/resolve")
    @ResponseBody
    public ResponseEntity<?> resolveAlert(@PathVariable long id) {
        System.out.println("[DEBUG] DashboardController.resolveAlert() called for alert id: " + id);
        try {
            int updated = jdbc.update(
                "UPDATE alerts SET resolved=1, resolved_at=NOW(3) WHERE id=?", id);
            System.out.println("[DEBUG] DashboardController.resolveAlert() updated " + updated + " rows");
            return ResponseEntity.ok(Map.of("resolved", updated > 0));
        } catch (Exception e) {
            System.err.println("[DEBUG] DashboardController.resolveAlert() failed: " + e.getMessage());
            e.printStackTrace();
            throw e;
        }
    }

    /** Hourly summary for a lane (for the trend chart). */
    @GetMapping("/api/lane/{laneId}/hourly")
    @ResponseBody
    public ResponseEntity<?> laneHourly(@PathVariable String laneId,
                                         @RequestParam(defaultValue = "24") int hours) {
        System.out.println("[DEBUG] DashboardController.laneHourly() called for lane: " + laneId + ", hours: " + hours);
        try {
            String sql = """
                SELECT UNIX_TIMESTAMP(hour_bucket)*1000 AS ts,
                       avg_vehicles, max_vehicles, avg_noise_db, accident_count
                FROM   lane_hourly_summary
                WHERE  zone_id = 'MAIN-STREET-ROTARY'
                  AND  lane_id = ?
                  AND  hour_bucket >= NOW() - INTERVAL ? HOUR
                ORDER  BY hour_bucket
                """;
            var result = jdbc.queryForList(sql, laneId, hours);
            System.out.println("[DEBUG] DashboardController.laneHourly() returned " + result.size() + " rows");
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            System.err.println("[DEBUG] DashboardController.laneHourly() failed: " + e.getMessage());
            e.printStackTrace();
            throw e;
        }
    }
}