/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  The ASF licenses this file to You
 * under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.  For additional information regarding
 * copyright in this work, please see the NOTICE file in the top level
 * directory of this distribution.
 */

package com.wikantik.pressroom.pojos.wrapper;

import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import org.apache.commons.text.StringEscapeUtils;
import com.wikantik.pressroom.WebloggerException;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import com.wikantik.pressroom.config.WebloggerRuntimeConfig;
import com.wikantik.pressroom.business.URLStrategy;
import com.wikantik.pressroom.business.Weblogger;
import com.wikantik.pressroom.pojos.TagStat;
import com.wikantik.pressroom.pojos.WeblogCategory;
import com.wikantik.pressroom.pojos.WeblogEntry;
import com.wikantik.pressroom.pojos.WeblogEntrySearchCriteria;
import com.wikantik.pressroom.util.HTMLSanitizer;
import com.wikantik.pressroom.util.ServingSince;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.stream.Collectors;
import com.wikantik.pressroom.pojos.ThemeTemplate.ComponentType;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.pojos.WeblogTheme;


/**
 * Pojo safety wrapper for Weblog objects.
 */
public final class WeblogWrapper {

    private static final Logger log = LoggerFactory.getLogger(WeblogWrapper.class);

    /** The template API's cap on a recent-entries request, whatever length was asked for. */
    private static final int MAX_ENTRIES = 100;

    // keep a reference to the wrapped pojo
    private final Weblog pojo;

    // url strategy to use for any url building
    private final URLStrategy urlStrategy;

    // this is private so that we can force the use of the .wrap(pojo) method
    // the business tier, for the lookups the template API needs
    private final Weblogger weblogger;

    private WeblogWrapper(Weblog toWrap, URLStrategy strat, Weblogger weblogger) {
        this.pojo = toWrap;
        this.urlStrategy = strat;
        this.weblogger = weblogger;
    }
    
    
    /**
     * Wrap the given pojo if it is not null. The wrapper is the template API
     * and does its own lookups: it is handed the (possibly preview-aware)
     * {@link URLStrategy} every URL it emits must come from, and the business
     * tier it resolves related objects through -- never a static locator.
     */
    public static WeblogWrapper wrap(Weblog toWrap, URLStrategy strat, Weblogger weblogger) {
        if (toWrap != null) {
            return new WeblogWrapper(toWrap, strat, weblogger);
        }
        return null;
    }


    /** The theme this weblog renders with, resolved through the facade the wrapper was given. */
    private WeblogTheme theme() throws WebloggerException {
        return weblogger.getThemeManager().getTheme(this.pojo);
    }


    public ThemeTemplateWrapper getTemplateByAction(ComponentType action) throws WebloggerException {
        return ThemeTemplateWrapper.wrap(theme().getTemplateByAction(action));
    }
    
    
    public ThemeTemplateWrapper getTemplateByName(String name) throws WebloggerException {
        return ThemeTemplateWrapper.wrap(theme().getTemplateByName(name));
    }
    
    
    public ThemeTemplateWrapper getTemplateByLink(String link) throws WebloggerException {
        return ThemeTemplateWrapper.wrap(theme().getTemplateByLink(link));
    }
    
    
    public List<ThemeTemplateWrapper> getTemplates() throws WebloggerException {
        return theme().getTemplates().stream()
                .map(ThemeTemplateWrapper::wrap)
                .collect(Collectors.toList());
    }
    
    
    public String getId() {
        return this.pojo.getId();
    }
    
    
    public String getHandle() {
        return this.pojo.getHandle();
    }
    
    
    public String getName() {
        if (this.pojo.isSiteSpace()) {
            return StringEscapeUtils.escapeHtml4(
                    WebloggerRuntimeConfig.getProperty("site.name"));
        }
        return StringEscapeUtils.escapeHtml4(this.pojo.getName());
    }
    
