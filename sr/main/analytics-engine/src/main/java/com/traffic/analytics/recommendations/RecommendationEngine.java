package com.traffic.analytics.recommendations;

import com.traffic.analytics.model.AnalyticsResult;
import com.traffic.analytics.model.AnalyticsResult.Category;
import com.traffic.analytics.model.AnalyticsResult.Severity;
import com.traffic.analytics.model.LaneSnapshot;
import com.traffic.analytics.model.PollutionSnapshot;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Recommendation Engine
 * ─────────────────────
 * Consumes the results from the three analysers (congestion, environment,
 * alerts) plus raw sensor snapshots and produces actionable, prioritised
 * {@link AnalyticsResult} objects of category RECOMMENDATION.
 *
 * Rules applied (highest-priority first):
 *
 *  R1. ACCIDENT / incident detected  → red all incoming lights, dispatch unit.
 *  R2. Spillback risk                → extend green for spillback-affected entry.
 *  R3. Persistent congestion         → adaptive green extension per lane.
 *  R4. Zone-wide congestion          → activate alternate routing advisory.
 *  R5. Hazardous air quality         → restrict traffic volume.
 *  R6. Rising PM2.5 trend            → reduce green cycle time to lower idle emissions.
 *  R7. Critical noise                → reduce traffic speed advisory.
 *  R8. Noise + pollution combined    → immediate volume restriction.
 *  R9. Stale / unresolved alerts     → escalate to operator.
 * R10. Low-traffic lane              → consolidate green time to another direction.
 */
public class RecommendationEngine {

    // ── Direction → associated lane ids ─────────────────────────────────────
    private static final Map<String, List<String>> DIR_LANES = Map.of(
        "NORTH", List.of("NORTH-IN", "NORTH-OUT"),
        "SOUTH", List.of("SOUTH-IN", "SOUTH-OUT"),
        "EAST",  List.of("EAST-IN",  "EAST-OUT"),
        "WEST",  List.of("WEST-IN",  "WEST-OUT")
    );

    // ════════════════════════════════════════════════════════════════════════
    //  Public API
    // ════════════════════════════════════════════════════════════════════════

    /**
     * @param zoneId         rotary zone id
     * @param congestionResults  output of CongestionAnalyser
     * @param envResults         output of EnvironmentAnalyser
     * @param alertResults       output of AlertAnalyser
     * @param latestLanes        latest reading per lane (for direct metric access)
     * @param latestPoll         latest pollution reading (nullable)
     */
    public List<AnalyticsResult> recommend(
            String                 zoneId,
            List<AnalyticsResult>  congestionResults,
            List<AnalyticsResult>  envResults,
            List<AnalyticsResult>  alertResults,
            List<LaneSnapshot>     latestLanes,
            PollutionSnapshot      latestPoll) {

        List<AnalyticsResult> recs = new ArrayList<>();

        // Build fast-access maps
        Map<String, LaneSnapshot> laneMap = latestLanes.stream()
            .collect(Collectors.toMap(LaneSnapshot::getLaneId, s -> s, (a, b) -> b));

        Set<String> congestedLanes = latestLanes.stream()
            .filter(s -> "CONGESTED".equals(s.getFluxLevel()) || "HIGH".equals(s.getFluxLevel()))
            .map(LaneSnapshot::getLaneId)
            .collect(Collectors.toSet());

        Set<String> incidentLanes = latestLanes.stream()
            .filter(s -> !"NORMAL".equals(s.getCameraStatus()))
            .map(LaneSnapshot::getLaneId)
            .collect(Collectors.toSet());

        // ── R1 — Incident / accident ─────────────────────────────────────
        recs.addAll(incidentRecommendations(zoneId, latestLanes, incidentLanes));

        // ── R2 — Spillback ───────────────────────────────────────────────
        recs.addAll(spillbackRecommendations(zoneId, congestionResults, laneMap));

        // ── R3 — Persistent congestion ───────────────────────────────────
        recs.addAll(persistentCongestionRecommendations(zoneId, congestionResults));

        // ── R4 — Zone-wide congestion ────────────────────────────────────
        recs.addAll(zoneWideCongestionRecommendations(zoneId, congestionResults, congestedLanes));

        // ── R5/R6 — Air quality ──────────────────────────────────────────
        if (latestPoll != null) {
            recs.addAll(airQualityRecommendations(zoneId, latestPoll, envResults));
        }

        // ── R7/R8 — Noise ────────────────────────────────────────────────
        recs.addAll(noiseRecommendations(zoneId, envResults, latestPoll));

        // ── R9 — Stale alerts ────────────────────────────────────────────
        recs.addAll(staleAlertEscalation(zoneId, alertResults));

        // ── R10 — Low-traffic rebalancing ────────────────────────────────
        recs.addAll(lowTrafficRebalancing(zoneId, laneMap, congestedLanes));

        // De-duplicate by title (same recommendation can be triggered by multiple rules)
        Set<String> seen = new LinkedHashSet<>();
        return recs.stream()
            .filter(r -> seen.add(r.getTitle()))
            .sorted(Comparator.comparingInt((AnalyticsResult r) -> r.getSeverity().ordinal()).reversed())
            .collect(Collectors.toList());
    }

