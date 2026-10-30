package com.traffic.analytics.congestion;

import com.traffic.analytics.model.AnalyticsResult;
import com.traffic.analytics.model.AnalyticsResult.Category;
import com.traffic.analytics.model.AnalyticsResult.Severity;
import com.traffic.analytics.model.LaneSnapshot;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Congestion Analyser
 * ───────────────────
 * Processes a window of recent lane readings and produces congestion
 * {@link AnalyticsResult} objects.
 *
 * Detection strategies applied in order:
 *
 *  1. INSTANTANEOUS  — last reading flux level per lane.
 *  2. TREND          — is vehicle count rising across the window? (linear slope)
 *  3. PERSISTENCE    — was a lane stuck at HIGH/CONGESTED for > threshold ticks?
 *  4. SPILLBACK      — if exit lanes are congested, flag spillback risk.
 *  5. ZONE-WIDE      — if ≥ half of all lanes are HIGH or worse, emit zone alert.
 */
public class CongestionAnalyser {

    // ── Thresholds ────────────────────────────────────────────────────────────
    private static final int    PERSISTENCE_TICKS   = 3;    // consecutive HIGH/CONGESTED ticks
    private static final double TREND_SLOPE_WARNING = 2.5;  // vehicles/tick slope → warning
    private static final double TREND_SLOPE_CRITICAL = 5.0; // vehicles/tick slope → critical
    private static final double ZONE_WIDE_FRACTION  = 0.5;  // fraction of lanes congested for zone alert

    public List<AnalyticsResult> analyse(String zoneId, List<LaneSnapshot> window) {
        if (window == null || window.isEmpty()) return List.of();

        List<AnalyticsResult> results = new ArrayList<>();

        // Group readings by laneId (preserving time order — DB returns ordered)
        Map<String, List<LaneSnapshot>> byLane = window.stream()
            .collect(Collectors.groupingBy(LaneSnapshot::getLaneId,
                     LinkedHashMap::new, Collectors.toList()));

        int congestionCount = 0;

        for (Map.Entry<String, List<LaneSnapshot>> entry : byLane.entrySet()) {
            String              laneId   = entry.getKey();
            List<LaneSnapshot>  readings = entry.getValue();
            LaneSnapshot        latest   = readings.get(readings.size() - 1);

            // ── 1. Instantaneous ────────────────────────────────────────────
            AnalyticsResult instant = instantaneous(zoneId, laneId, latest);
            if (instant != null) {
                results.add(instant);
                if (instant.getSeverity().ordinal() >= Severity.HIGH.ordinal()) congestionCount++;
            }

            // ── 2. Trend ─────────────────────────────────────────────────────
            if (readings.size() >= 3) {
                AnalyticsResult trend = trendAnalysis(zoneId, laneId, readings);
                if (trend != null) results.add(trend);
            }

            // ── 3. Persistence ───────────────────────────────────────────────
            AnalyticsResult persist = persistenceCheck(zoneId, laneId, readings);
            if (persist != null) results.add(persist);

            // ── 4. Spillback (only for OUT lanes) ────────────────────────────
            if (laneId.endsWith("-OUT")) {
                AnalyticsResult spill = spillbackCheck(zoneId, laneId, latest);
                if (spill != null) results.add(spill);
            }
        }

        // ── 5. Zone-wide ─────────────────────────────────────────────────────
        if (byLane.size() > 0) {
            double fraction = (double) congestionCount / byLane.size();
            if (fraction >= ZONE_WIDE_FRACTION) {
                results.add(AnalyticsResult.builder(Category.CONGESTION, zoneId)
                    .severity(Severity.CRITICAL)
                    .title("Zone-wide congestion detected")
                    .description(String.format(
                        "%d/%d lanes are heavily congested (%.0f%%). Immediate action required.",
                        congestionCount, byLane.size(), fraction * 100))
                    .metric("congestedLanes",  congestionCount)
                    .metric("totalLanes",       byLane.size())
                    .metric("congestedFraction", fraction)
                    .build());
            }
        }

        return results;
    }

    // ── Strategy 1 — instantaneous flux level ────────────────────────────────

