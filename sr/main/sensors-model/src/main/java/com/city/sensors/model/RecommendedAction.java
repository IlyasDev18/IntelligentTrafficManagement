package com.city.sensors.model;

/**
 * An action recommended by the decision engine in response to sensor data.
 */
public class RecommendedAction {

    private final String action;      // e.g. CHANGE_LIGHT, DISPATCH_UNIT, ALERT
    private final String target;      // sensor / light id
    private final String newValue;    // e.g. RED, GREEN
    private final String reason;      // human-readable

    public RecommendedAction(String action, String target, String newValue, String reason) {
        this.action   = action;
        this.target   = target;
        this.newValue = newValue;
        this.reason   = reason;
    }

    public String getAction()   { return action;   }
    public String getTarget()   { return target;   }
    public String getNewValue() { return newValue; }
    public String getReason()   { return reason;   }
}