    // ════════════════════════════════════════════════════════════════════════
    //  R1 — Incidents
    // ════════════════════════════════════════════════════════════════════════

    private List<AnalyticsResult> incidentRecommendations(String zoneId,
                                                           List<LaneSnapshot> lanes,
                                                           Set<String> incidentLanes) {
        List<AnalyticsResult> recs = new ArrayList<>();

        for (LaneSnapshot s : lanes) {
            if ("NORMAL".equals(s.getCameraStatus())) continue;

            String dir     = directionOf(s.getLaneId());
            String details = s.getCameraDetails() != null ? s.getCameraDetails() : "";

            switch (s.getCameraStatus()) {
                case "ACCIDENT" -> {
                    // Red all lights except opposite direction for clearance
                    List<String> toRed = DIR_LANES.keySet().stream()
                        .filter(d -> !d.equals(opposite(dir)))
                        .collect(Collectors.toList());

                    recs.add(AnalyticsResult.builder(Category.RECOMMENDATION, zoneId)
                        .severity(Severity.CRITICAL)
                        .laneId(s.getLaneId())
                        .title("ACCIDENT on " + s.getLaneId() + " — set RED: " + String.join(", ", toRed))
                        .description(String.format(
                            "Accident detected on lane %s (%s). "
                            + "Recommended: set lights %s to RED to clear the zone. "
                            + "Keep %s GREEN as escape corridor. Dispatch emergency unit.",
                            s.getLaneId(), details, String.join(", ", toRed), opposite(dir)))
                        .metric("incidentLane",   s.getLaneId())
                        .metric("incidentType",   s.getCameraStatus())
                        .metric("lightsToRed",    String.join(",", toRed))
                        .metric("escapeCorridor", opposite(dir))
                        .build());

                    recs.add(AnalyticsResult.builder(Category.RECOMMENDATION, zoneId)
                        .severity(Severity.CRITICAL)
                        .laneId(s.getLaneId())
                        .title("Dispatch emergency unit to " + s.getLaneId())
                        .description("Accident severity: " + details
                            + ". Dispatch ambulance/police immediately.")
                        .metric("targetLane", s.getLaneId())
                        .metric("action",     "DISPATCH_EMERGENCY")
                        .build());
                }

                case "PEDESTRIAN_ON_ROAD" -> recs.add(
                    AnalyticsResult.builder(Category.RECOMMENDATION, zoneId)
                        .severity(Severity.HIGH)
                        .laneId(s.getLaneId())
                        .title("Pedestrian on road — set " + dir + " light to RED")
                        .description(String.format(
                            "Pedestrian detected on lane %s (%s). Halt incoming traffic on %s direction.",
                            s.getLaneId(), details, dir))
                        .metric("targetLight", "TL-" + zoneId + "-" + dir)
                        .metric("newState",    "RED")
                        .build());

                case "STOPPED_VEHICLE" -> recs.add(
                    AnalyticsResult.builder(Category.RECOMMENDATION, zoneId)
                        .severity(Severity.MEDIUM)
                        .laneId(s.getLaneId())
                        .title("Stopped vehicle on " + s.getLaneId() + " — send traffic officer")
                        .description(String.format(
                            "Stopped vehicle on lane %s (%s). May block traffic flow. "
                            + "Recommend dispatch of traffic officer or tow service.",
                            s.getLaneId(), details))
                        .metric("targetLane", s.getLaneId())
                        .metric("action",     "DISPATCH_TRAFFIC_OFFICER")
                        .build());

                case "WRONG_WAY" -> recs.add(
                    AnalyticsResult.builder(Category.RECOMMENDATION, zoneId)
                        .severity(Severity.CRITICAL)
                        .laneId(s.getLaneId())
                        .title("WRONG-WAY driver on " + s.getLaneId() + " — immediate action")
                        .description(String.format(
                            "Wrong-way vehicle detected on lane %s (%s). "
                            + "Red all lights immediately and broadcast alert.",
                            s.getLaneId(), details))
                        .metric("targetLane", s.getLaneId())
                        .metric("action",     "RED_ALL_LIGHTS_BROADCAST")
                        .build());

                default -> {}
            }
        }
        return recs;
    }

