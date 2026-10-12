/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  The ASF licenses this file to You
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

package org.apache.roller.weblogger.pojos;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.Serializable;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.text.SimpleDateFormat;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.StringTokenizer;
import java.util.TreeSet;

import org.apache.commons.text.StringEscapeUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.builder.EqualsBuilder;
import org.apache.commons.lang3.builder.HashCodeBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.roller.util.DateUtil;
import org.apache.roller.util.RollerConstants;
import org.apache.roller.util.UUIDGenerator;
import org.apache.roller.weblogger.WebloggerException;
import org.apache.roller.weblogger.business.shortcodes.ShortcodeContext;
import org.apache.roller.weblogger.util.Utilities;

/**
 * Represents a Weblog Entry.
 */
public class WeblogEntry implements Serializable, ShortcodeContext {
    private static final Logger log = LoggerFactory.getLogger(WeblogEntry.class);
    
    public static final long serialVersionUID = 2341505386843044125L;

    // Stored by name (see WeblogEntry.orm.xml's <enumerated>STRING</enumerated>
    // on status), so appending TRASHED here is safe regardless of position --
    // it is added last anyway, as the habit to keep if that mapping ever
    // changes to ordinal.
    public enum PubStatus {DRAFT, PUBLISHED, PENDING, SCHEDULED, TRASHED}

    // Simple properies
    private String    id            = UUIDGenerator.generateUUID();
    private String    title         = null;
    private String    link          = null;
    private String    summary       = null;
    private String    text          = null;
    private String    anchor        = null;
    private Timestamp pubTime       = null;
    private Timestamp updateTime    = null;
    private Boolean   rightToLeft   = Boolean.FALSE;
    private Boolean   pinnedToMain  = Boolean.FALSE;
    private PubStatus status        = PubStatus.DRAFT;
    private String    locale        = null;
    private String    creatorUserName = null;
    private String    searchDescription = null;

    // Wave 1 media & SEO foundation
    private String    featuredImageId = null;
    private String    metaTitle       = null;
    private String    ogImageId       = null;
    private String    canonicalUrl    = null;
    private Boolean   noindex         = Boolean.FALSE;
    private Boolean   commentsClosed  = Boolean.FALSE;

    // Wave 3 travel structured data
    private JsonLdType jsonLdType     = null;
    private Double    geoLatitude     = null;
    private Double    geoLongitude    = null;
    private Timestamp eventStart      = null;
    private Timestamp eventEnd        = null;
    private String    eventLocation   = null;

    // Wave B audience: newsletter
    private Timestamp newsletterSentAt = null;

    // W5 trash
    private Timestamp trashedAt = null;

    // set to true when switching between pending/draft/scheduled and published
    // either the aggregate table needs the entry's tags added (for published)
    // or subtracted (anything else)
    private Boolean   refreshAggregates = Boolean.FALSE;

    // Associated objects
    private Weblog        website  = null;
    private WeblogCategory category = null;
    
    // Collection of name/value entry attributes
    private transient Set<WeblogEntryAttribute> attSet = new TreeSet<>();

    private transient Set<WeblogEntryTag> tagSet = new HashSet<>();
    private transient Set<WeblogEntryTag> removedTags = new HashSet<>();
    private transient Set<WeblogEntryTag> addedTags = new HashSet<>();
    
    //----------------------------------------------------------- Construction
    
    public WeblogEntry() {
    }
    
    public WeblogEntry(
            String id,
            WeblogCategory category,
            Weblog website,
            User creator,
            String title,
            String link,
            String text,
            String anchor,
            Timestamp pubTime,
            Timestamp updateTime,
            PubStatus status) {
        //this.id = id;
        this.category = category;
        this.website = website;
        this.creatorUserName = creator.getUserName();
        this.title = title;
        this.link = link;
        this.text = text;
        this.anchor = anchor;
        this.pubTime = pubTime;
        this.updateTime = updateTime;
        this.status = status;
    }
    
