package com.city.sensors.simulator;

import com.city.sensors.model.LaneData;
import com.city.sensors.model.RecommendedAction;
import com.city.sensors.model.RotarySnapshot;

import java.util.List;
import java.util.Map;

/**
 * Lightweight JSON serializer — no external dependencies required.
 * Produces human-readable, pretty-printed JSON matching the project spec.
 */
public class JsonSerializer {

    private static final String INDENT = "  ";

    public static String toJson(RotarySnapshot snap) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");

        field(sb, 1, "zoneId",    snap.getZoneId());
        field(sb, 1, "type",      snap.getType());
        fieldLong(sb, 1, "timestamp", snap.getTimestamp());

        // pollutionData is already a JSON string — stored as an escaped string value
        sb.append(indent(1)).append("\"pollutionData\": ")
          .append(jsonString(snap.getPollutionData())).append(",\n");

        // trafficLightData object
        sb.append(indent(1)).append("\"trafficLightData\": {\n");
        Map<String, String> tl = snap.getTrafficLightData();
        int tlIdx = 0;
        for (Map.Entry<String, String> e : tl.entrySet()) {
            boolean last = (++tlIdx == tl.size());
            sb.append(indent(2)).append(jsonString(e.getKey()))
              .append(": ").append(jsonString(e.getValue()))
              .append(last ? "\n" : ",\n");
        }
        sb.append(indent(1)).append("},\n");

        // laneData object
        sb.append(indent(1)).append("\"laneData\": {\n");
        Map<String, LaneData> lanes = snap.getLaneData();
        int laneIdx = 0;
        for (Map.Entry<String, LaneData> entry : lanes.entrySet()) {
            boolean lastLane = (++laneIdx == lanes.size());
            LaneData ld = entry.getValue();
            sb.append(indent(2)).append(jsonString(entry.getKey())).append(": {\n");
            sb.append(indent(3)).append("\"flux\": ").append(jsonString(ld.getFlux())).append(",\n");
            sb.append(indent(3)).append("\"vehicleCount\": ").append(ld.getVehicleCount()).append(",\n");
            sb.append(indent(3)).append("\"noise\": ").append(jsonString(ld.getNoise())).append(",\n");
            sb.append(indent(3)).append("\"cameraStatus\": ").append(jsonString(ld.getCameraStatus())).append(",\n");
            String details = ld.getCameraDetails();
            sb.append(indent(3)).append("\"cameraDetails\": ")
              .append(details == null ? "null" : jsonString(details))
              .append("\n");
            sb.append(indent(2)).append(lastLane ? "}\n" : "},\n");
        }
        sb.append(indent(1)).append("},\n");

        // recommendedActions array
        List<RecommendedAction> acts = snap.getRecommendedActions();
        sb.append(indent(1)).append("\"recommendedActions\": [\n");
        for (int i = 0; i < acts.size(); i++) {
            boolean last = (i == acts.size() - 1);
            RecommendedAction a = acts.get(i);
            sb.append(indent(2)).append("{\n");
            sb.append(indent(3)).append("\"action\": ").append(jsonString(a.getAction())).append(",\n");
            sb.append(indent(3)).append("\"target\": ").append(jsonString(a.getTarget())).append(",\n");
            sb.append(indent(3)).append("\"newValue\": ").append(jsonString(a.getNewValue())).append(",\n");
            sb.append(indent(3)).append("\"reason\": ").append(jsonString(a.getReason())).append("\n");
            sb.append(indent(2)).append(last ? "}\n" : "},\n");
        }
        sb.append(indent(1)).append("]\n");

        sb.append("}");
        return sb.toString();
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private static void field(StringBuilder sb, int depth, String key, String value) {
        sb.append(indent(depth)).append(jsonString(key)).append(": ")
          .append(jsonString(value)).append(",\n");
    }

    private static void fieldLong(StringBuilder sb, int depth, String key, long value) {
        sb.append(indent(depth)).append(jsonString(key)).append(": ")
          .append(value).append(",\n");
    }

    private static String indent(int depth) {
        return INDENT.repeat(depth);
    }

    /** Wraps a string in double-quotes and escapes internal quotes/backslashes. */
    private static String jsonString(String s) {
        if (s == null) return "null";
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