    // ════════════════════════════════════════════════════════════════════════
    //  R2 — Spillback
    // ════════════════════════════════════════════════════════════════════════

    private List<AnalyticsResult> spillbackRecommendations(String zoneId,
                                                            List<AnalyticsResult> congestion,
                                                            Map<String, LaneSnapshot> laneMap) {
        return congestion.stream()
            .filter(r -> r.getTitle().contains("Spillback"))
            .map(r -> {
                String exitLane  = String.valueOf(r.getMetrics().get("exitLane"));
                String entryLane = String.valueOf(r.getMetrics().get("riskEntryLane"));
                String dir       = directionOf(exitLane);

                return AnalyticsResult.builder(Category.RECOMMENDATION, zoneId)
                    .severity(Severity.HIGH)
                    .laneId(exitLane)
                    .title("Extend GREEN for " + opposite(dir) + " to relieve spillback on " + exitLane)
                    .description(String.format(
                        "Exit lane %s is congested. Extend GREEN time for the %s direction to "
                        + "drain rotary and prevent blocking entry %s.",
                        exitLane, opposite(dir), entryLane))
                    .metric("exitLane",        exitLane)
                    .metric("extendDirection", opposite(dir))
                    .metric("action",          "EXTEND_GREEN")
                    .build();
            })
            .collect(Collectors.toList());
    }

    // ════════════════════════════════════════════════════════════════════════
    //  R3 — Persistent congestion
    // ════════════════════════════════════════════════════════════════════════

    private List<AnalyticsResult> persistentCongestionRecommendations(String zoneId,
                                                                        List<AnalyticsResult> congestion) {
        return congestion.stream()
            .filter(r -> r.getTitle().startsWith("Persistent congestion"))
            .map(r -> {
                String laneId = r.getLaneId();
                String dir    = directionOf(laneId);
                long   ticks  = ((Number) r.getMetrics().getOrDefault("consecutiveTicks", 0)).longValue();

                return AnalyticsResult.builder(Category.RECOMMENDATION, zoneId)
                    .severity(Severity.HIGH)
                    .laneId(laneId)
                    .title("Extend GREEN for " + dir + " (+15s) — persistent congestion " + ticks + " ticks")
                    .description(String.format(
                        "Lane %s has been congested for %d consecutive ticks (%d s). "
                        + "Recommend extending GREEN phase for %s direction by 15 seconds.",
                        laneId, ticks, ticks * 10, dir))
                    .metric("targetLight",   "TL-" + zoneId + "-" + dir)
                    .metric("extensionSec",  15)
                    .metric("action",        "EXTEND_GREEN")
                    .build();
            })
            .collect(Collectors.toList());
    }

    // ════════════════════════════════════════════════════════════════════════
    //  R4 — Zone-wide congestion
    // ════════════════════════════════════════════════════════════════════════