    public WeblogEntry(WeblogEntry otherData) {
        this.setData(otherData);
    }
    
    //---------------------------------------------------------- Initializaion
    
    /**
     * Set bean properties based on other bean.
     */
    public void setData(WeblogEntry other) {
        
        this.setId(other.getId());
        this.setCategory(other.getCategory());
        this.setWebsite(other.getWebsite());
        this.setCreatorUserName(other.getCreatorUserName());
        this.setTitle(other.getTitle());
        this.setLink(other.getLink());
        this.setText(other.getText());
        this.setSummary(other.getSummary());
        this.setSearchDescription(other.getSearchDescription());
        this.setFeaturedImageId(other.getFeaturedImageId());
        this.setMetaTitle(other.getMetaTitle());
        this.setOgImageId(other.getOgImageId());
        this.setCanonicalUrl(other.getCanonicalUrl());
        this.setNoindex(other.getNoindex());
        this.setCommentsClosed(other.getCommentsClosed());
        this.setAnchor(other.getAnchor());
        this.setPubTime(other.getPubTime());
        this.setUpdateTime(other.getUpdateTime());
        this.setStatus(other.getStatus());
        this.setRightToLeft(other.getRightToLeft());
        this.setPinnedToMain(other.getPinnedToMain());
        this.setLocale(other.getLocale());
    }

    // ------------------------------------------------- revision bookkeeping

    /**
     * The content this entry was loaded from the database with, or null when it
     * has never been loaded (a new entry). Transient and deliberately not
     * mapped: it exists only so a save can tell what it is displacing.
     */
    private transient WeblogEntryRevision loadedContent;

    /** Status as loaded from the database; null for a new entry. Set by the
     *  same post-load callback that snapshots content for revisions. */
    private transient PubStatus loadedStatus = null;

    /**
     * JPA {@code post-load} callback: remember the content as read from the
     * database. See WeblogEntry.orm.xml, which is where this is wired -- there
     * is no annotation on this class because the mapping is metadata-complete.
     */
    public void snapshotLoadedContent() {
        loadedContent = WeblogEntryRevision.of(this, null);
        loadedStatus = getStatus();
    }

    public PubStatus getLoadedStatus() {
        return loadedStatus;
    }

    /**
     * The content this entry held before the caller's pending changes, as an
     * unsaved revision -- or null when there is nothing to record: a new entry
     * that was never loaded, or a save that leaves title, text and summary
     * exactly as they were.
     *
     * <p>That second case is why an author can re-save an entry to fix its
     * publication date without depositing a revision identical to the one
     * before it.
     *
     * @param creator username to attribute the displaced content to
     */
    public WeblogEntryRevision contentBeingReplaced(String creator) {
        if (loadedContent == null || !loadedContent.differsFrom(this)) {
            return null;
        }
        loadedContent.setCreator(creator);
        return loadedContent;
    }

    /**
     * An unsaved draft copy of this entry, carrying its content, categorisation,
     * SEO block and tags but none of its identity or publication history.
     *
     * <p>Specifically NOT copied:
     * <ul>
     *   <li>the id -- a fresh one, or the save would overwrite the original;</li>
     *   <li>the anchor -- left null so {@code saveWeblogEntry} derives a unique
     *       one from the new title, which is the only place that checks the
     *       weblog for collisions;</li>
     *   <li>the publication time and status -- a copy starts as a DRAFT that
     *       has never been published, so it cannot appear on the blog before
     *       its author has looked at it.</li>
     * </ul>
     *
     * <p>Tags are re-added by name rather than shared, so the copy owns its own
     * tag rows and the aggregate counts stay right when either entry changes.
     *
     * @param newTitle title for the copy, which the caller localises; the
     *                 anchor is derived from it at save time
     */
    public WeblogEntry copyAsDraft(String newTitle) throws WebloggerException {
        WeblogEntry copy = new WeblogEntry();
        copy.setData(this);
        copy.setId(UUIDGenerator.generateUUID());
        copy.setTitle(newTitle);
        copy.setAnchor(null);
        copy.setStatus(PubStatus.DRAFT);
        copy.setPubTime(null);
        copy.setUpdateTime(new Timestamp(System.currentTimeMillis()));
        copy.setTagsAsString(getTagsAsString());
        return copy;
    }

