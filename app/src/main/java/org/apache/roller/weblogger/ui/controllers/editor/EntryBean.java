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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.commons.text.StringEscapeUtils;
import org.apache.roller.weblogger.WebloggerException;
import org.apache.roller.weblogger.pojos.JsonLdType;
import org.apache.roller.weblogger.pojos.WeblogCategory;
import org.apache.roller.weblogger.pojos.WeblogEntry;
import org.apache.roller.weblogger.pojos.WeblogEntry.PubStatus;
import org.apache.roller.weblogger.ui.controllers.EntryFieldRules;
import org.apache.roller.weblogger.util.Utilities;

import java.sql.Timestamp;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.TimeZone;


/**
 * Bean for managing entry data.
 */
public class EntryBean {
    
    private static final Logger log = LoggerFactory.getLogger(EntryBean.class);
    
    private String id = null;
    private String title = null;
    private String locale = null;
    private String categoryId = null;
    private String tagsString = null;
    private String summary = null;
    private String text = null;
    private String status = null;

    private String pubTimeLocal = null;
    private boolean pinnedToMain = false;
    private String searchDescription = null;

    // Wave 1 media & SEO foundation -- no editor UI yet, plumbing only
    private String featuredImageId = null;
    private String metaTitle = null;
    private String ogImageId = null;
    private String canonicalUrl = null;
    private boolean noindex = false;
    private boolean commentsClosed = false;

    // Wave 3 travel structured data, edited in the SEO card.
    // jsonLdType holds a JsonLdType enum name, like status holds a PubStatus
    // name; the form binds event times as text (eventStartLocal/eventEndLocal) which
    // the controller reads in the weblog's zone into eventStart/eventEnd.
    private String jsonLdType = null;
    private Double geoLatitude = null;
    private Double geoLongitude = null;
    private Timestamp eventStart = null;
    private Timestamp eventEnd = null;
    private String eventStartLocal = null;
    private String eventEndLocal = null;
    private String eventLocation = null;
    
    
    public String getId() {
        return this.id;
    }
    
    public void setId( String id ) {
        this.id = id;
    }
    
    public String getTitle() {
        return this.title;
    }
    
    public void setTitle( String title ) {
        this.title = title;
    }
    
    public String getSummary() {
        return this.summary;
    }
    
    public void setSummary( String summary ) {
        this.summary = summary;
    }
    
    public String getText() {
        return this.text;
    }
    
    public void setText( String text ) {
        this.text = text;
    }
    
    public String getStatus() {
        return this.status;
    }
    
    public void setStatus( String status ) {
        this.status = status;
    }
    
    public String getLocale() {
        return this.locale;
    }
    
    public void setLocale( String locale ) {
        this.locale = locale;
    }
    
    public String getTagsAsString() {
        return this.tagsString;
    }
    
    public void setTagsAsString( String tagsAsString ) {
        this.tagsString = tagsAsString;
    }
    
    public String getCategoryId() {
        return categoryId;
    }
    
    public void setCategoryId(String categoryId) {
        this.categoryId = categoryId;
    }
    

    public String getPubTimeLocal() {
        return pubTimeLocal;
    }

    public void setPubTimeLocal(String pubTimeLocal) {
        this.pubTimeLocal = pubTimeLocal;
    }

    public boolean getPinnedToMain() {
        return this.pinnedToMain;
    }

    public void setPinnedToMain( boolean pinnedToMain ) {
        this.pinnedToMain = pinnedToMain;
    // CPD-OFF -- A pojo and its form bean necessarily name the same fields, and
    // the getters/copy methods that move values between them are that list
    // written out twice. Removing the duplication means introducing a mapper,
    // which is a design change rather than a cleanup: the copy methods are also
    // where per-field rules live (EntryBean escapes the title on the way in and
    // unescapes it on the way out -- see CLAUDE.md, Templates), and a generic
    // mapper is exactly where such a rule gets lost.
    }

    public String getSearchDescription() {
        return searchDescription;
    }
    
    public void setSearchDescription(String searchDescription) {
        this.searchDescription = searchDescription;
    }

    public String getFeaturedImageId() {
        return featuredImageId;
    }

    public void setFeaturedImageId(String featuredImageId) {
        this.featuredImageId = featuredImageId;
    }

    public String getMetaTitle() {
        return metaTitle;
    }

    public void setMetaTitle(String metaTitle) {
        this.metaTitle = metaTitle;
    }

    public String getOgImageId() {
        return ogImageId;
    }

    public void setOgImageId(String ogImageId) {
        this.ogImageId = ogImageId;
    }

    public String getCanonicalUrl() {
        return canonicalUrl;
    }

    public void setCanonicalUrl(String canonicalUrl) {
        this.canonicalUrl = canonicalUrl;
    }

