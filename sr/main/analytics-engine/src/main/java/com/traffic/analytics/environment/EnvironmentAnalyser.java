package com.traffic.analytics.environment;

import com.traffic.analytics.model.AnalyticsResult;
import com.traffic.analytics.model.AnalyticsResult.Category;
import com.traffic.analytics.model.AnalyticsResult.Severity;
import com.traffic.analytics.model.LaneSnapshot;
import com.traffic.analytics.model.PollutionSnapshot;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Environment Analyser
 * ────────────────────
 * Processes pollution and noise data to produce environmental
 * {@link AnalyticsResult} objects.
 *
 * Checks performed:
 *
 *  POLLUTION
 *   P1. Instantaneous AQI level — maps to severity.
 *   P2. Rising PM2.5 trend      — slope over the window.
 *   P3. Sustained high AQI      — persistence counter.
 *
 *  NOISE
 *   N1. Per-lane noise level    — threshold-based.
 *   N2. Zone-wide noise average — aggregate alert.
 *   N3. Noise + congestion correlation — combined alert when both are bad.
 */
public class EnvironmentAnalyser {

    // ── PM2.5 thresholds (µg/m³) ─────────────────────────────────────────────
    private static final double PM25_MODERATE       = 12.0;
    private static final double PM25_UNHEALTHY      = 35.0;
    private static final double PM25_VERY_UNHEALTHY = 55.0;
    private static final double PM25_HAZARDOUS      = 150.0;

    // ── PM2.5 trend slope ────────────────────────────────────────────────────
    private static final double PM25_SLOPE_WARNING  = 1.5;   // µg/m³ per tick
    private static final double PM25_SLOPE_CRITICAL = 4.0;

    // ── PM2.5 persistence ────────────────────────────────────────────────────
    private static final int    PM25_PERSIST_TICKS  = 4;     // consecutive unhealthy ticks

    // ── Noise thresholds (dB) ─────────────────────────────────────────────────
    private static final double NOISE_MODERATE  = 60.0;
    private static final double NOISE_HIGH      = 70.0;
    private static final double NOISE_CRITICAL  = 80.0;

    // ════════════════════════════════════════════════════════════════════════
    //  Public API
    // ════════════════════════════════════════════════════════════════════════

