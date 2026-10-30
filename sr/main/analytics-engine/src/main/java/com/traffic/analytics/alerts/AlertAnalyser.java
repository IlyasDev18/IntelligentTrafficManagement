package com.traffic.analytics.alerts;

import com.traffic.analytics.model.AlertRecord;
import com.traffic.analytics.model.AnalyticsResult;
import com.traffic.analytics.model.AnalyticsResult.Category;
import com.traffic.analytics.model.AnalyticsResult.Severity;

import java.util.*;
import java.util.stream.Collectors;


public class AlertAnalyser {

    // ── Thresholds ────────────────────────────────────────────────────────────
    private static final int    STORM_THRESHOLD_COUNT   = 10;   // alerts in window
    private static final int    RECURRING_MIN_HITS      = 3;    // same target in window
    private static final long   STALE_THRESHOLD_SECONDS = 300L; // 5 minutes

    // ════════════════════════════════════════════════════════════════════════
    //  Public API
    // ════════════════════════════════════════════════════════════════════════

    public List<AnalyticsResult> analyse(String zoneId,
                                          List<AlertRecord> active,
                                          List<AlertRecord> windowAlerts) {
        List<AnalyticsResult> results = new ArrayList<>();

        // A1 — Active high-severity
        results.addAll(activeHighSeverity(zoneId, active));

        // A2 — Storm detection
        results.addAll(stormDetection(zoneId, windowAlerts));

        // A3 — Recurring targets
        results.addAll(recurringTargets(zoneId, windowAlerts));

        // A4 — Stale unresolved
        results.addAll(staleAlerts(zoneId, active));

        // A5 — Rate summary
        results.add(rateSummary(zoneId, windowAlerts));

        return results;
    }

    // ════════════════════════════════════════════════════════════════════════
    //  A1 — Escalate active high severity
    // ════════════════════════════════════════════════════════════════════════

    private List<AnalyticsResult> activeHighSeverity(String zoneId, List<AlertRecord> active) {
        return active.stream()
            .filter(a -> "HIGH".equals(a.getSeverity()) || "CRITICAL".equals(a.getSeverity()))
            .map(a -> AnalyticsResult.builder(Category.ALERT, zoneId)
                .severity("CRITICAL".equals(a.getSeverity()) ? Severity.CRITICAL : Severity.HIGH)
                .title("Unresolved " + a.getSeverity() + " alert: " + a.getActionType()
                       + " → " + a.getTarget())
                .description(String.format(
                    "Alert #%d has not been resolved. Action: %s on %s. Reason: %s. "
                    + "Triggered at %s.",
                    a.getId(), a.getActionType(), a.getTarget(),
                    a.getReason(), a.getRecordedAt()))
                .metric("alertId",    a.getId())
                .metric("actionType", a.getActionType())
                .metric("target",     a.getTarget())
                .build())
            .collect(Collectors.toList());
    }

    // ════════════════════════════════════════════════════════════════════════
    //  A2 — Alert storm
    // ════════════════════════════════════════════════════════════════════════

    private List<AnalyticsResult> stormDetection(String zoneId, List<AlertRecord> window) {
        if (window.size() < STORM_THRESHOLD_COUNT) return List.of();

        long highCount = window.stream()
            .filter(a -> "HIGH".equals(a.getSeverity()) || "CRITICAL".equals(a.getSeverity()))
            .count();

        // Group by type for breakdown
        Map<String, Long> byType = window.stream()
            .collect(Collectors.groupingBy(AlertRecord::getActionType, Collectors.counting()));

        String breakdown = byType.entrySet().stream()
            .sorted(Map.Entry.<String,Long>comparingByValue().reversed())
            .map(e -> e.getKey() + "×" + e.getValue())
            .collect(Collectors.joining(", "));

        return List.of(AnalyticsResult.builder(Category.ALERT, zoneId)
            .severity(highCount > 5 ? Severity.CRITICAL : Severity.HIGH)
            .title("Alert storm: " + window.size() + " alerts in window (" + highCount + " HIGH/CRITICAL)")
            .description(String.format(
                "Unusually high number of alerts (%d) generated in the analysis window. "
                + "Breakdown: [%s]. Possible sensor malfunction or major incident.",
                window.size(), breakdown))
            .metric("totalAlerts", window.size())
            .metric("highAlerts",  highCount)
            .build());
    }

    // ════════════════════════════════════════════════════════════════════════
    //  A3 — Recurring targets
    // ════════════════════════════════════════════════════════════════════════

