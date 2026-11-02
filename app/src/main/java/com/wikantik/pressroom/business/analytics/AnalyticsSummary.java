package com.wikantik.pressroom.business.analytics;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;

/**
 * One weblog's cached traffic totals for a period and the one before it.
 * Plain entity, mapped in AnalyticsCache.orm.xml; imports no business-tier type.
 */
public class AnalyticsSummary {

    private String weblogId;
    private String period;
    private Instant asOf;
    private long visitors;
    private long visits;
    private long views;
    private long bounces;
    private long totalTime;
    private long prevVisitors;
    private long prevVisits;
    private long prevViews;
    private long prevBounces;
    private long prevTotalTime;

    /** Required by JPA. */
    public AnalyticsSummary() {
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

    public Instant getAsOf() {
        return asOf;
    }

    public void setAsOf(Instant asOf) {
        this.asOf = asOf;
    }

    public long getVisitors() {
        return visitors;
    }

    public void setVisitors(long visitors) {
        this.visitors = visitors;
    }

    public long getVisits() {
        return visits;
    }

    public void setVisits(long visits) {
        this.visits = visits;
    }

    public long getViews() {
        return views;
    }

    public void setViews(long views) {
        this.views = views;
    }

    public long getBounces() {
        return bounces;
    }

    public void setBounces(long bounces) {
        this.bounces = bounces;
    }

    public long getTotalTime() {
        return totalTime;
    }

    public void setTotalTime(long totalTime) {
        this.totalTime = totalTime;
    }

    public long getPrevVisitors() {
        return prevVisitors;
    }

    public void setPrevVisitors(long prevVisitors) {
        this.prevVisitors = prevVisitors;
    }

    public long getPrevVisits() {
        return prevVisits;
    }

    public void setPrevVisits(long prevVisits) {
        this.prevVisits = prevVisits;
    }

    public long getPrevViews() {
        return prevViews;
    }

    public void setPrevViews(long prevViews) {
        this.prevViews = prevViews;
    }

    public long getPrevBounces() {
        return prevBounces;
    }

    public void setPrevBounces(long prevBounces) {
        this.prevBounces = prevBounces;
    }

    public long getPrevTotalTime() {
        return prevTotalTime;
    }

    public void setPrevTotalTime(long prevTotalTime) {
        this.prevTotalTime = prevTotalTime;
    }

    /** Composite primary key. */
    public static final class Key implements Serializable {
        private static final long serialVersionUID = 1L;
        private String weblogId;
        private String period;

        /** Required by JPA. */
        public Key() {
            // fields are set through the constructor or by JPA
        }

        public Key(String weblogId, String period) {
            this.weblogId = weblogId;
            this.period = period;
        }

        public String getWeblogId() {
            return weblogId;
        }

        public String getPeriod() {
            return period;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Key that
                    && Objects.equals(weblogId, that.weblogId)
                    && Objects.equals(period, that.period);
        }

        @Override
        public int hashCode() {
            return Objects.hash(weblogId, period);
        }
    }
}
