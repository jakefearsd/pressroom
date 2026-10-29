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

package com.wikantik.pressroom.pojos.wrapper;

import java.sql.Timestamp;

import com.wikantik.pressroom.business.URLStrategy;
import com.wikantik.pressroom.business.Weblogger;
import com.wikantik.pressroom.pojos.WeblogPage;

/**
 * The template view of a static page ({@code $model.page}).
 *
 * <p>Every accessor a template reads is a plain delegate except
 * {@link #getRenderedContent()}, which goes through the tier's
 * {@code EntryRenderer} -- the entity no longer renders itself (DI wave,
 * plan Task 14). Like the other wrappers it holds the facade and the
 * request's {@link URLStrategy} (preview-aware) rather than locating either.
 */
public final class WeblogPageWrapper {

    private final WeblogPage pojo;
    private final URLStrategy urlStrategy;
    private final Weblogger weblogger;

    private WeblogPageWrapper(WeblogPage toWrap, URLStrategy strat, Weblogger weblogger) {
        this.pojo = toWrap;
        this.urlStrategy = strat;
        this.weblogger = weblogger;
    }

    /** Wraps a page; null in, null out. */
    public static WeblogPageWrapper wrap(WeblogPage toWrap, URLStrategy strat, Weblogger weblogger) {
        if (toWrap == null) {
            return null;
        }
        return new WeblogPageWrapper(toWrap, strat, weblogger);
    }

    public String getId() {
        return this.pojo.getId();
    }

    public WeblogWrapper getWeblog() {
        return WeblogWrapper.wrap(this.pojo.getWeblog(), urlStrategy, weblogger);
    }

    public String getSlug() {
        return this.pojo.getSlug();
    }

    public String getTitle() {
        return this.pojo.getTitle();
    }

    /** The page's source text, in Markdown, before shortcode expansion. */
    public String getContent() {
        return this.pojo.getContent();
    }

    /**
     * The page's content, rendered through the same pipeline entries use:
     * shortcodes, then markdown, then sanitization.
     */
    public String getRenderedContent() {
        return weblogger.getEntryRenderer().pageContent(this.pojo);
    }

    public WeblogPage.PubStatus getStatus() {
        return this.pojo.getStatus();
    }

    public Boolean getShowInNav() {
        return this.pojo.getShowInNav();
    }

    public int getNavOrder() {
        return this.pojo.getNavOrder();
    }

    public Timestamp getCreated() {
        return this.pojo.getCreated();
    }

    public Timestamp getUpdated() {
        return this.pojo.getUpdated();
    }

    public String getMetaTitle() {
        return this.pojo.getMetaTitle();
    }

    public String getSearchDescription() {
        return this.pojo.getSearchDescription();
    }

    public String getCanonicalUrl() {
        return this.pojo.getCanonicalUrl();
    }

    public Boolean getNoindex() {
        return this.pojo.getNoindex();
    }

    public String getOgImageId() {
        return this.pojo.getOgImageId();
    }

    /** The underlying entity, for the few template sites that need the raw values. */
    public WeblogPage getPojo() {
        return this.pojo;
    }
}
