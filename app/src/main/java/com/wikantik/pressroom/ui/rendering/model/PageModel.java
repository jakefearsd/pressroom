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

package com.wikantik.pressroom.ui.rendering.model; 

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.FormatStyle;
import java.util.Locale;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.commons.validator.routines.UrlValidator;
import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.business.URLStrategy;
import com.wikantik.pressroom.business.Weblogger;
import com.wikantik.pressroom.pojos.MediaFile;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.pojos.WeblogEntry;
import com.wikantik.pressroom.pojos.WeblogPage;
import com.wikantik.pressroom.pojos.wrapper.MediaFileWrapper;
import com.wikantik.pressroom.pojos.wrapper.ThemeTemplateWrapper;
import com.wikantik.pressroom.pojos.wrapper.WeblogCategoryWrapper;
import com.wikantik.pressroom.pojos.wrapper.WeblogEntryWrapper;
import com.wikantik.pressroom.pojos.wrapper.WeblogPageWrapper;
import com.wikantik.pressroom.pojos.wrapper.WeblogWrapper;
import com.wikantik.pressroom.ui.rendering.pagers.WeblogEntriesDayPager;
import com.wikantik.pressroom.ui.rendering.pagers.WeblogEntriesLatestPager;
import com.wikantik.pressroom.ui.rendering.pagers.WeblogEntriesMonthPager;
import com.wikantik.pressroom.ui.rendering.pagers.WeblogEntriesPager;
import com.wikantik.pressroom.ui.rendering.pagers.WeblogEntriesPermalinkPager;
import com.wikantik.pressroom.ui.rendering.util.WeblogPageRequest;
import com.wikantik.pressroom.ui.rendering.util.WeblogRequest;


/**
 * Model which provides information needed to render a weblog page.
 */
public class PageModel implements Model {
    
    private static final Logger log = LoggerFactory.getLogger(PageModel.class);

    /**
     * Same allowlist as {@code CtaShortcode}: a stored canonical-URL override
     * is emitted straight into {@code <link rel="canonical">}, {@code og:url}
     * and the JSON-LD {@code mainEntityOfPage} -- so a {@code javascript:}/
     * {@code data:}/{@code file:} value must never reach any of those sites.
     * Save-time validation (EntryEditController/PageEditController) rejects
     * new ones; this catches rows written before that validation existed.
     */
    private static final UrlValidator CANONICAL_URL_VALIDATOR =
            new UrlValidator(new String[] {"http", "https"});

    private WeblogPageRequest pageRequest = null;
    private URLStrategy urlStrategy = null;
    private Map<String, String[]> requestParameters = null;
    private Weblog weblog = null;