    public String getTagline() {
        if (this.pojo.isSiteSpace()) {
            return HTMLSanitizer.conditionallySanitizeText(
                    WebloggerRuntimeConfig.getProperty("site.description"));
        }
        return HTMLSanitizer.conditionallySanitizeText(this.pojo.getTagline());
    }

    /** True for the hidden site space weblog that owns the site's own pages. */
    public boolean isSiteSpace() {
        return this.pojo.isSiteSpace();
    }

    /**
     * The weblog's creator, resolved by name through the tier this wrapper
     * was given; null (never an exception) when the name no longer resolves,
     * so a byline cannot break a page.
     */
    public UserWrapper getCreator() {
        try {
            return UserWrapper.wrap(weblogger.getUserManager()
                    .getUserByUserName(this.pojo.getCreatorUserName()));
        } catch (Exception e) {
            log.error("ERROR fetching user object for username: {}", this.pojo.getCreatorUserName(), e);
            return null;
        }
    }

    /**
     * The Umami website UUID the theme macro builds this weblog's tracker
     * tag from, or null if analytics is disabled for this weblog.
     */
    public String getAnalyticsSiteId() {
        return this.pojo.getAnalyticsSiteId();
    }

    /**
     * The operator's saved link to the Umami share dashboard for this
     * weblog's site, or null if none is set.
     */
    public String getAnalyticsShareUrl() {
        return this.pojo.getAnalyticsShareUrl();
    }

    /**
     * The Listmonk list UUID this weblog's subscribe form feeds, or null if
     * the subscribe form is disabled for this weblog.
     */
    public String getNewsletterListUuid() {
        return this.pojo.getNewsletterListUuid();
    }

    public String getEmailAddress() {
        return this.pojo.getEmailAddress();
    }
    
    
    public String getEditorTheme() {
        return this.pojo.getEditorTheme();
    }
    
    
    public String getLocale() {
        return this.pojo.getLocale();
    }
    
    
    public String getTimeZone() {
        return this.pojo.getTimeZone();
    }
    
    
    public Date getDateCreated() {
        return this.pojo.getDateCreated();
    }


    public Locale getLocaleInstance() {
        return this.pojo.getLocaleInstance();
    }


    /**
     * The weblog's locale as a BCP-47 language tag, for {@code <html lang>}.
     *
     * <p>{@link #getLocale()} returns Java's underscore form as stored
     * ({@code en_US}), which is not a valid {@code lang} attribute value -- a
     * user agent that cannot parse it treats the document as having no
     * declared language, which is what every bundled theme's markup used to
     * do. {@link Locale#toLanguageTag()} is the conversion
     * ({@code en_US -> en-US}); an unset locale falls back to the JVM default
     * through {@code Weblog#getLocaleInstance}, so this never returns null.
     */
    public String getLanguageTag() {
        return this.pojo.getLocaleInstance().toLanguageTag();
    }
    
    
    public TimeZone getTimeZoneInstance() {
        return this.pojo.getTimeZoneInstance();
    }
    
    
    public int getEntryDisplayCount() {
        return this.pojo.getEntryDisplayCount();
    }
    
    
    public Boolean getVisible() {
        return this.pojo.getVisible();
    }

    /* deprecated in Roller 5.1 */
    @Deprecated
    public Boolean getEnabled() {
        return getVisible();
    }

    public Boolean getActive() {
        return this.pojo.getActive();
    }

    public Boolean getCommentsEnabled() {
        return this.pojo.getCommentsEnabled();
    }

    public Boolean getCommentNotify() {
        return this.pojo.getCommentNotify();
    }

    /**
     * The hostname the weblog is served on besides the site host, or null.
     * Read off the weblog, never the request: a rendered page is cached by
     * handle and shared by both hosts (see MultiWeblogURLStrategy.getWeblogURL).
     */
    public String getCustomDomain() {
        return this.pojo.getCustomDomain();
    }
    
    
    public Date getLastModified() {
        return this.pojo.getLastModified();
    }