    //------------------------------------------------------- Good citizenship

    @Override
    public String toString() {
        StringBuilder buf = new StringBuilder();
        buf.append("{");
        buf.append(getId());
        buf.append(", ").append(this.getAnchor());
        buf.append(", ").append(this.getTitle());
        buf.append(", ").append(this.getPubTime());
        buf.append("}");
        return buf.toString();
    }

    @Override
    public boolean equals(Object other) {
        if (other == this) {
            return true;
        }
        if (!(other instanceof WeblogEntry)) {
            return false;
        }
        WeblogEntry o = (WeblogEntry)other;
        return new EqualsBuilder()
            .append(getAnchor(), o.getAnchor()) 
            .append(getWebsite(), o.getWebsite()) 
            .isEquals();
    }
    
    @Override
    public int hashCode() { 
        return new HashCodeBuilder()
            .append(getAnchor())
            .append(getWebsite())
            .toHashCode();
    }
    
   //------------------------------------------------------ Simple properties
    
    public String getId() {
        return this.id;
    }
    
    public void setId(String id) {
        // Form bean workaround: empty string is never a valid id
        if (id != null && id.isBlank()) {
            return;
        }
        this.id = id;
    }
    
    public WeblogCategory getCategory() {
        return this.category;
    }
    
    public void setCategory(WeblogCategory category) {
        this.category = category;
    }
       
    /**
     * Return collection of WeblogCategory objects of this entry.
     * Added for symmetry with PlanetEntryData object.
     */
    public List<WeblogCategory> getCategories() {
        return List.of(getCategory());
    }
    
    public Weblog getWebsite() {
        return this.website;
    }

    public void setWebsite(Weblog website) {
        this.website = website;
    }

    // ---- ShortcodeContext. Delegates to the accessors this class already
    // had; WeblogEntry.orm.xml is metadata-complete, so an extra getter
    // cannot create a phantom persistent field.

    @Override
    public Weblog getWeblog() {
        return getWebsite();
    }

    @Override
    public String getSlug() {
        return getAnchor();
    }

    @Override
    public String getRawText() {
        return getText();
    }

    public String getCreatorUserName() {
        return creatorUserName;
    }

    public void setCreatorUserName(String creatorUserName) {
        this.creatorUserName = creatorUserName;
    }   
    
    public String getTitle() {
        return this.title;
    }
    
    public void setTitle(String title) {
        this.title = title;
    }
    
    /**
     * Get summary for weblog entry (maps to RSS description and Atom summary).
     */
    public String getSummary() {
        return summary;
    }
    
    /**
     * Set summary for weblog entry (maps to RSS description and Atom summary).
     */
    public void setSummary(String summary) {
        this.summary = summary;
    // CPD-OFF -- A pojo and its form bean necessarily name the same fields, and
    // the getters/copy methods that move values between them are that list
    // written out twice. Removing the duplication means introducing a mapper,
    // which is a design change rather than a cleanup: the copy methods are also
    // where per-field rules live (EntryBean escapes the title on the way in and
    // unescapes it on the way out -- see CLAUDE.md, Templates), and a generic
    // mapper is exactly where such a rule gets lost.
    }
    
    /**
     * Get search description for weblog entry.
     */
    public String getSearchDescription() {
        return searchDescription;
    }
    
    /**
     * Set search description for weblog entry
     */
    public void setSearchDescription(String searchDescription) {
        this.searchDescription = searchDescription;
    }

    /**
     * Id of the {@code MediaFile} to use as this entry's featured image, e.g.
     * for theme hero slots and as the default {@code og:image}. Stored as a
     * bare id rather than a foreign key -- media files are deleted
     * independently of entries, and a stale reference should degrade to "no
     * image" rather than block either delete.
     */
    public String getFeaturedImageId() {
        return featuredImageId;
    }

