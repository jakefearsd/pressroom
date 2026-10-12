/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  The ASF licenses this file to You
 * under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.  For additional information regarding
 * copyright in this work, please see the NOTICE file in the top level
 * directory of this distribution.
 */

package org.apache.roller.weblogger.ui.controllers.editor;

import org.apache.commons.lang3.StringUtils;
import java.math.BigDecimal;
import java.util.regex.Pattern;

import org.apache.roller.weblogger.WebloggerException;
import org.apache.roller.weblogger.business.BusinessManager;
import org.apache.roller.weblogger.pojos.Business;
import org.apache.roller.weblogger.pojos.Weblog;


/**
 * Bean used to manage data submitted to WeblogConfig action.
 */
public class WeblogConfigBean {
    
    private String handle = null;
    private String name = null;
    private String tagline = null;
    private String emailAddress = null;
    private String locale = null;
    private String timeZone = null;
    private int entryDisplayCount = 15;
    private boolean active = false;
    private boolean commentsEnabled = false;
    private boolean commentNotify = false;
    private String icon = null;
    private String about = null;

    private String analyticsSiteId = null;
    private String analyticsShareUrl = null;
    private String newsletterListUuid = null;
    private String customDomain = null;

    private String businessId = null;
    private String placeType = null;
    private String placeLocality = null;
    private String placeRegion = null;
    private String placeCountry = null;
    // Latitude and longitude arrive as text so a typo is a field error on the
    // form, not a binding failure that never reaches the controller.
    private String placeLat = null;
    private String placeLng = null;
    private String bookingUrl = null;


    public String getHandle() {
        return this.handle;
    }
    
    public void setHandle( String handle ) {
        this.handle = handle;
    }
    
    public String getName() {
        return this.name;
    }
    
    public void setName( String name ) {
        this.name = name;
    }
    
    public String getTagline() {
        return this.tagline;
    }
    
    public void setTagline( String tagline ) {
        this.tagline = tagline;
    }
    
    public String getEmailAddress() {
        return this.emailAddress;
    }
    
    public void setEmailAddress( String emailAddress ) {
        this.emailAddress = emailAddress;
    }
    
    public String getLocale() {
        return this.locale;
    }
    
    public void setLocale( String locale ) {
        this.locale = locale;
    }
    
    public String getTimeZone() {
        return this.timeZone;
    }
    
    public void setTimeZone( String timeZone ) {
        this.timeZone = timeZone;
    }
    
    public int getEntryDisplayCount() {
        return this.entryDisplayCount;
    }
    
    public void setEntryDisplayCount( int entryDisplayCount ) {
        this.entryDisplayCount = entryDisplayCount;
    }
    
    public String getIcon() {
        return icon;
    }

    public void setIcon(String icon) {
        this.icon = icon;
    }

    public String getAbout() {
        return about;
    }

    public void setAbout(String about) {
        this.about = about;
    }
    
    public boolean getCommentsEnabled() {
        return commentsEnabled;
    }

    public void setCommentsEnabled(boolean commentsEnabled) {
        this.commentsEnabled = commentsEnabled;
    }

    public boolean getCommentNotify() {
        return commentNotify;
    }

    public void setCommentNotify(boolean commentNotify) {
        this.commentNotify = commentNotify;
    }

    public boolean getActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public String getAnalyticsSiteId() {
        return analyticsSiteId;
    }

    public void setAnalyticsSiteId(String analyticsSiteId) {
        this.analyticsSiteId = analyticsSiteId;
    }

    public String getAnalyticsShareUrl() {
        return analyticsShareUrl;
    }

    public void setAnalyticsShareUrl(String analyticsShareUrl) {
        this.analyticsShareUrl = analyticsShareUrl;
    }

    public String getNewsletterListUuid() {
        return newsletterListUuid;
    }

    public void setNewsletterListUuid(String newsletterListUuid) {
        this.newsletterListUuid = newsletterListUuid;
    }

    public String getCustomDomain() {
        return customDomain;
    }

    public void setCustomDomain(String customDomain) {
        this.customDomain = customDomain;
    }

    public String getBusinessId() {
        return businessId;
    }

    public void setBusinessId(String businessId) {
        this.businessId = businessId;
    }

    public String getPlaceType() {
        return placeType;
    }

    public void setPlaceType(String placeType) {
        this.placeType = placeType;
    }

    public String getPlaceLocality() {
        return placeLocality;
    }

    public void setPlaceLocality(String placeLocality) {
        this.placeLocality = placeLocality;
    }

    public String getPlaceRegion() {
        return placeRegion;
    }

    public void setPlaceRegion(String placeRegion) {
        this.placeRegion = placeRegion;
    }

    public String getPlaceCountry() {
        return placeCountry;
    }

    public void setPlaceCountry(String placeCountry) {
        this.placeCountry = placeCountry;
    }

    public String getPlaceLat() {
        return placeLat;
    }

    public void setPlaceLat(String placeLat) {
        this.placeLat = placeLat;
    }

    public String getPlaceLng() {
        return placeLng;
    }

    public void setPlaceLng(String placeLng) {
        this.placeLng = placeLng;
    }

    public String getBookingUrl() {
        return bookingUrl;
    }