    public String getStylesheet() throws WebloggerException {
        // custom stylesheet comes from the weblog theme
        if(theme().getStylesheet() != null) {
            String url = urlStrategy.getWeblogPageURL(this.pojo, null, theme().getStylesheet().getLink(), null, null, null, null, 0, false);
            // Versioned by what dates the stylesheet's Last-Modified (the
            // weblog's change or this server's start): Cloudflare keeps a .css
            // for four hours without asking, so a fixed address outlived a
            // deploy, new markup on old rules.
            Date changed = this.pojo.getLastModified();
            long version = ServingSince.atLeast(changed == null ? 0L : changed.getTime()) / 1000;
            return url + (url.contains("?") ? "&" : "?") + "v=" + version;
        }
        return null;
    }

    
    /**
     * Get path to weblog icon image if defined.
     *
     * This method is somewhat smart in the sense that it will check the entered
     * icon value and if it is a full url then it will be left alone, but if it
     * is a relative path to a file in the weblog's uploads section then it will
     * build the full url to that resource and return it.
     */
    public String getIcon() {
        
        String iconPath = this.pojo.getIconPath();
        if(iconPath == null) {
            return null;
        }
        
        if(iconPath.startsWith("http") || iconPath.startsWith("/")) {
            // if icon path is a relative path then assume it's a weblog resource
            return iconPath;
        } else {
            // otherwise it's just a plain old url
            return urlStrategy.getWeblogResourceURL(this.pojo, iconPath, false);
        }
        
    }
    
    
    public String getAbout() {
        return HTMLSanitizer.conditionallySanitizeText(this.pojo.getAbout());
    }
    
    
    
    /**
     * Relative weblog url, from the strategy this wrapper was given -- a
     * theme preview stays inside the preview, the live site links to itself.
     */
    // NM_CONFUSING pairs this with AbstractPager.getUrl() (inherited into
    // FeedModel$FeedEntriesPager, which is what the finding names) purely on
    // case -- the two classes share no relationship. getURL() is the template
    // API's spelling ($model.weblog.URL, $entry.website.URL), and getUrl() on
    // AbstractPager is inherited by every pager in the rendering model.
    // Renaming either would break its own unrelated call chain to fix a purely
    // cosmetic collision. The suppression used to sit on Weblog.getURL(),
    // which this wrapper method replaced when the entity stopped building urls.
    @SuppressFBWarnings(
            value = "NM_CONFUSING",
            justification = "getURL() is the template API's spelling; the method it collides with "
                    + "on case alone, AbstractPager.getUrl(), is inherited by every pager in the "
                    + "rendering model. Neither side can be renamed without breaking its own, "
                    + "entirely unrelated call chain.")
    public String getURL() {
        return urlStrategy.getWeblogURL(this.pojo, null, false);
    }


    public String getAbsoluteURL() {
        return urlStrategy.getWeblogURL(this.pojo, null, true);
    }
    
    
    /**
     * Get weblog entry specified by anchor or null if no such entry exists.
     */
    public WeblogEntryWrapper getWeblogEntry(String anchor) {
        WeblogEntry entry = null;
        try {
            entry = weblogger.getWeblogEntryManager().getWeblogEntryByAnchor(this.pojo, anchor);
        } catch (WebloggerException e) {
            log.error("ERROR: getting entry by anchor");
        }
        return WeblogEntryWrapper.wrap(entry, urlStrategy, weblogger);
    }


    public List<WeblogCategoryWrapper> getWeblogCategories() {
        return this.pojo.getWeblogCategories().stream()
                .map(cat -> WeblogCategoryWrapper.wrap(cat, urlStrategy, weblogger))
                .collect(Collectors.toList());
    }