    public boolean getCommentsClosed() {
        return commentsClosed;
    }

    public void setCommentsClosed(boolean commentsClosed) {
        this.commentsClosed = commentsClosed;
    }

    public boolean getNoindex() {
        return noindex;
    }

    public void setNoindex(boolean noindex) {
        this.noindex = noindex;
    }

    public String getJsonLdType() {
        return jsonLdType;
    }

    public void setJsonLdType(String jsonLdType) {
        this.jsonLdType = jsonLdType;
    }

    public Double getGeoLatitude() {
        return geoLatitude;
    }

    public void setGeoLatitude(Double geoLatitude) {
        this.geoLatitude = geoLatitude;
    }

    public Double getGeoLongitude() {
        return geoLongitude;
    }

    public void setGeoLongitude(Double geoLongitude) {
        this.geoLongitude = geoLongitude;
    }

    public Timestamp getEventStart() {
        return eventStart;
    }

    public void setEventStart(Timestamp eventStart) {
        this.eventStart = eventStart;
    }

    public Timestamp getEventEnd() {
        return eventEnd;
    }

    public void setEventEnd(Timestamp eventEnd) {
        this.eventEnd = eventEnd;
    }

    public String getEventLocation() {
        return eventLocation;
    }

    public void setEventLocation(String eventLocation) {
        this.eventLocation = eventLocation;
    }

    /**
     * The text of the event-start field: the zone-formatted time on a loaded
     * entry, or exactly what the author submitted. The controller parses it
     * in the weblog's zone (see {@code EntryEditController}); a value that
     * will not parse stays here so the form redisplays it with its error.
     */
    public String getEventStartLocal() {
    // CPD-ON
        return eventStartLocal;
    }

    public void setEventStartLocal(String value) {
        this.eventStartLocal = value;
    }

    /** {@link #getEventEndLocal} counterpart of {@link #getEventStartLocal}. */
    public String getEventEndLocal() {
        return eventEndLocal;
    }

    public void setEventEndLocal(String value) {
        this.eventEndLocal = value;
    }

    /**
     * The datetime-local wire format, to the minute. Written out rather than
     * reusing {@code ISO_LOCAL_DATE_TIME}, which always formats seconds and
     * would hand the browser a value it re-submits differently; ROOT so a
     * non-Gregorian default locale cannot shift the year.
     */
    private static final DateTimeFormatter DATETIME_LOCAL =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm", Locale.ROOT);

    /**
     * The final pubtime of the entry: {@link #getPubTimeLocal()} -- what an
     * {@code <input type="datetime-local">} submits -- parsed as wall-clock
     * time in {@code timezone}. Callers pass
     * {@code getActionWeblog(request).getTimeZoneInstance()}, exactly as the
     * old dateString/hours/minutes/seconds combination this replaces did;
     * pubtime has always meant the WEBLOG's timezone, never the server's or
     * the request locale's.
     *
     * <p>A blank {@code pubTimeLocal} means "no time chosen" and returns
     * null, which {@code EntryEditController} reads as "publish now". A
     * non-blank value that will not parse throws {@link
     * DateTimeParseException} rather than being silently discarded the way
     * the old dateString parser did -- a mistyped pubtime must surface as a
     * validation error, not quietly publish "now".
     *
     * <p>Delegates to {@link EntryFieldRules#parsePubTime}, the single
     * source of truth for this parsing (shared with the automation API).
     */
    public Timestamp getPubTime(TimeZone timezone) {
        return EntryFieldRules.parsePubTime(pubTimeLocal, timezone);
    }

    public boolean isDraft() {
        return PubStatus.DRAFT.name().equals(status);
    }
    
    public boolean isPending() {
        return PubStatus.PENDING.name().equals(status);
    }
    
    public boolean isPublished() {
        return PubStatus.PUBLISHED.name().equals(status);
    }
    
    public boolean isScheduled() {
        return PubStatus.SCHEDULED.name().equals(status);
    }
    