    public void setBookingUrl(String bookingUrl) {
        this.bookingUrl = bookingUrl;
    }

    public void copyFrom(Weblog dataHolder) {
        
        this.handle = dataHolder.getHandle();
        this.name = dataHolder.getName();
        this.tagline = dataHolder.getTagline();
        this.emailAddress = dataHolder.getEmailAddress();
        this.locale = dataHolder.getLocale();
        this.timeZone = dataHolder.getTimeZone();
        this.entryDisplayCount = dataHolder.getEntryDisplayCount();
        setActive(dataHolder.getActive());
        setCommentsEnabled(Boolean.TRUE.equals(dataHolder.getCommentsEnabled()));
        setCommentNotify(!Boolean.FALSE.equals(dataHolder.getCommentNotify()));
        this.analyticsSiteId = dataHolder.getAnalyticsSiteId();
        this.analyticsShareUrl = dataHolder.getAnalyticsShareUrl();
        this.newsletterListUuid = dataHolder.getNewsletterListUuid();
        this.customDomain = dataHolder.getCustomDomain();
        setIcon(dataHolder.getIconPath());
        setAbout(dataHolder.getAbout());
        this.businessId = dataHolder.getBusiness() == null ? null : dataHolder.getBusiness().getId();
        this.placeType = dataHolder.getPlaceType();
        this.placeLocality = dataHolder.getPlaceLocality();
        this.placeRegion = dataHolder.getPlaceRegion();
        this.placeCountry = dataHolder.getPlaceCountry();
        this.placeLat = dataHolder.getPlaceLat() == null ? null : dataHolder.getPlaceLat().toPlainString();
        this.placeLng = dataHolder.getPlaceLng() == null ? null : dataHolder.getPlaceLng().toPlainString();
        this.bookingUrl = dataHolder.getBookingUrl();
    }
    
    
    public void copyTo(Weblog dataHolder) {
        dataHolder.setName(this.name);
        dataHolder.setTagline(this.tagline);
        dataHolder.setEmailAddress(this.emailAddress);
        dataHolder.setLocale(this.locale);
        dataHolder.setTimeZone(this.timeZone);
        dataHolder.setEntryDisplayCount(this.entryDisplayCount);
        dataHolder.setActive(this.getActive());
        dataHolder.setCommentsEnabled(this.getCommentsEnabled());
        dataHolder.setCommentNotify(this.getCommentNotify());
        dataHolder.setIconPath(getIcon());
        dataHolder.setAbout(getAbout());
        dataHolder.setAnalyticsSiteId(StringUtils.trimToNull(this.analyticsSiteId));
        dataHolder.setAnalyticsShareUrl(StringUtils.trimToNull(this.analyticsShareUrl));
        dataHolder.setNewsletterListUuid(StringUtils.trimToNull(this.newsletterListUuid));
        dataHolder.setCustomDomain(StringUtils.trimToNull(this.customDomain));
        dataHolder.setPlaceType(StringUtils.trimToNull(this.placeType));
        dataHolder.setPlaceLocality(StringUtils.trimToNull(this.placeLocality));
        dataHolder.setPlaceRegion(StringUtils.trimToNull(this.placeRegion));
        String country = StringUtils.trimToNull(this.placeCountry);
        dataHolder.setPlaceCountry(country == null ? null : country.toUpperCase(java.util.Locale.ROOT));
        dataHolder.setPlaceLat(toDecimal(this.placeLat));
        dataHolder.setPlaceLng(toDecimal(this.placeLng));
        dataHolder.setBookingUrl(StringUtils.trimToNull(this.bookingUrl));
    }

    /**
     * As {@link #copyTo(Weblog)}, and also resolves {@code businessId} through
     * the manager: blank clears the business, an unknown id is a
     * {@link WebloggerException} so a stale form can never silently unlink it.
     */
    public void copyTo(Weblog dataHolder, BusinessManager businesses) throws WebloggerException {
        // Resolve first: a failed lookup must leave the weblog exactly as it was.
        String id = StringUtils.trimToNull(this.businessId);
        Business business = null;
        if (id != null) {
            business = businesses.getBusiness(id);
            if (business == null) {
                throw new WebloggerException("Unknown business " + id);
            }
        }
        copyTo(dataHolder);
        dataHolder.setBusiness(business);
    }

    /**
     * A coordinate as typed: a plain decimal with an optional sign, at most three
     * integer digits (or none, as in ".5") and eight decimals. No exponent,
     * ever -- "1E-100000000" is numerically zero and would pass a range
     * check, but rounding it costs seconds of CPU.
     */
    private static final Pattern PLAIN_DECIMAL = Pattern.compile("^[+-]?(\\d{1,3}(\\.\\d{1,8})?|\\.\\d{1,8})$");

    /**
     * The number typed, or null when blank or not a plain decimal (validation
     * reports the latter). Checked against the pattern before any BigDecimal
     * is made, so no arithmetic ever sees an exponent.
     */
    public static BigDecimal toDecimal(String text) {
        String trimmed = StringUtils.trimToNull(text);
        if (trimmed == null || !PLAIN_DECIMAL.matcher(trimmed).matches()) {
            return null;
        }
        return new BigDecimal(trimmed);
    }
    


}