    public void setFeaturedImageId(String featuredImageId) {
        this.featuredImageId = featuredImageId;
    }

    /** SEO {@code <title>} override. Null falls back to the entry's normal title. */
    public String getMetaTitle() {
        return metaTitle;
    }

    public void setMetaTitle(String metaTitle) {
        this.metaTitle = metaTitle;
    }

    /**
     * Id of the {@code MediaFile} to use for {@code og:image} specifically,
     * when it should differ from the featured image. Null falls back to the
     * featured image. See {@link #getFeaturedImageId()} for why this is a
     * bare id rather than a foreign key.
     */
    public String getOgImageId() {
        return ogImageId;
    }

    public void setOgImageId(String ogImageId) {
        this.ogImageId = ogImageId;
    }

    /** Canonical URL override. Null falls back to the entry's normal permalink. */
    public String getCanonicalUrl() {
        return canonicalUrl;
    }

    public void setCanonicalUrl(String canonicalUrl) {
        this.canonicalUrl = canonicalUrl;
    }

    /** True to emit {@code <meta name="robots" content="noindex">} for this entry. */
    public Boolean getNoindex() {
        return noindex;
    }

    public void setNoindex(Boolean noindex) {
        this.noindex = noindex;
    }

    /** True when the owner has closed this entry to new reader comments. */
    public Boolean getCommentsClosed() {
        return commentsClosed;
    }

    public void setCommentsClosed(Boolean commentsClosed) {
        this.commentsClosed = commentsClosed;
    }

    /**
     * The schema.org type this entry's head emits as JSON-LD. Null means the
     * {@link JsonLdType#BLOG_POSTING} default -- just the BlogPosting block
     * every permalink already gets.
     */
    public JsonLdType getJsonLdType() {
        return jsonLdType;
    }

    public void setJsonLdType(JsonLdType jsonLdType) {
        this.jsonLdType = jsonLdType;
    }

    /**
     * Latitude for a {@link JsonLdType#TOURIST_ATTRACTION}'s GeoCoordinates;
     * also the default centre for a bare {@code [map]}. Null when unset.
     */
    public Double getGeoLatitude() {
        return geoLatitude;
    }

    public void setGeoLatitude(Double geoLatitude) {
        this.geoLatitude = geoLatitude;
    }

    /** Longitude counterpart to {@link #getGeoLatitude()}. Null when unset. */
    public Double getGeoLongitude() {
        return geoLongitude;
    }

    public void setGeoLongitude(Double geoLongitude) {
        this.geoLongitude = geoLongitude;
    }

    /** Start instant for a {@link JsonLdType#EVENT}'s startDate. Null when unset. */
    public Timestamp getEventStart() {
        return eventStart;
    }

    public void setEventStart(Timestamp eventStart) {
        this.eventStart = eventStart;
    }

    /** End instant for a {@link JsonLdType#EVENT}'s endDate. Null when unset. */
    public Timestamp getEventEnd() {
        return eventEnd;
    }

    public void setEventEnd(Timestamp eventEnd) {
        this.eventEnd = eventEnd;
    }

    /**
     * Venue name for a {@link JsonLdType#EVENT}, emitted as a name-only
     * {@code Place} location. Null when unset.
     */
    public String getEventLocation() {
        return eventLocation;
    }

    public void setEventLocation(String eventLocation) {
        this.eventLocation = eventLocation;
    }

    /**
     * When "Send as newsletter" succeeded for this entry. Null means never
     * sent; stamped so an entry cannot be mailed twice.
     */
    public Timestamp getNewsletterSentAt() {
    // CPD-ON
        return newsletterSentAt;
    }

    public void setNewsletterSentAt(Timestamp newsletterSentAt) {
        this.newsletterSentAt = newsletterSentAt;
    }

    /**
     * Get content text for weblog entry (maps to RSS content:encoded and Atom content).
     */
    public String getText() {
        return this.text;
    }
    
