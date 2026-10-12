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

package org.apache.roller.weblogger.pojos;

import java.io.Serializable;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

import org.apache.commons.lang3.builder.EqualsBuilder;
import org.apache.commons.lang3.builder.HashCodeBuilder;
import org.apache.roller.util.UUIDGenerator;
import org.apache.roller.weblogger.util.I18nUtils;
import org.apache.roller.weblogger.util.Utilities;


/**
 * Website has many-to-many association with users. Website has one-to-many and
 * one-direction associations with weblog entries, weblog categories, folders and
 * other objects. Use UserManager to create, fetch, update and retrieve websites.
 *
 * @author David M Johnson
 */
public class Weblog implements Serializable {
    
    public static final long serialVersionUID = 206437645033737127L;
    

    
    // Simple properties
    private String  id               = UUIDGenerator.generateUUID();
    private String  handle           = null;
    private String  name             = null;
    private String  tagline          = null;
    private String  emailAddress     = null;
    private String  editorTheme      = null;
    private String  locale           = null;
    private String  timeZone         = null;
    private Boolean visible          = Boolean.TRUE;
    private Boolean active           = Boolean.TRUE;
    private Boolean commentsEnabled  = Boolean.FALSE;
    private Boolean commentNotify    = Boolean.TRUE;
    private Date    dateCreated      = new Date();
    private int     entryDisplayCount = 15;
    private Date    lastModified     = new Date();
    private String  iconPath         = null;
    private String  about            = null;
    private String  creator          = null;
    private String  analyticsSiteId  = null;
    private String  analyticsShareUrl = null;
    private String  newsletterListUuid = null;
    private String  customDomain     = null;
    private Business business        = null;
    private String  placeType        = null;
    private String  placeLocality    = null;
    private String  placeRegion      = null;
    private String  placeCountry     = null;
    private BigDecimal placeLat      = null;
    private BigDecimal placeLng      = null;
    private String  bookingUrl       = null;


    private transient List<WeblogCategory> weblogCategories = new ArrayList<>();

    private transient List<MediaFileDirectory> mediaFileDirectories = new ArrayList<>();

    public Weblog() {
    }
    
    public Weblog(
            String handle,
            String creator,
            String name,
            String desc,
            String email,
            String editorTheme,
            String locale,
            String timeZone) {
        
        this.handle = handle;
        this.creator = creator;
        this.name = name;
        this.tagline = desc;
        this.emailAddress = email;
        this.editorTheme = editorTheme;
        this.locale = locale;
        this.timeZone = timeZone;
    }
    
    //------------------------------------------------------- Good citizenship

    @Override
    public String toString() {
        return  "{" + getId() + ", " + getHandle()
        + ", " + getName() + ", " + getEmailAddress()
        + ", " + getLocale() + ", " + getTimeZone() + "}";
    }

    @Override
    public boolean equals(Object other) {
        if (other == this) {
            return true;
        }
        if (!(other instanceof Weblog)) {
            return false;
        }
        Weblog o = (Weblog)other;
        return new EqualsBuilder()
            .append(getHandle(), o.getHandle()) 
            .isEquals();
    }
    
    @Override
    public int hashCode() { 
        return new HashCodeBuilder()
            .append(getHandle())
            .toHashCode();
    } 
    
    /**
     * Id of the Website.
     */
    public String getId() {
        return this.id;
    }
    
    public void setId(String id) {
        this.id = id;
    }
    
    /**
     * Short URL safe string that uniquely identifies the website.
     */
    public String getHandle() {
        return this.handle;
    }
    
    public void setHandle(String handle) {
        this.handle = handle;
    }
    
    /**
     * Name of the Website.
     */
    public String getName() {
        return this.name;
    }
    
    public void setName(String name) {
        this.name = Utilities.removeHTML(name);
    }
    
    /**
     * Description
     *
     */
    public String getTagline() {
        return this.tagline;
    }
    
    public void setTagline(String tagline) {
        this.tagline = Utilities.removeHTML(tagline);
    }
    
    /**
     * Username of original creator of website.
     */
    public String getCreatorUserName() {
        return creator;
    }
    
    public void setCreatorUserName(String creatorUserName) {
        creator = creatorUserName;
    }

    public String getEmailAddress() {
        return this.emailAddress;
    }
    
    public void setEmailAddress(String emailAddress) {
        this.emailAddress = emailAddress;
    }
    
    /**
     * EditorTheme of the Website.
     */
    public String getEditorTheme() {
        return this.editorTheme;
    }
    
    public void setEditorTheme(String editorTheme) {
        this.editorTheme = editorTheme;
    }
    
    /**
     * Locale of the Website.
     */
    public String getLocale() {
        return this.locale;
    }
    
    public void setLocale(String locale) {
        this.locale = locale;
    }
    
