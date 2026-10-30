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

package com.wikantik.pressroom.ui.rendering.model;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.business.URLStrategy;
import com.wikantik.pressroom.business.Weblogger;
import com.wikantik.pressroom.business.WeblogEntryManager;
import com.wikantik.pressroom.config.WebloggerRuntimeConfig;
import com.wikantik.pressroom.pojos.WeblogEntry;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.ui.rendering.util.WeblogRequest;


/**
 * Provides access to URL building functionality.
 *
 * NOTE: we purposely go against the standard getter/setter bean standard
 * for methods that take arguments so that users get a consistent way to
 * access those methods in their templates. i.e.
 *
 * $url.category("foo")
 *
 * instead of
 *
 * $url.getCategory("foo")
 */
public class URLModel implements Model {
    
    private static final Logger log = LoggerFactory.getLogger(URLModel.class);
    
    protected Weblog weblog = null;
    protected String locale = null;
    
    protected URLStrategy urlStrategy = null;

    /**
     * The business-tier facade, handed in through {@code initData} by the
     * servlet that loaded this model (plan Task 10); shared with the preview
     * subclass.
     */
    protected Weblogger weblogger = null;
    
    
    public URLModel() {}
    
    @Override
    public String getModelName() {
        return "url";
    }
    
    @Override
    public void init(Map<String, Object> initData) throws WebloggerException {
        
        // need a weblog request so that we can know the weblog and locale
        WeblogRequest weblogRequest = (WeblogRequest) initData.get("parsedRequest");
        if(weblogRequest == null) {
            throw new WebloggerException("Expected 'weblogRequest' init param!");
        }
        
        this.weblog = weblogRequest.getWeblog();
        this.locale = weblogRequest.getLocale();

        // the facade and the url strategy are both required -- no fallback
        weblogger = ModelLoader.requireWeblogger(initData);
        urlStrategy = ModelLoader.requireUrlStrategy(initData);
    }
    
    
    /** Relative URL of Roller, e.g. /pressroom */
    public String getSite() {
        return WebloggerRuntimeConfig.getRelativeContextURL();
    }
    
    
    /**
     * Absolute URL of Roller, e.g. http://localhost:8080/pressroom.
     *
     * <p>$url.absoluteSite means the SITE, and every caller (the frontpage
     * theme's brand/menu links, the atom feeds' stylesheet href and site
     * &lt;id&gt;, error-page.vm) is a site-level or control-plane link. By this
     * wave's own design the control plane lives on the site host, so none of
     * those may follow a weblog's custom domain -- a weblog's own urls come
     * from the URL strategy (see MultiWeblogURLStrategy.getWeblogURL), not
     * from this model method. This used to also check the per-weblog
     * "weblog.absoluteurl.&lt;handle&gt;" property; that branch is gone with the
     * property itself.
     */
    public String getAbsoluteSite() {
        return WebloggerRuntimeConfig.getAbsoluteContextURL();
    }
    
    
    /** URL for logging in */  
    public String getLogin() {
        return urlStrategy.getLoginURL(false);
    }
    
    
    /** URL for logging out */
    public String getLogout() {
        return urlStrategy.getLogoutURL(false);
    }
    
    /** URL for a specific UI action */
    public String action(String action, String namespace) {
        if(namespace != null) {
            if("/pressroom-ui".equals(namespace)) {
                return urlStrategy.getActionURL(action, namespace, null, null, true);
            } else if("/pressroom-ui/authoring".equals(namespace)) {
                return urlStrategy.getActionURL(action, namespace, weblog.getHandle(), null, true);
            } else if("/pressroom-ui/admin".equals(namespace)) {
                return urlStrategy.getActionURL(action, namespace, null, null, true);
            }
        }
        return null;
    }
    
    
    public String themeResource(String theme, String filePath) {
        return getSite()+"/themes/"+theme+"/"+filePath;
    }
        
    public String themeResource(String theme, String filePath, boolean absolute) {
        if (absolute) {
            return getAbsoluteSite()+"/themes/"+theme+"/"+filePath;
        }
        return themeResource(theme, filePath);
    }
        
    public String getHome() {
        return urlStrategy.getWeblogCollectionURL(weblog, locale, null, null, null, -1, true);
    }
    
    
    public String home(int pageNum) {
        return urlStrategy.getWeblogCollectionURL(weblog, locale, null, null, null, pageNum, true);
    }
    
    
    public String home(String customLocale) {
        return urlStrategy.getWeblogCollectionURL(weblog, customLocale, null, null, null, -1, true);
    }
    
    
    public String home(String customLocale, int pageNum) {
        return urlStrategy.getWeblogCollectionURL(weblog, customLocale, null, null, null, pageNum, true);
    }
    
    
    public String entry(String anchor) {
        return urlStrategy.getWeblogEntryURL(weblog, locale, anchor, true);
    }