    /**
     * Set content text for weblog entry (maps to RSS content:encoded and Atom content).
     */
    public void setText(String text) {
        this.text = text;
    }
    
    public String getAnchor() {
        return this.anchor;
    }
    
    public void setAnchor(String anchor) {
        this.anchor = anchor;
    }
    
    //-------------------------------------------------------------------------

    public Set<WeblogEntryAttribute> getEntryAttributes() {
        return attSet;
    }

    public void setEntryAttributes(Set<WeblogEntryAttribute> atts) {
        this.attSet = atts;
    }
    
    public String findEntryAttribute(String name) {
        if (getEntryAttributes() != null) {
            for (WeblogEntryAttribute att : getEntryAttributes()) {
                if (name.equals(att.getName())) {
                    return att.getValue();
                }
            }
        }
        return null;
    }
        
    public void putEntryAttribute(String name, String value) throws Exception {
        WeblogEntryAttribute att = null;
        for (WeblogEntryAttribute o : getEntryAttributes()) {
            if (name.equals(o.getName())) {
                att = o; 
                break;
            }
        }
        if (att == null) {
            att = new WeblogEntryAttribute();
            att.setEntry(this);
            att.setName(name);
            att.setValue(value);
            getEntryAttributes().add(att);
        } else {
            att.setValue(value);
        }
    }
    
    //-------------------------------------------------------------------------
    
    /**
     * <p>Publish time is the time that an entry is to be (or was) made available
     * for viewing by newsfeed readers and visitors to the Roller site.</p>
     *
     * <p>Roller stores time using the timeZone of the server itself. When
     * times are displayed  in a user's weblog they must be translated
     * to the user's timeZone.</p>
     *
     * <p>NOTE: Times are stored using the SQL TIMESTAMP datatype, which on
     * MySQL has only a one-second resolution.</p>
     */
    public Timestamp getPubTime() {
        return this.pubTime;
    }
    
    public void setPubTime(Timestamp pubTime) {
        this.pubTime = pubTime;
    }
    
    /**
     * <p>Update time is the last time that an weblog entry was saved in the
     * Roller weblog editor or via web services API (XML-RPC or Atom).</p>
     *
     * <p>Roller stores time using the timeZone of the server itself. When
     * times are displayed  in a user's weblog they must be translated
     * to the user's timeZone.</p>
     *
     * <p>NOTE: Times are stored using the SQL TIMESTAMP datatype, which on
     * MySQL has only a one-second resolution.</p>
     */
    public Timestamp getUpdateTime() {
        return this.updateTime;
    }
    
    public void setUpdateTime(Timestamp updateTime) {
        this.updateTime = updateTime;
    }
    
    public PubStatus getStatus() {
        return this.status;
    }

    public void setStatus(PubStatus status) {
        this.status = status;
    }

    /**
     * When this entry was moved to the trash. Null means it is not trashed;
     * the trash state itself lives in {@link #getStatus()} being
     * {@link PubStatus#TRASHED} -- this stamp exists only so the purge sweep
     * and the trash list can order and expire entries.
     */
    public Timestamp getTrashedAt() {
        return trashedAt;
    }

    public void setTrashedAt(Timestamp trashedAt) {
        this.trashedAt = trashedAt;
    }
    
    /**
     * Some weblog entries are about one specific link.
     * @return Returns the link.
     */
    public String getLink() {
        return link;
    }
    
    /**
     * @param link The link to set.
     */
    public void setLink(String link) {
        this.link = link;
    }
    
    /**
     * True if this entry should be rendered right to left.
     */
    public Boolean getRightToLeft() {
        return rightToLeft;
    }
    /**
     * True if this entry should be rendered right to left.
     */
    public void setRightToLeft(Boolean rightToLeft) {
        this.rightToLeft = rightToLeft;
    }
    
