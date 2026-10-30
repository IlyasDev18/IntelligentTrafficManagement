package com.traffic.analytics.model;

import java.time.Instant;

/**
 * One row from the {@code alerts} table.
 */
public class AlertRecord {

    private final long    id;
    private final String  zoneId;
    private final String  actionType;
    private final String  target;
    private final String  newValue;
    private final String  reason;
    private final String  severity;
    private final boolean resolved;
    private final Instant recordedAt;

    public AlertRecord(long id, String zoneId, String actionType, String target,
                       String newValue, String reason, String severity,
                       boolean resolved, Instant recordedAt) {
        this.id          = id;
        this.zoneId      = zoneId;
        this.actionType  = actionType;
        this.target      = target;
        this.newValue    = newValue;
        this.reason      = reason;
        this.severity    = severity;
        this.resolved    = resolved;
        this.recordedAt  = recordedAt;
    }

    public long    getId()          { return id;          }
    public String  getZoneId()      { return zoneId;      }
    public String  getActionType()  { return actionType;  }
    public String  getTarget()      { return target;      }
    public String  getNewValue()    { return newValue;    }
    public String  getReason()      { return reason;      }
    public String  getSeverity()    { return severity;    }
    public boolean isResolved()     { return resolved;    }
    public Instant getRecordedAt()  { return recordedAt;  }
}
