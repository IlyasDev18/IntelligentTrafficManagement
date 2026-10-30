package com.traffic.analytics.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Universal result container produced by every analyser.
 *
 * Fields
 * ──────
 *  category    : CONGESTION | ENVIRONMENT | ALERT | RECOMMENDATION
 *  severity    : INFO | LOW | MEDIUM | HIGH | CRITICAL
 *  zoneId      : rotary zone this result belongs to
 *  laneId      : specific lane (null if zone-wide)
 *  title       : short human-readable summary
 *  description : full detail
 *  metrics     : key/value map of supporting numeric data
 *  generatedAt : epoch-ms timestamp
 */
public class AnalyticsResult {

    public enum Category   { CONGESTION, ENVIRONMENT, ALERT, RECOMMENDATION }
    public enum Severity   { INFO, LOW, MEDIUM, HIGH, CRITICAL }

    private final Category           category;
    private final Severity           severity;
    private final String             zoneId;
    private final String             laneId;        // nullable
    private final String             title;
    private final String             description;
    private final Map<String,Object> metrics;
    private final long               generatedAt;

    private AnalyticsResult(Builder b) {
        this.category    = b.category;
        this.severity    = b.severity;
        this.zoneId      = b.zoneId;
        this.laneId      = b.laneId;
        this.title       = b.title;
        this.description = b.description;
        this.metrics     = Map.copyOf(b.metrics);
        this.generatedAt = b.generatedAt;
    }

    // ── Getters ──────────────────────────────────────────────────────────────
    public Category           getCategory()    { return category;    }
    public Severity           getSeverity()    { return severity;    }
    public String             getZoneId()      { return zoneId;      }
    public String             getLaneId()      { return laneId;      }
    public String             getTitle()       { return title;       }
    public String             getDescription() { return description; }
    public Map<String,Object> getMetrics()     { return metrics;     }
    public long               getGeneratedAt() { return generatedAt; }

    // ── JSON serialisation (no external lib) ─────────────────────────────────
    public String toJson() {
        StringBuilder sb = new StringBuilder("{\n");
        kv(sb, "category",    category.name());
        kv(sb, "severity",    severity.name());
        kv(sb, "zoneId",      zoneId);
        kv(sb, "laneId",      laneId);
        kv(sb, "title",       title);
        kv(sb, "description", description);
        sb.append("  \"metrics\": {");
        if (!metrics.isEmpty()) {
            sb.append("\n");
            var it = metrics.entrySet().iterator();
            while (it.hasNext()) {
                var e = it.next();
                sb.append("    ").append(qs(e.getKey())).append(": ");
                Object v = e.getValue();
                if (v instanceof Number) sb.append(v);
                else sb.append(qs(String.valueOf(v)));
                if (it.hasNext()) sb.append(",");
                sb.append("\n");
            }
            sb.append("  }");
        } else { sb.append("}"); }
        sb.append(",\n");
        sb.append("  \"generatedAt\": ").append(generatedAt).append("\n}");
        return sb.toString();
    }

    private static void kv(StringBuilder sb, String k, String v) {
        sb.append("  ").append(qs(k)).append(": ")
          .append(v == null ? "null" : qs(v)).append(",\n");
    }
    private static String qs(String s) {
        return "\"" + s.replace("\"", "\\\"") + "\"";
    }

    @Override
    public String toString() { return title + " [" + severity + "]"; }

    // ── Builder ──────────────────────────────────────────────────────────────
    public static Builder builder(Category category, String zoneId) {
        return new Builder(category, zoneId);
    }

    public static class Builder {
        private final Category       category;
        private final String         zoneId;
        private Severity             severity    = Severity.INFO;
        private String               laneId      = null;
        private String               title       = "";
        private String               description = "";
        private final Map<String,Object> metrics = new LinkedHashMap<>();
        private long                 generatedAt = Instant.now().toEpochMilli();

        private Builder(Category category, String zoneId) {
            this.category = category;
            this.zoneId   = zoneId;
        }

        public Builder severity(Severity s)          { this.severity    = s;  return this; }
        public Builder laneId(String l)              { this.laneId      = l;  return this; }
        public Builder title(String t)               { this.title       = t;  return this; }
        public Builder description(String d)         { this.description = d;  return this; }
        public Builder metric(String k, Object v)    { this.metrics.put(k,v); return this; }
        public Builder generatedAt(long ts)          { this.generatedAt = ts; return this; }
        public AnalyticsResult build()               { return new AnalyticsResult(this); }
    }
}