    /**
     * True if story should be pinned to the top of the Roller site main blog.
     * @return Returns the pinned.
     */
    public Boolean getPinnedToMain() {
        return pinnedToMain;
    }
    /**
     * True if story should be pinned to the top of the Roller site main blog.
     * @param pinnedToMain The pinned to set.
     */
    public void setPinnedToMain(Boolean pinnedToMain) {
        this.pinnedToMain = pinnedToMain;
    }

    /**
     * The locale string that defines the i18n approach for this entry.
     */
    public String getLocale() {
        return locale;
    }

    public void setLocale(String locale) {
        this.locale = locale;
    }
    
    public Set<WeblogEntryTag> getTags() {
         return tagSet;
    }

    @SuppressWarnings("unused")
    public void setTags(Set<WeblogEntryTag> tagSet) throws WebloggerException {
         this.tagSet = tagSet;
         this.removedTags = new HashSet<>();
         this.addedTags = new HashSet<>();
    }
     
    /**
     * Roller lowercases all tags based on locale because there's not a 1:1 mapping
     * between uppercase/lowercase characters across all languages.  
     * @param name
     * @throws WebloggerException
     */
    public void addTag(String name) throws WebloggerException {
        Locale localeObject = getWebsite() != null ? getWebsite().getLocaleInstance() : Locale.getDefault();
        name = Utilities.normalizeTag(name, localeObject);
        if (name.length() == 0) {
            return;
        }
        
        for (WeblogEntryTag tag : getTags()) {
            if (tag.getName().equals(name)) {
                return;
            }
        }

        WeblogEntryTag tag = new WeblogEntryTag();
        tag.setName(name);
        tag.setCreatorUserName(getCreatorUserName());
        tag.setWeblog(getWebsite());
        tag.setWeblogEntry(this);
        // Not getUpdateTime() alone: roller_weblogentrytag.time is NOT NULL, and
        // a brand-new entry has no update time until saveWeblogEntry stamps one,
        // which happens AFTER the caller has applied its tags. Reading it
        // unguarded made every tagged POST to the entries API fail with a
        // constraint violation. The JSP editor only escaped that by ordering --
        // EntryEditController sets an update time on the line before
        // EntryBean.copyTo applies the tags -- which is an invariant nothing
        // stated and nothing enforced. Falling back to now keeps the tag and its
        // entry agreeing on a timestamp whenever there is one to agree with, and
        // removes the ordering trap when there is not.
        Timestamp entryUpdated = getUpdateTime();
        tag.setTime(entryUpdated != null ? entryUpdated : new Timestamp(System.currentTimeMillis()));
        tagSet.add(tag);
        
        addedTags.add(tag);
    }

    public Set<WeblogEntryTag> getAddedTags() {
        return addedTags;
    }
    
    public Set<WeblogEntryTag> getRemovedTags() {
        return removedTags;
    }

    public String getTagsAsString() {
        StringBuilder sb = new StringBuilder();
        // Sort by name
        Set<WeblogEntryTag> tmp = new TreeSet<>(new WeblogEntryTagComparator());
        tmp.addAll(getTags());
        for (WeblogEntryTag entryTag : tmp) {
            sb.append(entryTag.getName()).append(" ");
        }
        if (sb.length() > 0) {
            sb.deleteCharAt(sb.length() - 1);
        }

        return sb.toString();
    }

    public void setTagsAsString(String tags) throws WebloggerException {
        if (StringUtils.isEmpty(tags)) {
            removedTags.addAll(tagSet);
            tagSet.clear();
            return;
        }

        List<String> updatedTags = Utilities.splitStringAsTags(tags);
        Set<String> newTags = new HashSet<>(updatedTags.size());
        Locale localeObject = getWebsite() != null ? getWebsite().getLocaleInstance() : Locale.getDefault();

        for (String name : updatedTags) {
            newTags.add(Utilities.normalizeTag(name, localeObject));
        }

        // remove old ones no longer passed.
        for (Iterator<WeblogEntryTag> it = tagSet.iterator(); it.hasNext();) {
            WeblogEntryTag tag = it.next();
            if (!newTags.contains(tag.getName())) {
                // tag no longer listed in UI, needs removal from DB
                removedTags.add(tag);
                it.remove();
            } else {
                // already in persisted set, therefore isn't new
                newTags.remove(tag.getName());
            }
        }

        for (String newTag : newTags) {
            addTag(newTag);
        }
    }