    /**
     * The business-tier facade, handed in through {@code initData} by the
     * servlet that loaded this model (plan Task 10). Protected so the preview
     * and search subclasses share it rather than locating their own.
     */
    protected Weblogger weblogger = null;
    
    
    /**
     * 
     * Creates an un-initialized new instance, Weblogger calls init() to complete
     * construction.
     */
    public PageModel() {}
    
    
    /** 
     * Template context name to be used for model.
     */
    @Override
    public String getModelName() {
        return "model";
    }
    
    
    /** 
     * Init page model based on request. 
     */
    @Override
    @SuppressWarnings("unchecked")
    public void init(Map<String, Object> initData) throws WebloggerException {
        
        // we expect the init data to contain a weblogRequest object
        WeblogRequest weblogRequest = (WeblogRequest) initData.get("parsedRequest");
        if(weblogRequest == null) {
            throw new WebloggerException("expected weblogRequest from init data");
        }
        
        // PageModel only works on page requests, so cast weblogRequest
        // into a WeblogPageRequest and if it fails then throw exception
        if(weblogRequest instanceof WeblogPageRequest) {
            this.pageRequest = (WeblogPageRequest) weblogRequest;
        } else {
            throw new WebloggerException("weblogRequest is not a WeblogPageRequest."+
                    "  PageModel only supports page requests.");
        }
        
        // custom request parameters
        this.requestParameters = (Map<String, String[]>) initData.get("requestParameters");

        // the facade and the url strategy are both required -- no fallback
        weblogger = ModelLoader.requireWeblogger(initData);
        urlStrategy = ModelLoader.requireUrlStrategy(initData);

        // extract weblog object
        weblog = pageRequest.getWeblog();
    }    
    
    
    /**
     * Get the weblog locale used to render this page, null if no locale.
     */
    public String getLocale() {
        return pageRequest.getLocale();
    }
    
    
    /**
     * Get weblog being displayed.
     */
    public WeblogWrapper getWeblog() {
        return WeblogWrapper.wrap(weblog, urlStrategy, weblogger);
    }
    
    
    /**
     * Is this page considered a permalink?
     */
    public boolean isPermalink() {
        return pageRequest.getWeblogAnchor() != null;
    }
    
    
    /**
     * Is this page showing search results?
     */
    public boolean isSearchResults() {
        // the search results model will extend this class and override this
        return false;
    }
    
    
    /**
     * Absolute canonical URL of the page being rendered, for the
     * {@code <link rel="canonical">} tag emitted by the {@code #showSeoHead}
     * macro.
     *
     * <p>On permalinks a non-blank per-entry canonical-URL override wins (the
     * entry is syndicated from elsewhere and canonical credit belongs there);
     * a static page (a {@link WeblogPage} resolved from a bare
     * {@code /<handle>/<slug>} request) honors its own stored override the
     * same way. Otherwise this is the natural absolute URL of the request:
     * the entry permalink, the static page URL (same shape as
     * {@link com.wikantik.pressroom.ui.rendering.model.URLModel#staticPage},
     * so the canonical link matches the href readers actually follow), the
     * custom page URL, or the collection URL (home/category/date/tags --
     * keeping the page number, so page 2 canonicalizes to itself instead of
     * claiming to duplicate page 1). Returns null on search results pages,
     * which have no canonical form.
     */
    public String getCanonicalUrl() {
        if (isSearchResults()) {
            return null;
        }
        if (isPermalink()) {
            WeblogEntry entry = pageRequest.getWeblogEntry();
            if (entry != null && isHttpOrHttps(entry.getCanonicalUrl())) {
                return entry.getCanonicalUrl();
            }
            return urlStrategy.getWeblogEntryURL(weblog,
                    pageRequest.getLocale(), pageRequest.getWeblogAnchor(), true);
        }
        if (pageRequest.getPageSlug() != null) {
            WeblogPage page = pageRequest.getWeblogPageContent();
            if (page != null) {
                if (isHttpOrHttps(page.getCanonicalUrl())) {
                    return page.getCanonicalUrl();
                }
                // The one page-URL rule, absolute like every other branch
                // here (URLModel#staticPage asks for the relative form).
                return urlStrategy.getWeblogStaticPageURL(weblog, page.getSlug(), true);
            }
        }
        if (pageRequest.getWeblogPageName() != null) {
            return urlStrategy.getWeblogPageURL(weblog, pageRequest.getLocale(),
                    pageRequest.getWeblogPageName(), null,
                    pageRequest.getWeblogCategoryName(), pageRequest.getWeblogDate(),
                    pageRequest.getTags(), pageRequest.getPageNum(), true);
        }
        return urlStrategy.getWeblogCollectionURL(weblog, pageRequest.getLocale(),
                pageRequest.getWeblogCategoryName(), pageRequest.getWeblogDate(),
                pageRequest.getTags(), pageRequest.getPageNum(), true);
    }

    /**
     * Whether a stored canonical-URL override is non-blank AND an absolute
     * http(s) URL -- rows written before save-time validation existed may
     * carry a {@code javascript:}/{@code data:}/{@code file:} value, and
     * this is what keeps it out of the rendered head.
     */
    private static boolean isHttpOrHttps(String canonicalUrl) {
        return StringUtils.isNotBlank(canonicalUrl) && CANONICAL_URL_VALIDATOR.isValid(canonicalUrl);
    }


    /**
     * Get weblog entry being displayed or null if none specified by request.
     */
    public WeblogEntryWrapper getWeblogEntry() {
        if(pageRequest.getWeblogEntry() != null) {
            return WeblogEntryWrapper.wrap(pageRequest.getWeblogEntry(), urlStrategy, weblogger);
        }
        return null;
    }
    
    
    /**
     * The static page being rendered, or null on every other weblog view.
     *
     * <p>A {@link WeblogPageWrapper} rather than the raw pojo since the DI
     * wave: the page's rendered content comes from the tier's
     * {@code EntryRenderer}, which the wrapper reaches through the facade it
     * holds -- the entity itself no longer renders.
     */
    public WeblogPageWrapper getPage() {
        WeblogPage page = pageRequest.getWeblogPageContent();
        return page == null ? null : WeblogPageWrapper.wrap(page, urlStrategy, weblogger);
    }