    public WeblogCategoryWrapper getWeblogCategory(String categoryName) {
        WeblogCategory category = null;
        try {
            if (categoryName != null && !"nil".equals(categoryName)) {
                category = weblogger.getWeblogEntryManager()
                        .getWeblogCategoryByName(this.pojo, categoryName);
            } else if (!this.pojo.getWeblogCategories().isEmpty()) {
                // Same "first category found" fallback saveWeblogEntry uses, and
                // the same reason for the guard: this is reachable from a
                // template, where an unchecked NoSuchElementException escapes the
                // catch below and takes the whole render with it. Returning null
                // is what every other failure here already does.
                category = this.pojo.getWeblogCategories().getFirst();
            }
        } catch (WebloggerException e) {
            log.error("ERROR: fetching category: {}", categoryName, e);
        }
        return WeblogCategoryWrapper.wrap(category, urlStrategy, weblogger);
    }

    
    /**
     * Get up to 100 most recent published entries in weblog.
     * @param cat Category name or null (or "nil") for no category restriction
     * @param length Max entries to return (1-100)
     */
    public List<WeblogEntryWrapper> getRecentWeblogEntries(String cat, int length) {
        if (cat != null && "nil".equals(cat)) {
            cat = null;
        }
        WeblogEntrySearchCriteria wesc = new WeblogEntrySearchCriteria();
        wesc.setCatName(cat);
        return recentEntries(wesc, length);
    }
    
    
    /**
     * Get up to 100 most recent published entries in weblog.
     * @param tag Blog entry tag to query by, or null (or "nil") for any
     * @param length Max entries to return (1-100)
     */
    public List<WeblogEntryWrapper> getRecentWeblogEntriesByTag(String tag, int length) {
        if (tag != null && "nil".equals(tag)) {
            tag = null;
        }
        WeblogEntrySearchCriteria wesc = new WeblogEntrySearchCriteria();
        wesc.setTags(tag != null ? List.of(tag) : Collections.emptyList());
        return recentEntries(wesc, length);
    }

    /**
     * The template API's recent-entries contract: published only, at most
     * {@link #MAX_ENTRIES} however many were asked for, nothing for a
     * non-positive length, and an empty list (logged) rather than an exception
     * when the query fails -- a sidebar must not take the page down.
     */
    private List<WeblogEntryWrapper> recentEntries(WeblogEntrySearchCriteria wesc, int length) {
        if (length > MAX_ENTRIES) {
            length = MAX_ENTRIES;
        }
        if (length < 1) {
            return Collections.emptyList();
        }
        try {
            wesc.setWeblog(this.pojo);
            wesc.setStatus(WeblogEntry.PubStatus.PUBLISHED);
            wesc.setMaxResults(length);
            return weblogger.getWeblogEntryManager().getWeblogEntries(wesc).stream()
                    .map(entry -> WeblogEntryWrapper.wrap(entry, urlStrategy, weblogger))
                    .collect(Collectors.toList());
        } catch (WebloggerException e) {
            log.error("ERROR: getting recent entries", e);
        }
        return Collections.emptyList();
    }
    
    
    /**
     * Get a list of TagStats objects for the most popular tags
     *
     * @param sinceDays Number of days into past (or -1 for all days)
     * @param length    Max number of tags to return.
     */
    public List<TagStat> getPopularTags(int sinceDays, int length) {
        Date startDate = null;
        if (sinceDays > 0) {
            Calendar cal = Calendar.getInstance();
            cal.setTime(new Date());
            cal.add(Calendar.DATE, -1 * sinceDays);
            startDate = cal.getTime();
        }
        try {
            return weblogger.getWeblogEntryManager().getPopularTags(this.pojo, startDate, 0, length);
        } catch (Exception e) {
            log.error("ERROR: fetching popular tags for weblog {}", this.pojo.getName(), e);
        }
        return Collections.emptyList();
    }


    public long getEntryCount() {
        long count = 0;
        try {
            count = weblogger.getWeblogEntryManager().getEntryCount(this.pojo);
        } catch (WebloggerException e) {
            log.error("Error getting entry count for weblog {}", this.pojo.getName(), e);
        }
        return count;
    }
    
    
    /**
     * this is a special method to access the original pojo
     * we don't really want to do this, but it's necessary
     * because some parts of the rendering process still need the
     * original pojo object
     */
    public Weblog getPojo() {
        return this.pojo;
    }
}