    //------------------------------------------------------------------------
    
    /**
     * Format the publish time of this weblog entry using the specified pattern.
     * See java.text.SimpleDateFormat for more information on this format.
     *
     * @see java.text.SimpleDateFormat
     * @return Publish time formatted according to pattern.
     */
    public String formatPubTime(String pattern) {
        try {
            SimpleDateFormat format = new SimpleDateFormat(pattern,
                    this.getWebsite().getLocaleInstance());
            
            return format.format(getPubTime());
        } catch (RuntimeException e) {
            log.error("Unexpected exception", e);
        }
        
        return "ERROR: formatting date";
    }
    
    //------------------------------------------------------------------------
    
    /**
     * Format the update time of this weblog entry using the specified pattern.
     * See java.text.SimpleDateFormat for more information on this format.
     *
     * @see java.text.SimpleDateFormat
     * @return Update time formatted according to pattern.
     */
    public String formatUpdateTime(String pattern) {
        try {
            SimpleDateFormat format = new SimpleDateFormat(pattern);
            
            return format.format(getUpdateTime());
        } catch (RuntimeException e) {
            log.error("Unexpected exception", e);
        }
        
        return "ERROR: formatting date";
    }
    
    //------------------------------------------------------------------------

    // The absolute permalink used to be a getter here, reaching the URLStrategy
    // through the static service locator. It is built by whoever holds the
    // strategy now: WeblogEntryWrapper.getPermalink() for templates and
    // AdminUrls.entry(entry) for the admin JSPs.

    /**
     * Returns entry permalink, relative to Roller context. Pure string work --
     * no collaborator -- which is why it stays on the entity.
     * @deprecated Use the wrapper's getPermalink() instead.
     */
    // NM_CONFUSING pairs this with MediaFileWrapper.getPermalink() purely on
    // case -- the two classes share no hierarchy. getPermalink() is the
    // canonical template spelling ($image.permalink in WEB-INF/velocity/
    // weblog.vm and the themes) and renaming it would silently break every
    // one of those, per CLAUDE.md's Velocity-leniency warning; this method is
    // deprecated legacy API kept for the wrapper's $entry.permaLink. The
    // suppression used to sit on MediaFile.getPermalink(), the other half of
    // the pair, until the entity stopped building urls; SpotBugs anchors the
    // finding on whichever method it names first, which is now this one.
    @SuppressFBWarnings(
            value = "NM_CONFUSING",
            justification = "MediaFileWrapper.getPermalink() is the canonical template spelling "
                    + "($image.permalink) and cannot be renamed without silently breaking every "
                    + "theme reference; this deprecated method collides with it on case alone.")
    @Deprecated
    public String getPermaLink() {
        String lAnchor = URLEncoder.encode(getAnchor(), StandardCharsets.UTF_8);
        return "/" + getWebsite().getHandle() + "/entry/" + lAnchor;
    }
    
    /**
     * Return the Title of this post, or the first 255 characters of the
     * entry's text.
     *
     * @return String
     */
    public String getDisplayTitle() {
        if ( getTitle()==null || getTitle().isBlank() ) {
            return StringUtils.left(Utilities.removeHTML(getText()), RollerConstants.TEXTWIDTH_255);
        }
        return Utilities.removeHTML(getTitle());
    }
    
    /**
     * Return RSS 09x style description (escaped HTML version of entry text)
     */
    public String getRss09xDescription() {
        return getRss09xDescription(-1);
    }
    