    /**
     * Resolves {@link #getPage()}'s {@code ogImageId} to its
     * {@link MediaFileWrapper}, or null if the page has no Open Graph image
     * set, isn't being rendered, or the id no longer resolves (the media
     * file was deleted independently). Mirrors
     * {@link WeblogEntryWrapper#getOgImage()}; lives here rather than on
     * {@link WeblogPageWrapper} because it predates that wrapper.
     */
    public MediaFileWrapper getPageOgImage() {
        WeblogPage page = pageRequest.getWeblogPageContent();
        if (page == null || page.getOgImageId() == null) {
            return null;
        }
        try {
            MediaFile mediaFile = weblogger
                    .getMediaFileManager().getMediaFile(page.getOgImageId());
            return MediaFileWrapper.wrap(mediaFile, urlStrategy, weblogger);
        } catch (Exception ex) {
            log.debug("Could not resolve media file {}", page.getOgImageId(), ex);
            return null;
        }
    }


    /**
     * Published pages that belong in navigation, in nav order -- the source
     * for {@code #showPageLinks} in every bundled theme.
     *
     * <p>Takes the {@link WeblogWrapper} that {@code $model.weblog} hands
     * templates (not the raw {@link Weblog} pojo): every theme calls this
     * with {@code $model.weblog} or a {@code $weblog} macro parameter that
     * traces back to it, and Velocity resolves an overload by the argument's
     * actual type, so a {@code Weblog}-typed parameter here would silently
     * fail to match and render nothing.
     */
    public List<WeblogPage> getNavPages(WeblogWrapper navWeblog) {
        try {
            return weblogger.getWeblogPageManager()
                    .getPublishedPages(navWeblog.getPojo()).stream()
                    .filter(page -> Boolean.TRUE.equals(page.getShowInNav()))
                    .collect(Collectors.toList());
        } catch (WebloggerException ex) {
            log.error("Error getting nav pages for weblog - {}", navWeblog, ex);
            return Collections.emptyList();
        }
    }


    /**
     * Get weblog entry being displayed or null if none specified by request.
     */
    public ThemeTemplateWrapper getWeblogPage() {
        if(pageRequest.getWeblogPageName() != null) {
            return ThemeTemplateWrapper.wrap(pageRequest.getWeblogPage());
        } else {
            try {
                return ThemeTemplateWrapper.wrap(weblogger.getThemeManager().getTheme(weblog).getDefaultTemplate());
            } catch (WebloggerException ex) {
                log.error("Error getting default page", ex);
            }
        }
        return null;
    }
    
    
    /**
     * Get weblog category specified by request, or null if the category name
     * found in the request does not exist in the current weblog.
     */
    public WeblogCategoryWrapper getWeblogCategory() {
        if(pageRequest.getWeblogCategory() != null) {
            return WeblogCategoryWrapper.wrap(pageRequest.getWeblogCategory(), urlStrategy, weblogger);
        }
        return null;
    }
    
    
    /**
     * The date archive this request names, as the URL carries it
     * ({@code yyyyMM} or {@code yyyyMMdd}), or null off a date archive.
     */
    public String getWeblogDate() {
        return pageRequest.getWeblogDate();
    }