    /**
     * Timezone of the Website.
     */
    public String getTimeZone() {
        return this.timeZone;
    }
    
    public void setTimeZone(String timeZone) {
        this.timeZone = timeZone;
    }
    
    public Date getDateCreated() {
        if (dateCreated == null) {
            return null;
        } else {
            return (Date)dateCreated.clone();
        }
    }

    public void setDateCreated(final Date date) {
        if (date != null) {
            dateCreated = (Date)date.clone();
        } else {
            dateCreated = null;
        }
    }

    /**
     * Set bean properties based on other bean.
     */
    public void setData(Weblog other) {
        
        this.setId(other.getId());
        this.setName(other.getName());
        this.setHandle(other.getHandle());
        this.setTagline(other.getTagline());
        this.setCreatorUserName(other.getCreatorUserName());
        this.setEmailAddress(other.getEmailAddress());
        this.setEditorTheme(other.getEditorTheme());
        this.setLocale(other.getLocale());
        this.setTimeZone(other.getTimeZone());
        this.setVisible(other.getVisible());
        this.setDateCreated(other.getDateCreated());
        this.setEntryDisplayCount(other.getEntryDisplayCount());
        this.setActive(other.getActive());
        this.setCommentsEnabled(other.getCommentsEnabled());
        this.setCommentNotify(other.getCommentNotify());
        this.setLastModified(other.getLastModified());
        this.setWeblogCategories(other.getWeblogCategories());
    }
    
    
    /**
     * Parse locale value and instantiate a Locale object,
     * otherwise return default Locale.
     *
     * @return Locale
     */
    public Locale getLocaleInstance() {
        return I18nUtils.toLocale(getLocale());
    }
    
    
    /**
     * Return TimeZone instance for value of timeZone,
     * otherwise return system default instance.
     * @return TimeZone
     */
    public TimeZone getTimeZoneInstance() {
        if (getTimeZone() == null) {
            this.setTimeZone( TimeZone.getDefault().getID() );
        }
        return TimeZone.getTimeZone(getTimeZone());
    }
    
    public int getEntryDisplayCount() {
        return entryDisplayCount;
    }
    
    public void setEntryDisplayCount(int entryDisplayCount) {
        this.entryDisplayCount = entryDisplayCount;
    }
    
    /**
     * Set to FALSE to disable and hide this weblog from public view.
     */
    public Boolean getVisible() {
        return this.visible;
    }
    
    public void setVisible(Boolean visible) {
        this.visible = visible;
    }
    
    /**
     * Set to FALSE to exclude this weblog from community areas such as the
     * front page and the planet page.
     */
    public Boolean getActive() {
        return active;
    }
    
    public void setActive(Boolean active) {
        this.active = active;
    }

    /** True when signed-in readers may comment on this weblog's entries. */
    public Boolean getCommentsEnabled() {
        return commentsEnabled;
    }

    public void setCommentsEnabled(Boolean commentsEnabled) {
        this.commentsEnabled = commentsEnabled;
    }

    /** True to email the owner when a comment arrives. */
    public Boolean getCommentNotify() {
        return commentNotify;
    }

    public void setCommentNotify(Boolean commentNotify) {
        this.commentNotify = commentNotify;
    }
    
    /**
     * The last time any visible part of this weblog was modified.
     * This includes a change to weblog settings, entries, themes, templates,
     * categories, etc.
     */
    public Date getLastModified() {
        return lastModified;
    }

    public void setLastModified(Date lastModified) {
        this.lastModified = lastModified;
    }

    // getURL()/getAbsoluteURL() used to live here, reaching the URLStrategy
    // through the static service locator from inside a getter. They are built
    // by whoever holds the strategy now: WeblogWrapper.getURL()/getAbsoluteURL()
    // for templates and AdminUrls.weblog()/weblogAbsolute() for the admin JSPs.

    /**
     * The path under the weblog's resources to an icon image.
     */
    public String getIconPath() {
        return iconPath;
    }

    public void setIconPath(String iconPath) {
        this.iconPath = iconPath;
    }

    /**
     * The Umami website UUID the theme macro builds this weblog's tracker
     * tag from. Null or blank disables analytics for this weblog.
     */
    public String getAnalyticsSiteId() {
        return analyticsSiteId;
    }

    public void setAnalyticsSiteId(String analyticsSiteId) {
        this.analyticsSiteId = analyticsSiteId;
    }

    /**
     * The operator's saved link to the Umami share dashboard for this
     * weblog's site. Display-only convenience; never used to build the
     * tracker tag.
     */
    public String getAnalyticsShareUrl() {
        return analyticsShareUrl;
    }

    public void setAnalyticsShareUrl(String analyticsShareUrl) {
        this.analyticsShareUrl = analyticsShareUrl;
    }

