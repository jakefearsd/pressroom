package com.wikantik.pressroom.business.analytics;

import java.io.Serializable;
import java.util.Objects;

/**
 * Cached JSON series or key/count list of one kind for a weblog and period.
 * Plain entity, mapped in AnalyticsCache.orm.xml; imports no business-tier type.
 */
public class AnalyticsBreakdown {

    private String weblogId;
    private String period;
    private String kind;
    private String payload;

    /** Required by JPA. */
    public AnalyticsBreakdown() {
        // fields are set through the setters
    }

    public String getWeblogId() {
        return weblogId;
    }

    public void setWeblogId(String weblogId) {
        this.weblogId = weblogId;
    }

    public String getPeriod() {
        return period;
    }

    public void setPeriod(String period) {
        this.period = period;
    }

    public String getKind() {
        return kind;
    }

    public void setKind(String kind) {
        this.kind = kind;
    }

    public String getPayload() {
        return payload;
    }

    public void setPayload(String payload) {
        this.payload = payload;
    }

    /** Composite primary key. */
    public static final class Key implements Serializable {
        private static final long serialVersionUID = 1L;
        private String weblogId;
        private String period;
        private String kind;

        /** Required by JPA. */
        public Key() {
            // fields are set through the constructor or by JPA
        }

        public Key(String weblogId, String period, String kind) {
            this.weblogId = weblogId;
            this.period = period;
            this.kind = kind;
        }

        public String getWeblogId() {
            return weblogId;
        }

        public String getPeriod() {
            return period;
        }

        public String getKind() {
            return kind;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Key that
                    && Objects.equals(weblogId, that.weblogId)
                    && Objects.equals(period, that.period)
                    && Objects.equals(kind, that.kind);
        }

        @Override
        public int hashCode() {
            return Objects.hash(weblogId, period, kind);
        }
    }
}