    private List<AnalyticsResult> zoneWideCongestionRecommendations(String zoneId,
                                                                      List<AnalyticsResult> congestion,
                                                                      Set<String> congestedLanes) {
        boolean zoneWide = congestion.stream()
            .anyMatch(r -> r.getTitle().startsWith("Zone-wide congestion"));
        if (!zoneWide) return List.of();

        return List.of(
            AnalyticsResult.builder(Category.RECOMMENDATION, zoneId)
                .severity(Severity.CRITICAL)
                .title("Activate alternate routing advisory for zone " + zoneId)
                .description(String.format(
                    "%d lanes are congested zone-wide. Recommend activating variable message signs "
                    + "on all approach roads to divert non-essential traffic.",
                    congestedLanes.size()))
                .metric("congestedLanes", congestedLanes.size())
                .metric("action",         "ACTIVATE_DIVERSION_SIGNS")
                .build(),
            AnalyticsResult.builder(Category.RECOMMENDATION, zoneId)
                .severity(Severity.HIGH)
                .title("Notify traffic management centre — zone-wide congestion")
                .description("Zone " + zoneId + " is fully congested. Operator notification required.")
                .metric("action", "NOTIFY_TMC")
                .build()
        );
    }

    // ════════════════════════════════════════════════════════════════════════
    //  R5/R6 — Air quality
    // ════════════════════════════════════════════════════════════════════════

    private List<AnalyticsResult> airQualityRecommendations(String zoneId,
                                                             PollutionSnapshot poll,
                                                             List<AnalyticsResult> envResults) {
        List<AnalyticsResult> recs = new ArrayList<>();
        double pm25 = poll.getPm25();

        if (pm25 > 150) {          // Hazardous
            recs.add(AnalyticsResult.builder(Category.RECOMMENDATION, zoneId)
                .severity(Severity.CRITICAL)
                .title("HAZARDOUS air quality — restrict traffic volume immediately")
                .description(String.format(
                    "PM2.5=%.1f µg/m³ (Hazardous). Recommend: shorten all GREEN phases by 40%% "
                    + "to reduce idle emissions, activate emission alerts, notify health authorities.",
                    pm25))
                .metric("pm25",           pm25)
                .metric("greenReduction", "40%")
                .metric("action",         "RESTRICT_TRAFFIC_VOLUME")
                .build());
        } else if (pm25 > 55) {    // Very Unhealthy
            recs.add(AnalyticsResult.builder(Category.RECOMMENDATION, zoneId)
                .severity(Severity.HIGH)
                .title("Very unhealthy air — shorten GREEN phases by 20%")
                .description(String.format(
                    "PM2.5=%.1f µg/m³ (Very Unhealthy). Reduce traffic dwell time by shortening "
                    + "GREEN cycles to minimise idle emissions.", pm25))
                .metric("pm25",           pm25)
                .metric("greenReduction", "20%")
                .metric("action",         "SHORTEN_GREEN_PHASES")
                .build());
        }

        // R6 — Rising trend
        boolean risingTrend = envResults.stream()
            .anyMatch(r -> r.getTitle().contains("PM2.5") && r.getTitle().contains("trend"));
        if (risingTrend && pm25 > 12) {
            recs.add(AnalyticsResult.builder(Category.RECOMMENDATION, zoneId)
                .severity(Severity.MEDIUM)
                .title("Rising PM2.5 — reduce cycle time by 10% to limit idling")
                .description("PM2.5 is trending upward. Proactively reduce green cycle duration "
                    + "to decrease vehicle idling time and slow pollution accumulation.")
                .metric("action", "REDUCE_CYCLE_TIME")
                .metric("pm25",   pm25)
                .build());
        }

        return recs;
    }

    // ════════════════════════════════════════════════════════════════════════
    //  R7/R8 — Noise
    // ════════════════════════════════════════════════════════════════════════

