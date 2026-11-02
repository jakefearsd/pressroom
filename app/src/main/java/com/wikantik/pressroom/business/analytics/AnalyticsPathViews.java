package com.wikantik.pressroom.business.analytics;

import java.io.Serializable;
import java.util.Objects;

/**
 * Cached views of one path of a weblog in a period.
 * Plain entity, mapped in AnalyticsCache.orm.xml; imports no business-tier type.
 */
public class AnalyticsPathViews {

    private String weblogId;
    private String period;
    private String path;
    private String entryId;
    private long views;
    private long visitors;

    /** Required by JPA. */
    public AnalyticsPathViews() {
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

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public String getEntryId() {
        return entryId;
    }

    public void setEntryId(String entryId) {
        this.entryId = entryId;
    }

    public long getViews() {
        return views;
    }

    public void setViews(long views) {
        this.views = views;
    }

    public long getVisitors() {
        return visitors;
    }

    public void setVisitors(long visitors) {
        this.visitors = visitors;
    }

    /** Composite primary key. */
    public static final class Key implements Serializable {
        private static final long serialVersionUID = 1L;
        private String weblogId;
        private String period;
        private String path;

        /** Required by JPA. */
        public Key() {
            // fields are set through the constructor or by JPA
        }

        public Key(String weblogId, String period, String path) {
            this.weblogId = weblogId;
            this.period = period;
            this.path = path;
        }

        public String getWeblogId() {
            return weblogId;
        }

        public String getPeriod() {
            return period;
        }

        public String getPath() {
            return path;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Key that
                    && Objects.equals(weblogId, that.weblogId)
                    && Objects.equals(period, that.period)
                    && Objects.equals(path, that.path);
        }

        @Override
        public int hashCode() {
            return Objects.hash(weblogId, period, path);
        }
    }
}