    /**
     * The date archive as a reader would name it, in the weblog's language:
     * "October 2026" for a month, "October 22, 2026" for a day (the
     * locale's long date). Null off a date archive, and for a date the URL
     * syntax allows but the calendar does not (month 13): such an archive
     * simply lists nothing, and a heading naming it would be a guess.
     */
    public String getWeblogDateLabel() {
        String date = pageRequest.getWeblogDate();
        if (date == null) {
            return null;
        }
        Locale locale = weblog.getLocaleInstance();
        try {
            if (date.length() == 8) {
                return LocalDate.parse(date, DateTimeFormatter.BASIC_ISO_DATE)
                        .format(DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG).withLocale(locale));
            }
            // LLLL is the stand-alone month name: "Oktober 2026", and the
            // nominative in languages that inflect it after a day number
            return YearMonth.parse(date, DateTimeFormatter.ofPattern("uuuuMM", Locale.ROOT))
                    .format(DateTimeFormatter.ofPattern("LLLL uuuu", locale));
        } catch (DateTimeParseException e) {
            log.debug("No label for date archive {}", date, e);
            return null;
        }
    }

    /**
     * Returns the list of tags specified in the request /tags/foo+bar
     */
    public List<String> getTags() {
        return pageRequest.getTags();
    }
    

    /**
     * A map of entries representing this page. The collection is grouped by 
     * days of entries.  Each value is a list of entry objects keyed by the 
     * date they were published.
     */
    public WeblogEntriesPager getWeblogEntriesPager() {
        return getWeblogEntriesPager(null);
    }
    
    
    /**
     * A map of entries representing this page - with entries restricted by category.
     * The collection is grouped by days of entries.  
     * Each value is a list of entry objects keyed by the date they were published.
     * @param catArgument Category restriction (null or "nil" for no restriction)
     */
    public WeblogEntriesPager getWeblogEntriesPager(String catArgument) {
        return getWeblogEntriesPager(catArgument, null);
    }
    
    
    /**
     * A map of entries representing this page - with entries restricted by tag.
     * The collection is grouped by days of entries.  
     * Each value is a list of entry objects keyed by the date they were published.
     * @param tagArgument tag restriction (null or "nil" for no restriction)
     */
    public WeblogEntriesPager getWeblogEntriesPagerByTag(String tagArgument) {
        return getWeblogEntriesPager(null, tagArgument);
    }
    
    
    private WeblogEntriesPager getWeblogEntriesPager(String catArgument, String tagArgument) {
        
        // category specified by argument wins over request parameter
        String cat = pageRequest.getWeblogCategoryName();
        if (catArgument != null && !StringUtils.isEmpty(catArgument) && !"nil".equals(catArgument)) {
            cat = catArgument;
        }
        
        List<String> tags = pageRequest.getTags();
        if (tagArgument != null && !StringUtils.isEmpty(tagArgument) && !"nil".equals(tagArgument)) {
            tags = new ArrayList<>();
            tags.add(tagArgument);
        }
        
        String dateString = pageRequest.getWeblogDate();
        
        // determine which mode to use
        if (pageRequest.getWeblogAnchor() != null) {
            return new WeblogEntriesPermalinkPager(
                    urlStrategy, weblogger,
                    weblog,
                    pageRequest.getLocale(),
                    pageRequest.getWeblogPageName(),
                    pageRequest.getWeblogAnchor(),
                    pageRequest.getWeblogDate(),
                    cat,
                    tags,
                    pageRequest.getPageNum());
        } else if (dateString != null && dateString.length() == 8) {
            return new WeblogEntriesDayPager(
                    urlStrategy, weblogger,
                    weblog,
                    pageRequest.getLocale(),
                    pageRequest.getWeblogPageName(),
                    pageRequest.getWeblogAnchor(),
                    pageRequest.getWeblogDate(),
                    cat,
                    tags,
                    pageRequest.getPageNum());
        } else if (dateString != null && dateString.length() == 6) {
            return new WeblogEntriesMonthPager(
                    urlStrategy, weblogger,
                    weblog,
                    pageRequest.getLocale(),
                    pageRequest.getWeblogPageName(),
                    pageRequest.getWeblogAnchor(),
                    pageRequest.getWeblogDate(),
                    cat,
                    tags,
                    pageRequest.getPageNum());
          
        } else {
            return new WeblogEntriesLatestPager(
                    urlStrategy, weblogger,
                    weblog,
                    pageRequest.getLocale(),
                    pageRequest.getWeblogPageName(),
                    pageRequest.getWeblogAnchor(),
                    pageRequest.getWeblogDate(),
                    cat,
                    tags,
                    pageRequest.getPageNum());
        }
    }
        
    
    /**
     * Get request parameter by name.
     */
    public String getRequestParameter(String paramName) {
        if (requestParameters != null) {
            String[] values = requestParameters.get(paramName);
            if (values != null && values.length > 0) {
                return values[0];
            }
        }
        return null;
    }

}