    /**
     * Copies the form onto the entry.
     *
     * @param category the category the controller resolved from
     *                 {@link #getCategoryId()}, or {@code null} if the id
     *                 resolved to nothing. The bean is pure -- the data binder
     *                 instantiates it, so it cannot be handed a manager to look
     *                 the id up itself -- but the decision of what a missing or
     *                 foreign category means still lives here, next to the
     *                 rest of what a submitted form is allowed to say.
     */
    public void copyTo(WeblogEntry entry, WeblogCategory category) throws WebloggerException {

        entry.setTitle(EntryFieldRules.escapeTitle(getTitle()));
        entry.setStatus(PubStatus.valueOf(getStatus()));
        entry.setLocale(getLocale());
        entry.setSummary(getSummary());
        entry.setText(getText());
        entry.setTagsAsString(getTagsAsString() != null
                ? Utilities.replaceNonAlphanumeric(getTagsAsString(), ' ') : "");
        entry.setSearchDescription(getSearchDescription());
        entry.setFeaturedImageId(getFeaturedImageId());
        entry.setMetaTitle(getMetaTitle());
        entry.setOgImageId(getOgImageId());
        entry.setCanonicalUrl(getCanonicalUrl());
        entry.setNoindex(getNoindex());
        entry.setCommentsClosed(getCommentsClosed());
        // fromString is lenient by design: null/blank (no dropdown yet) and
        // unknown values both normalize to the BLOG_POSTING default.
        entry.setJsonLdType(JsonLdType.fromString(getJsonLdType()));
        entry.setGeoLatitude(getGeoLatitude());
        entry.setGeoLongitude(getGeoLongitude());
        entry.setEventStart(getEventStart());
        entry.setEventEnd(getEventEnd());
        entry.setEventLocation(getEventLocation());

        // figure out the category selected
        if (getCategoryId() != null) {
            if (category == null) {
                throw new WebloggerException("Category could not be found - " + getCategoryId());
            } else if (!entry.getWebsite().equals(category.getWeblog())) {
                throw new WebloggerException("Illegal category, not owned by action weblog");
            } else {
                entry.setCategory(category);
            }
        } else {
            throw new WebloggerException("No category specified");
        }

        // NOTE: pubtime and pinned to main attributes are set in action
    }
    
    
    /**
     * Copy values from WeblogEntryData to this Form.
     */
    public void copyFrom(WeblogEntry entry, Locale locale) {
        
        setId(entry.getId());
        setTitle(StringEscapeUtils.unescapeHtml4(entry.getTitle()));
        setLocale(entry.getLocale());
        setStatus(entry.getStatus().name());
        setSummary(entry.getSummary());
        setText(entry.getText());
        setCategoryId(entry.getCategory().getId());
        setTagsAsString(entry.getTagsAsString());
        setSearchDescription(entry.getSearchDescription());
        setFeaturedImageId(entry.getFeaturedImageId());
        setMetaTitle(entry.getMetaTitle());
        setOgImageId(entry.getOgImageId());
        setCanonicalUrl(entry.getCanonicalUrl());
        setNoindex(entry.getNoindex() != null && entry.getNoindex());
        setCommentsClosed(Boolean.TRUE.equals(entry.getCommentsClosed()));
        // Null (entries persisted before this field existed) stays null; the
        // editor treats it as the BLOG_POSTING default.
        setJsonLdType(entry.getJsonLdType() != null ? entry.getJsonLdType().name() : null);
        setGeoLatitude(entry.getGeoLatitude());
        setGeoLongitude(entry.getGeoLongitude());
        setEventStart(entry.getEventStart());
        setEventEnd(entry.getEventEnd());
        if (entry.getEventStart() != null || entry.getEventEnd() != null) {
            TimeZone zone = entry.getWebsite().getTimeZoneInstance();
            this.eventStartLocal = EntryFieldRules.formatWallClock(entry.getEventStart(), zone);
            this.eventEndLocal = EntryFieldRules.formatWallClock(entry.getEventEnd(), zone);
        } else {
            this.eventStartLocal = "";
            this.eventEndLocal = "";
        }
        setEventLocation(entry.getEventLocation());

        // init pubtime value -- emitted in the WEBLOG's timezone, matching
        // what getPubTime(TimeZone) parses it back with, so opening and
        // saving an entry unchanged does not move its publication time.
        if (entry.getPubTime() != null) {
            log.debug("entry pubtime is {}", entry.getPubTime());
            ZonedDateTime zoned = entry.getPubTime().toInstant()
                    .atZone(entry.getWebsite().getTimeZoneInstance().toZoneId());
            setPubTimeLocal(zoned.toLocalDateTime().format(DATETIME_LOCAL));
        }

        setPinnedToMain(entry.getPinnedToMain());
    }
    
    
    @Override
    public String toString() {
        StringBuilder buf = new StringBuilder();
        
        //title,locale,catId,tags,text,summary,pubTimeLocal,status
        buf.append("title = ").append(getTitle()).append("\n");
        buf.append("locale = ").append(getLocale()).append("\n");
        buf.append("status = ").append(getStatus()).append("\n");
        buf.append("catId = ").append(getCategoryId()).append("\n");
        buf.append("tags = ").append(getTagsAsString()).append("\n");
        buf.append("pubTimeLocal = ").append(getPubTimeLocal()).append("\n");
        buf.append("text = ").append(getText()).append("\n");
        buf.append("summary = ").append(getSummary()).append("\n");
        buf.append("search description = ").append(getSearchDescription()).append("\n");

        return buf.toString();
    }
}