    /**
     * The Listmonk list UUID this weblog's subscribe form feeds. Null or
     * blank disables the subscribe form for this weblog; Roller stores no
     * subscriber data itself.
     */
    public String getNewsletterListUuid() {
        return newsletterListUuid;
    }

    public void setNewsletterListUuid(String newsletterListUuid) {
        this.newsletterListUuid = newsletterListUuid;
    }

    /**
     * The hostname this weblog is served at, or null to be served under
     * /<handle>/ on the site host. Stored lowercased; see
     * WeblogConfigController for the validation, and VirtualHostRegistry for
     * how it is resolved on a request.
     */
    public String getCustomDomain() {
        return customDomain;
    }

    public void setCustomDomain(String customDomain) {
        this.customDomain = customDomain;
    }

    /** The business this blog belongs to, or null. */
    public Business getBusiness() {
        return business;
    }

    public void setBusiness(Business business) {
        this.business = business;
    }

    /** Null, or the schema.org place type ("LodgingBusiness"). */
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

    /** ISO 3166-1 alpha-2 country code. */
    public String getPlaceCountry() {
        return placeCountry;
    }

    public void setPlaceCountry(String placeCountry) {
        this.placeCountry = placeCountry;
    }

    public BigDecimal getPlaceLat() {
        return placeLat;
    }

    /**
     * Kept to two decimals (about 1 km), HALF_UP; null stays null. A value
     * whose scale is outside [{@value #MIN_COORDINATE_SCALE},
     * {@value #MAX_COORDINATE_SCALE}] is refused with an
     * IllegalArgumentException: rounding "1E-100000000" computes a power of
     * ten with a hundred million digits and holds the thread for seconds.
     */
    public void setPlaceLat(BigDecimal placeLat) {
        this.placeLat = toTwoPlaces(placeLat);
    }

    public BigDecimal getPlaceLng() {
        return placeLng;
    }

    /** As {@link #setPlaceLat(BigDecimal)}. */
    public void setPlaceLng(BigDecimal placeLng) {
        this.placeLng = toTwoPlaces(placeLng);
    }

    private static final int MIN_COORDINATE_SCALE = -10;
    private static final int MAX_COORDINATE_SCALE = 20;

    private static BigDecimal toTwoPlaces(BigDecimal value) {
        if (value == null) {
            return null;
        }
        if (value.scale() < MIN_COORDINATE_SCALE || value.scale() > MAX_COORDINATE_SCALE) {
            throw new IllegalArgumentException("coordinate scale out of range: " + value.scale());
        }
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    /** This blog's booking link, overriding the business's. */
    public String getBookingUrl() {
        return bookingUrl;
    }

    public void setBookingUrl(String bookingUrl) {
        this.bookingUrl = bookingUrl;
    }

    /**
     * A description for the weblog (its purpose, authors, etc.)
     *
     * This field is meant to hold a paragraph describing the weblog, in contrast
     * to the short sentence or two 'description' attribute meant for blog taglines
     * and HTML header META description tags.
     *
     */
    public String getAbout() {
        return about;
    }

    public void setAbout(String about) {
        this.about = Utilities.removeHTML(about);
    }
    
    
    
    /**
     * Add a category as a child of this category.
     */
    public void addCategory(WeblogCategory category) {

        // make sure category is not null
        if(category == null || category.getName() == null) {
            throw new IllegalArgumentException("Category cannot be null and must have a valid name");
        }

        // make sure we don't already have a category with that name
        if(hasCategory(category.getName())) {
            throw new IllegalArgumentException("Duplicate category name '"+category.getName()+"'");
        }

        // add it to our list of child categories
        getWeblogCategories().add(category);
    }

    public List<WeblogCategory> getWeblogCategories() {
        return weblogCategories;
    }

    public void setWeblogCategories(List<WeblogCategory> cats) {
        this.weblogCategories = cats;
    }

    public boolean hasCategory(String name) {
        for (WeblogCategory cat : getWeblogCategories()) {
            if(name.equals(cat.getName())) {
                return true;
            }
        }
        return false;
    }

    public List<MediaFileDirectory> getMediaFileDirectories() {
        return mediaFileDirectories;
    }

    public void setMediaFileDirectories(List<MediaFileDirectory> mediaFileDirectories) {
        this.mediaFileDirectories = mediaFileDirectories;
    }

    /**
     * Indicates whether this weblog contains the specified media file directory
     *
     * @param name directory name
     *
     * @return true if directory is present, false otherwise.
     */
    public boolean hasMediaFileDirectory(String name) {
        for (MediaFileDirectory directory : this.getMediaFileDirectories()) {
            if (directory.getName().equals(name)) {
                return true;
            }
        }
        return false;
    }

    public MediaFileDirectory getMediaFileDirectory(String name) {
        for (MediaFileDirectory dir : this.getMediaFileDirectories()) {
            if (name.equals(dir.getName())) {
                return dir;
            }
        }
        return null;
    }

}