    private AnalyticsResult instantaneous(String zoneId, String laneId, LaneSnapshot s) {
        Severity sev = switch (s.getFluxLevel()) {
            case "LOW"       -> null;              // normal — no result
            case "OK"        -> null;
            case "HIGH"      -> Severity.MEDIUM;
            case "CONGESTED" -> Severity.HIGH;
            default          -> null;
        };
        if (sev == null) return null;

        return AnalyticsResult.builder(Category.CONGESTION, zoneId)
            .severity(sev)
            .laneId(laneId)
            .title("Congestion on lane " + laneId + ": " + s.getFluxLevel())
            .description(String.format(
                "Lane %s reports flux=%s with %d vehicles at %s.",
                laneId, s.getFluxLevel(), s.getVehicleCount(), s.getRecordedAt()))
            .metric("fluxLevel",    s.getFluxLevel())
            .metric("vehicleCount", s.getVehicleCount())
            .metric("noiseDb",      s.getNoiseDb())
            .build();
    }

    // ── Strategy 2 — linear slope of vehicle count ───────────────────────────

    private AnalyticsResult trendAnalysis(String zoneId, String laneId,
                                          List<LaneSnapshot> readings) {
        // Simple least-squares slope over vehicle counts
        int n = readings.size();
        double sumX = 0, sumY = 0, sumXY = 0, sumX2 = 0;
        for (int i = 0; i < n; i++) {
            double x = i;
            double y = readings.get(i).getVehicleCount();
            sumX  += x;  sumY  += y;
            sumXY += x * y; sumX2 += x * x;
        }
        double slope = (n * sumXY - sumX * sumY) / (n * sumX2 - sumX * sumX + 1e-9);

        if (slope >= TREND_SLOPE_CRITICAL) {
            return AnalyticsResult.builder(Category.CONGESTION, zoneId)
                .severity(Severity.CRITICAL)
                .laneId(laneId)
                .title("Rapid congestion build-up on " + laneId)
                .description(String.format(
                    "Vehicle count is increasing at %.1f vehicles/tick on lane %s. Critical build-up.",
                    slope, laneId))
                .metric("trendSlope", slope)
                .metric("windowTicks", n)
                .build();
        }
        if (slope >= TREND_SLOPE_WARNING) {
            return AnalyticsResult.builder(Category.CONGESTION, zoneId)
                .severity(Severity.MEDIUM)
                .laneId(laneId)
                .title("Rising traffic trend on " + laneId)
                .description(String.format(
                    "Vehicle count growing at %.1f vehicles/tick on lane %s.",
                    slope, laneId))
                .metric("trendSlope", slope)
                .metric("windowTicks", n)
                .build();
        }
        return null;
    }

    // ── Strategy 3 — persistence of high flux ────────────────────────────────

    private AnalyticsResult persistenceCheck(String zoneId, String laneId,
                                             List<LaneSnapshot> readings) {
        if (readings.size() < PERSISTENCE_TICKS) return null;

        // Count trailing consecutive HIGH or CONGESTED ticks
        int consecutive = 0;
        for (int i = readings.size() - 1; i >= 0; i--) {
            String flux = readings.get(i).getFluxLevel();
            if ("HIGH".equals(flux) || "CONGESTED".equals(flux)) consecutive++;
            else break;
        }

        if (consecutive >= PERSISTENCE_TICKS) {
            return AnalyticsResult.builder(Category.CONGESTION, zoneId)
                .severity(Severity.HIGH)
                .laneId(laneId)
                .title("Persistent congestion on " + laneId)
                .description(String.format(
                    "Lane %s has been at HIGH/CONGESTED for %d consecutive ticks (%d s). "
                    + "Sustained blockage likely.",
                    laneId, consecutive, consecutive * 10))
                .metric("consecutiveTicks",   consecutive)
                .metric("durationSeconds",    consecutive * 10)
                .metric("lastVehicleCount",   readings.get(readings.size()-1).getVehicleCount())
                .build();
        }
        return null;
    }

    // ── Strategy 4 — spillback from exit lane ────────────────────────────────

    private AnalyticsResult spillbackCheck(String zoneId, String laneId, LaneSnapshot s) {
        if (!"CONGESTED".equals(s.getFluxLevel()) && !"HIGH".equals(s.getFluxLevel())) return null;

        String direction = laneId.replace("-OUT", "");
        String inLane    = direction + "-IN";

        return AnalyticsResult.builder(Category.CONGESTION, zoneId)
            .severity(Severity.HIGH)
            .laneId(laneId)
            .title("Spillback risk: exit lane " + laneId + " is blocked")
            .description(String.format(
                "Exit lane %s is %s (%d vehicles). Risk of rotary spillback blocking %s entry.",
                laneId, s.getFluxLevel(), s.getVehicleCount(), inLane))
            .metric("exitLane",      laneId)
            .metric("riskEntryLane", inLane)
            .metric("vehicleCount",  s.getVehicleCount())
            .build();
    }
}