    public List<AnalyticsResult> analyse(String zoneId,
                                          List<PollutionSnapshot> pollWindow,
                                          List<LaneSnapshot>      laneWindow) {
        List<AnalyticsResult> results = new ArrayList<>();

        // ── Pollution checks ─────────────────────────────────────────────
        if (pollWindow != null && !pollWindow.isEmpty()) {
            results.addAll(pollutionInstantaneous(zoneId, pollWindow));
            results.addAll(pollutionTrend(zoneId, pollWindow));
            results.addAll(pollutionPersistence(zoneId, pollWindow));
        }

        // ── Noise checks ─────────────────────────────────────────────────
        if (laneWindow != null && !laneWindow.isEmpty()) {
            results.addAll(noisePerLane(zoneId, laneWindow));
            results.addAll(noiseZoneAverage(zoneId, laneWindow));
            if (pollWindow != null && !pollWindow.isEmpty()) {
                results.addAll(noisePollutionCorrelation(zoneId, pollWindow, laneWindow));
            }
        }

        return results;
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Pollution — P1 instantaneous
    // ════════════════════════════════════════════════════════════════════════

    private List<AnalyticsResult> pollutionInstantaneous(String zoneId,
                                                          List<PollutionSnapshot> window) {
        PollutionSnapshot latest = window.get(window.size() - 1);
        double pm25 = latest.getPm25();

        Severity sev;
        String   label;

        if      (pm25 > PM25_HAZARDOUS)      { sev = Severity.CRITICAL; label = "Hazardous"; }
        else if (pm25 > PM25_VERY_UNHEALTHY) { sev = Severity.HIGH;     label = "Very Unhealthy"; }
        else if (pm25 > PM25_UNHEALTHY)      { sev = Severity.MEDIUM;   label = "Unhealthy"; }
        else if (pm25 > PM25_MODERATE)       { sev = Severity.LOW;      label = "Moderate"; }
        else                                  { return List.of(); }   // Good — no alert

        return List.of(AnalyticsResult.builder(Category.ENVIRONMENT, zoneId)
            .severity(sev)
            .title("Air quality: " + label + " (PM2.5=" + String.format("%.1f", pm25) + " µg/m³)")
            .description(String.format(
                "Zone %s PM2.5 level is %.1f µg/m³ — categorised as %s. "
                + "AQI: %s. Recorded at %s.",
                zoneId, pm25, label, latest.getAqiCategory(), latest.getRecordedAt()))
            .metric("pm25",        pm25)
            .metric("aqiCategory", latest.getAqiCategory())
            .build());
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Pollution — P2 trend
    // ════════════════════════════════════════════════════════════════════════

    private List<AnalyticsResult> pollutionTrend(String zoneId, List<PollutionSnapshot> window) {
        if (window.size() < 3) return List.of();

        int n = window.size();
        double sumX=0, sumY=0, sumXY=0, sumX2=0;
        for (int i = 0; i < n; i++) {
            double x = i, y = window.get(i).getPm25();
            sumX += x; sumY += y; sumXY += x*y; sumX2 += x*x;
        }
        double slope = (n*sumXY - sumX*sumY) / (n*sumX2 - sumX*sumX + 1e-9);

        if (slope >= PM25_SLOPE_CRITICAL) {
            return List.of(AnalyticsResult.builder(Category.ENVIRONMENT, zoneId)
                .severity(Severity.CRITICAL)
                .title("Rapid PM2.5 increase detected")
                .description(String.format(
                    "PM2.5 is rising at %.2f µg/m³ per tick. Immediate ventilation / traffic "
                    + "restriction recommended.", slope))
                .metric("pm25Slope",   slope)
                .metric("windowTicks", n)
                .build());
        }
        if (slope >= PM25_SLOPE_WARNING) {
            return List.of(AnalyticsResult.builder(Category.ENVIRONMENT, zoneId)
                .severity(Severity.MEDIUM)
                .title("Rising PM2.5 trend")
                .description(String.format(
                    "PM2.5 trending upward at %.2f µg/m³ per tick over %d readings.", slope, n))
                .metric("pm25Slope",   slope)
                .metric("windowTicks", n)
                .build());
        }
        return List.of();
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Pollution — P3 persistence
    // ════════════════════════════════════════════════════════════════════════

    private List<AnalyticsResult> pollutionPersistence(String zoneId,
                                                        List<PollutionSnapshot> window) {
        int consecutive = 0;
        for (int i = window.size() - 1; i >= 0; i--) {
            if (window.get(i).getPm25() > PM25_UNHEALTHY) consecutive++;
            else break;
        }
        if (consecutive >= PM25_PERSIST_TICKS) {
            return List.of(AnalyticsResult.builder(Category.ENVIRONMENT, zoneId)
                .severity(Severity.HIGH)
                .title("Sustained poor air quality (" + consecutive + " consecutive readings)")
                .description(String.format(
                    "PM2.5 has exceeded the Unhealthy threshold for %d consecutive ticks (%d s). "
                    + "Consider traffic volume reduction.", consecutive, consecutive * 10))
                .metric("consecutiveTicks", consecutive)
                .metric("durationSeconds",  consecutive * 10)
                .build());
        }
        return List.of();
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Noise — N1 per-lane
    // ════════════════════════════════════════════════════════════════════════

    private List<AnalyticsResult> noisePerLane(String zoneId, List<LaneSnapshot> window) {
        List<AnalyticsResult> results = new ArrayList<>();

        // Take the latest reading per lane
        Map<String, LaneSnapshot> latestPerLane = new LinkedHashMap<>();
        for (LaneSnapshot s : window) latestPerLane.put(s.getLaneId(), s);

        for (Map.Entry<String, LaneSnapshot> e : latestPerLane.entrySet()) {
            String laneId = e.getKey();
            double db     = e.getValue().getNoiseDb();

            Severity sev;
            String   label;
            if      (db >= NOISE_CRITICAL) { sev = Severity.HIGH;   label = "Critical"; }
            else if (db >= NOISE_HIGH)     { sev = Severity.MEDIUM; label = "High";     }
            else if (db >= NOISE_MODERATE) { sev = Severity.LOW;    label = "Moderate"; }
            else                           { continue; }

            results.add(AnalyticsResult.builder(Category.ENVIRONMENT, zoneId)
                .severity(sev)
                .laneId(laneId)
                .title(String.format("Noise level %s on lane %s (%.1f dB)", label, laneId, db))
                .description(String.format(
                    "Lane %s noise reading of %.1f dB exceeds %s threshold.", laneId, db, label))
                .metric("noiseDb",    db)
                .metric("threshold",  label)
                .build());
        }
        return results;
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Noise — N2 zone average
    // ════════════════════════════════════════════════════════════════════════

    private List<AnalyticsResult> noiseZoneAverage(String zoneId, List<LaneSnapshot> window) {
        // Average of the latest reading per lane
        Map<String, Double> latestDb = new LinkedHashMap<>();
        for (LaneSnapshot s : window) latestDb.put(s.getLaneId(), s.getNoiseDb());

        double avg = latestDb.values().stream().mapToDouble(Double::doubleValue).average().orElse(0);
        double max = latestDb.values().stream().mapToDouble(Double::doubleValue).max().orElse(0);

        if (avg < NOISE_HIGH) return List.of();

        return List.of(AnalyticsResult.builder(Category.ENVIRONMENT, zoneId)
            .severity(avg >= NOISE_CRITICAL ? Severity.HIGH : Severity.MEDIUM)
            .title(String.format("Zone-wide elevated noise: avg=%.1f dB max=%.1f dB", avg, max))
            .description(String.format(
                "Average noise across all lanes is %.1f dB (max %.1f dB). "
                + "Noise pollution threshold exceeded zone-wide.", avg, max))
            .metric("avgNoiseDb",  avg)
            .metric("maxNoiseDb",  max)
            .metric("laneCount",   latestDb.size())
            .build());
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Noise + Pollution — N3 correlation
    // ════════════════════════════════════════════════════════════════════════

    private List<AnalyticsResult> noisePollutionCorrelation(String zoneId,
                                                             List<PollutionSnapshot> pollWindow,
                                                             List<LaneSnapshot>      laneWindow) {
        PollutionSnapshot latestPoll = pollWindow.get(pollWindow.size() - 1);
        double pm25 = latestPoll.getPm25();

        double avgNoise = laneWindow.stream()
            .collect(Collectors.groupingBy(LaneSnapshot::getLaneId,
                     Collectors.maxBy(Comparator.comparing(LaneSnapshot::getRecordedAt))))
            .values().stream()
            .filter(Optional::isPresent)
            .mapToDouble(o -> o.get().getNoiseDb())
            .average().orElse(0);

        if (pm25 > PM25_UNHEALTHY && avgNoise > NOISE_HIGH) {
            return List.of(AnalyticsResult.builder(Category.ENVIRONMENT, zoneId)
                .severity(Severity.HIGH)
                .title("Combined pollution + noise hazard")
                .description(String.format(
                    "PM2.5=%.1f µg/m³ AND average noise=%.1f dB are simultaneously above safe "
                    + "thresholds. Compounded environmental risk for residents and pedestrians.",
                    pm25, avgNoise))
                .metric("pm25",        pm25)
                .metric("avgNoiseDb",  avgNoise)
                .build());
        }
        return List.of();
    }
}