    public String date(String dateString) {
        return urlStrategy.getWeblogCollectionURL(weblog, locale, null, dateString, null, -1, true);
    }
    
    
    public String date(String dateString, int pageNum) {
        return urlStrategy.getWeblogCollectionURL(weblog, locale, null, dateString, null, pageNum, true);
    }
    
    
    public String category(String catName) {
        return urlStrategy.getWeblogCollectionURL(weblog, locale, catName, null, null, -1, true);
    }
    
    
    public String category(String catName, int pageNum) {
        return urlStrategy.getWeblogCollectionURL(weblog, locale, catName, null, null, pageNum, true);
    }
    
    
    public String tag(String tag) {
        return urlStrategy.getWeblogCollectionURL(weblog, locale, null, null, Arrays.asList(tag) , -1, true);
    }
    
    
    public String tag(String tag, int pageNum) {
        return urlStrategy.getWeblogCollectionURL(weblog, locale, null, null, Arrays.asList(tag), pageNum, true);
    }    
    
    
    public String tags(List<String> tags) {
        return urlStrategy.getWeblogCollectionURL(weblog, locale, null, null, tags , -1, true);
    }
    
    
    public String tags(List<String> tags, int pageNum) {
        return urlStrategy.getWeblogCollectionURL(weblog, locale, null, null, tags, pageNum, true);
    }
    
    
    public String collection(String dateString, String catName) {
        return urlStrategy.getWeblogCollectionURL(weblog, locale, catName, dateString, null, -1, true);
    }
    
    
    public String collection(String dateString, String catName, int pageNum) {
        return urlStrategy.getWeblogCollectionURL(weblog, locale, catName, dateString, null, pageNum, true);
    }
    
    
    public String getSearch() {
        return urlStrategy.getWeblogSearchURL(weblog, locale, null, null, -1, false);
    }
    
    public String search(String query, int pageNum) {
        return urlStrategy.getWeblogSearchURL(weblog, locale, query, null, pageNum, false);
    }
    
    
    public String search(String query, String catName, int pageNum) {
        return urlStrategy.getWeblogSearchURL(weblog, locale, query, catName, pageNum, false);
    }
    
    public String absoluteSearch(String query, String catName, int pageNum) {
        return urlStrategy.getWeblogSearchURL(weblog, locale, query, catName, pageNum, true);
    }        
    
    public String page(String pageLink) {
        return urlStrategy.getWeblogPageURL(weblog, locale, pageLink, null, null, null, null, -1, true);
    }
    
    
    public String page(String pageLink, String dateString, String catName, int pageNum) {
        return urlStrategy.getWeblogPageURL(weblog, locale, pageLink, null, catName, dateString, null, pageNum, true);
    }


    /**
     * URL for a static {@link com.wikantik.pressroom.pojos.WeblogPage},
     * routed at the bare {@code /<handle>/<slug>} path -- distinct from
     * {@link #page(String)}, which builds the {@code /page/<link>} URL for a
     * template page.
     *
     * <p>The slug is URL-encoded: {@code savePage} only rejects a blank
     * slug, one containing '/', and reserved names, so quote/angle-bracket
     * characters can otherwise reach this href raw.
     */
    public String staticPage(String slug) {
        return urlStrategy.getWeblogStaticPageURL(weblog, slug, false);
    }

    
    public String resource(String filePath) {
        return urlStrategy.getWeblogResourceURL(weblog, filePath, true);
    }
    
    
    public FeedURLS getFeed() {
        return new FeedURLS();
    }
    
    
    /** URL for editing a weblog entry */
    public String editEntry(String anchor) {
        try {
            // need to determine entryId from anchor
            WeblogEntryManager wmgr = weblogger.getWeblogEntryManager();
            WeblogEntry entry = wmgr.getWeblogEntryByAnchor(weblog, anchor);
            if(entry != null) {
                return urlStrategy.getEntryEditURL(weblog.getHandle(), entry.getId(), false);
            }
        } catch (WebloggerException ex) {
            log.error("Error looking up entry by anchor - {}", anchor, ex);
        }
        return null;
    } 
    
    
    /** URL for creating a new weblog entry */
    public String getCreateEntry() {
        return urlStrategy.getEntryAddURL(weblog.getHandle(), false);
    }
    
    
    /** URL for editing weblog settings */
    public String getEditSettings() {
        return urlStrategy.getWeblogConfigURL(weblog.getHandle(), false);
    }
    
    
    ///////  Inner Classes  ///////
    
    public class FeedURLS {

        public EntryFeedURLS getEntries() {
            return new EntryFeedURLS();
        }
    }

    public class EntryFeedURLS {

        public String getAtom() {
            return urlStrategy.getWeblogFeedURL(weblog, locale, "entries", "atom", null, null, null, false, true);
        }

        public String atom(String catName, boolean excerpts) {
            return urlStrategy.getWeblogFeedURL(weblog, locale, "entries", "atom", catName, null, null, excerpts, true);
        }

        public String atomByTags(List<String> tags, boolean excerpts) {
            return urlStrategy.getWeblogFeedURL(weblog, locale, "entries", "atom", null, null, tags, excerpts, true);
        }
    }
}
