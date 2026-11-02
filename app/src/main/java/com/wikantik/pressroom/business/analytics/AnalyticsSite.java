package com.wikantik.pressroom.business.analytics;

import java.time.Instant;

/**
 * Umami registration of one weblog; absent until the first attempt.
 * Plain entity, mapped in AnalyticsCache.orm.xml; imports no business-tier type.
 */
public class AnalyticsSite {

    private String weblogId;
    private String websiteId;
    private String syncedName;
    private String syncedDomain;
    private Instant registeredAt;
    private Instant lastAttempt;
    private String lastError;

    /** Required by JPA. */
    public AnalyticsSite() {
        // fields are set through the setters
    }

    public String getWeblogId() {
        return weblogId;
    }

    public void setWeblogId(String weblogId) {
        this.weblogId = weblogId;
    }

    public String getWebsiteId() {
        return websiteId;
    }

    public void setWebsiteId(String websiteId) {
        this.websiteId = websiteId;
    }

    public String getSyncedName() {
        return syncedName;
    }

    public void setSyncedName(String syncedName) {
        this.syncedName = syncedName;
    }

    public String getSyncedDomain() {
        return syncedDomain;
    }

    public void setSyncedDomain(String syncedDomain) {
        this.syncedDomain = syncedDomain;
    }

    public Instant getRegisteredAt() {
        return registeredAt;
    }

    public void setRegisteredAt(Instant registeredAt) {
        this.registeredAt = registeredAt;
    }

    public Instant getLastAttempt() {
        return lastAttempt;
    }

    public void setLastAttempt(Instant lastAttempt) {
        this.lastAttempt = lastAttempt;
    }

    public String getLastError() {
        return lastError;
    }

    public void setLastError(String lastError) {
        this.lastError = lastError;
    }

}