    private List<AnalyticsResult> noiseRecommendations(String zoneId,
                                                        List<AnalyticsResult> envResults,
                                                        PollutionSnapshot poll) {
        List<AnalyticsResult> recs = new ArrayList<>();

        boolean criticalNoise = envResults.stream()
            .anyMatch(r -> r.getTitle().contains("Critical") && r.getTitle().contains("Noise"));
        boolean combined = envResults.stream()
            .anyMatch(r -> r.getTitle().contains("Combined pollution + noise"));

        if (combined) {
            recs.add(AnalyticsResult.builder(Category.RECOMMENDATION, zoneId)
                .severity(Severity.HIGH)
                .laneId(null)
                .title("Combined hazard: restrict volume + 30 km/h speed advisory")
                .description("Both noise and pollution are simultaneously above safe thresholds. "
                    + "Issue 30 km/h speed advisory on all approach roads and reduce traffic volume.")
                .metric("action",       "SPEED_ADVISORY + VOLUME_RESTRICTION")
                .metric("advisedSpeed", 30)
                .build());
        } else if (criticalNoise) {
            recs.add(AnalyticsResult.builder(Category.RECOMMENDATION, zoneId)
                .severity(Severity.MEDIUM)
                .title("Issue 40 km/h speed advisory — critical noise levels")
                .description("One or more lanes have noise above 80 dB. A lower speed advisory "
                    + "will reduce tyre and engine noise significantly.")
                .metric("action",       "SPEED_ADVISORY")
                .metric("advisedSpeed", 40)
                .build());
        }
        return recs;
    }

    // ════════════════════════════════════════════════════════════════════════
    //  R9 — Stale alert escalation
    // ════════════════════════════════════════════════════════════════════════

    private List<AnalyticsResult> staleAlertEscalation(String zoneId,
                                                         List<AnalyticsResult> alertResults) {
        return alertResults.stream()
            .filter(r -> r.getTitle().startsWith("Stale unresolved alert"))
            .map(r -> AnalyticsResult.builder(Category.RECOMMENDATION, zoneId)
                .severity(Severity.HIGH)
                .title("Escalate to operator: " + r.getTitle())
                .description("Alert has not been resolved within the expected window. "
                    + "Escalate to human operator. Details: " + r.getDescription())
                .metric("sourceAlertTitle", r.getTitle())
                .metric("action",           "ESCALATE_TO_OPERATOR")
                .build())
            .collect(Collectors.toList());
    }

    // ════════════════════════════════════════════════════════════════════════
    //  R10 — Low-traffic rebalancing
    // ════════════════════════════════════════════════════════════════════════

    private List<AnalyticsResult> lowTrafficRebalancing(String zoneId,
                                                          Map<String, LaneSnapshot> laneMap,
                                                          Set<String> congestedLanes) {
        List<AnalyticsResult> recs = new ArrayList<>();

        // Find IN lanes with LOW flux while others are HIGH
        boolean anyHighIn = congestedLanes.stream().anyMatch(l -> l.endsWith("-IN"));

        laneMap.forEach((laneId, s) -> {
            if (!laneId.endsWith("-IN")) return;
            if (!"LOW".equals(s.getFluxLevel())) return;
            if (!anyHighIn) return;   // no congested peer — no action needed

            String dir          = directionOf(laneId);
            String oppDir       = opposite(dir);

            recs.add(AnalyticsResult.builder(Category.RECOMMENDATION, zoneId)
                .severity(Severity.LOW)
                .laneId(laneId)
                .title("Transfer green time from " + dir + " (low) to " + oppDir + " (congested)")
                .description(String.format(
                    "Lane %s has very low traffic (%d vehicles). Transfer 10s of GREEN time from "
                    + "%s to %s to rebalance flow.", laneId, s.getVehicleCount(), dir, oppDir))
                .metric("lowLane",        laneId)
                .metric("transferFrom",   dir)
                .metric("transferTo",     oppDir)
                .metric("transferSec",    10)
                .metric("action",         "REBALANCE_GREEN_TIME")
                .build());
        });

        return recs;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static String directionOf(String laneId) {
        if (laneId == null) return "UNKNOWN";
        return laneId.contains("-") ? laneId.split("-")[0] : laneId;
    }

    private static String opposite(String dir) {
        return switch (dir) {
            case "NORTH" -> "SOUTH";
            case "SOUTH" -> "NORTH";
            case "EAST"  -> "WEST";
            default      -> "EAST";
        };
    }
}