    private List<AnalyticsResult> recurringTargets(String zoneId, List<AlertRecord> window) {
        List<AnalyticsResult> results = new ArrayList<>();

        Map<String, Long> hitsByTarget = window.stream()
            .collect(Collectors.groupingBy(AlertRecord::getTarget, Collectors.counting()));

        hitsByTarget.forEach((target, count) -> {
            if (count >= RECURRING_MIN_HITS) {
                // Determine dominant action type for this target
                Map<String, Long> actionCounts = window.stream()
                    .filter(a -> target.equals(a.getTarget()))
                    .collect(Collectors.groupingBy(AlertRecord::getActionType, Collectors.counting()));
                String dominantAction = actionCounts.entrySet().stream()
                    .max(Map.Entry.comparingByValue())
                    .map(Map.Entry::getKey).orElse("UNKNOWN");

                results.add(AnalyticsResult.builder(Category.ALERT, zoneId)
                    .severity(count >= RECURRING_MIN_HITS * 2 ? Severity.HIGH : Severity.MEDIUM)
                    .title("Recurring alerts on target: " + target + " (" + count + " times)")
                    .description(String.format(
                        "Target %s has been flagged %d times in the current window. "
                        + "Dominant action: %s. Possible structural issue or persistent incident.",
                        target, count, dominantAction))
                    .metric("target",        target)
                    .metric("alertCount",    count)
                    .metric("dominantAction", dominantAction)
                    .build());
            }
        });

        return results;
    }

    // ════════════════════════════════════════════════════════════════════════
    //  A4 — Stale unresolved alerts
    // ════════════════════════════════════════════════════════════════════════

    private List<AnalyticsResult> staleAlerts(String zoneId, List<AlertRecord> active) {
        List<AnalyticsResult> results = new ArrayList<>();
        long now = System.currentTimeMillis() / 1000L;

        for (AlertRecord a : active) {
            long ageSeconds = now - a.getRecordedAt().getEpochSecond();
            if (ageSeconds > STALE_THRESHOLD_SECONDS) {
                results.add(AnalyticsResult.builder(Category.ALERT, zoneId)
                    .severity(Severity.HIGH)
                    .title("Stale unresolved alert #" + a.getId() + " (" + formatAge(ageSeconds) + " old)")
                    .description(String.format(
                        "Alert #%d (%s → %s) has been open for %s without resolution. "
                        + "Reason: %s.",
                        a.getId(), a.getActionType(), a.getTarget(),
                        formatAge(ageSeconds), a.getReason()))
                    .metric("alertId",    a.getId())
                    .metric("ageSeconds", ageSeconds)
                    .metric("actionType", a.getActionType())
                    .build());
            }
        }
        return results;
    }

    // ════════════════════════════════════════════════════════════════════════
    //  A5 — Rate summary (always produced as INFO)
    // ════════════════════════════════════════════════════════════════════════

    private AnalyticsResult rateSummary(String zoneId, List<AlertRecord> window) {
        long total    = window.size();
        long high     = window.stream().filter(a -> "HIGH".equals(a.getSeverity())).count();
        long critical = window.stream().filter(a -> "CRITICAL".equals(a.getSeverity())).count();
        long resolved = window.stream().filter(AlertRecord::isResolved).count();

        Map<String, Long> byType = window.stream()
            .collect(Collectors.groupingBy(AlertRecord::getActionType, Collectors.counting()));

        StringBuilder desc = new StringBuilder();
        desc.append(String.format("Window total=%d | high=%d | critical=%d | resolved=%d.",
                total, high, critical, resolved));
        desc.append(" Breakdown by type: ");
        byType.entrySet().stream()
            .sorted(Map.Entry.<String,Long>comparingByValue().reversed())
            .forEach(e -> desc.append(e.getKey()).append("=").append(e.getValue()).append(" "));

        AnalyticsResult.Builder b = AnalyticsResult.builder(Category.ALERT, zoneId)
            .severity(Severity.INFO)
            .title("Alert rate summary: " + total + " alerts in window")
            .description(desc.toString())
            .metric("totalAlerts",    total)
            .metric("highAlerts",     high)
            .metric("criticalAlerts", critical)
            .metric("resolvedAlerts", resolved);

        byType.forEach((type, count) -> b.metric("type_" + type.toLowerCase(), count));
        return b.build();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String formatAge(long seconds) {
        if (seconds < 60)   return seconds + "s";
        if (seconds < 3600) return (seconds / 60) + "m " + (seconds % 60) + "s";
        return (seconds / 3600) + "h " + ((seconds % 3600) / 60) + "m";
    }
}