    /**
     * Return RSS 09x style description (escaped HTML version of entry text)
     */
    public String getRss09xDescription(int maxLength) {
        String ret = StringEscapeUtils.escapeHtml3(getText());
        if (maxLength != -1 && ret.length() > maxLength) {
            ret = ret.substring(0,maxLength-3)+"...";
        }
        return ret;
    }
    
    
    /**
     * Create the anchor base for this entry from its title or text, using the
     * given word separator. The separator is a runtime site setting; the
     * manager that calls this reads it per call so a change is seen without a
     * restart (see {@code JPAWeblogEntryManagerImpl.createAnchor}). Anchors
     * already stored on entries keep whichever separator was in force when
     * they were created, which is what keeps existing permalinks working.
     */
    public String createAnchorBase(char separator) {
        
        // Use title (minus non-alphanumeric characters)
        String base = null;
        if (!StringUtils.isEmpty(getTitle())) {
            base = Utilities.replaceNonAlphanumeric(getTitle(), ' ').trim();    
        }
        // If we still have no base, then try text (minus non-alphanumerics)
        if (StringUtils.isEmpty(base) && !StringUtils.isEmpty(getText())) {
            base = Utilities.replaceNonAlphanumeric(getText(), ' ').trim();  
        }
        
        if (!StringUtils.isEmpty(base)) {
            
            // Use only the first 4 words
            StringTokenizer toker = new StringTokenizer(base);
            String tmp = null;
            int count = 0;
            while (toker.hasMoreTokens() && count < 5) {
                String s = toker.nextToken();
                s = s.toLowerCase(Locale.ROOT);
                tmp = (tmp == null) ? s : tmp + separator + s;
                count++;
            }
            base = tmp;
        }
        // No title or text, so instead we will use the items date
        // in YYYYMMDD format as the base anchor
        else {
            base = DateUtil.format8chars(getPubTime());
        }
        
        return base;
    }
    
    /**
     * A no-op. TODO: fix formbean generation so this is not needed.
     */
    public void setPermalink(String string) {}

    /**
     * A no-op. TODO: fix formbean generation so this is not needed.
     */
    // NM_CONFUSING pairs this with the unrelated MediaFileBean.setPermalink(
    // String) purely on case. Both this setter and its case-differing
    // sibling setPermalink(String) directly above are pre-existing no-ops
    // kept only so legacy formbean generation has somewhere to land (see
    // their own javadoc TODOs); MediaFileBean.setPermalink is a live,
    // actively JSP-bound property (${mediaFile.permalink} in
    // MediaFileImageChooser.jsp) on an entirely unrelated class. Renaming
    // either is out of scope for a naming-lint fix: this one because its
    // exact name is what legacy binding may still target, the other because
    // it would break a live JSP form field.
    @SuppressFBWarnings(
            value = "NM_CONFUSING",
            justification = "Pre-existing no-op kept only for legacy formbean-generation "
                    + "compatibility (see its own javadoc TODO); the method it collides with on "
                    + "case, MediaFileBean.setPermalink(String), is an unrelated, live JSP-bound "
                    + "property (${mediaFile.permalink} in MediaFileImageChooser.jsp) that must "
                    + "keep its exact name too.")
    public void setPermaLink(String string) {}
    
    /**
     * A no-op.
     * TODO: fix formbean generation so this is not needed.
     * @param string
     */
    public void setDisplayTitle(String string) {
    }
    
    /**
     * A no-op.
     * TODO: fix formbean generation so this is not needed.
     * @param string
     */
    public void setRss09xDescription(String string) {
    }
    
    
    /** Convenience method for checking status */
    public boolean isDraft() {
        return getStatus().equals(PubStatus.DRAFT);
    }

    /** Convenience method for checking status */
    public boolean isPending() {
        return getStatus().equals(PubStatus.PENDING);
    }

    /** Convenience method for checking status */
    public boolean isPublished() {
        return getStatus().equals(PubStatus.PUBLISHED);
    }


    public Boolean getRefreshAggregates() {
        return refreshAggregates;
    }

    public void setRefreshAggregates(Boolean refreshAggregates) {
        this.refreshAggregates = refreshAggregates;
    }

}
