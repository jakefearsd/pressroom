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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.business.URLStrategy;
import com.wikantik.pressroom.business.Weblogger;
import com.wikantik.pressroom.business.discovery.BlogCard;
import com.wikantik.pressroom.business.discovery.FrontPageSelection;
import com.wikantik.pressroom.config.WebloggerRuntimeConfig;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.pojos.WeblogEntry;
import com.wikantik.pressroom.pojos.wrapper.WeblogEntryWrapper;
import com.wikantik.pressroom.pojos.wrapper.WeblogWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@code $discovery}: the discovery picker's selections for templates -- the
 * front page's sections ({@link #getFrontPage()}) and a blog's "more from the
 * site" posts ({@link #moreFromSite}) -- as the wrappers themes already use.
 * Every entry is a {@link WeblogEntryWrapper} (titles stay stored-escaped, so
 * a template emits them bare) and every weblog a {@link WeblogWrapper} (whose
 * name is already escaped), all built from the url strategy the renderer
 * handed in, so a preview links within the preview.
 *
 * <p>The model is loaded for every page, so it asks the picker for nothing
 * until a template does, and for the front page at most once per render.
 */
public class DiscoveryModel implements Model {

    private static final Logger log = LoggerFactory.getLogger(DiscoveryModel.class);

    /** Posts in a blog's "more from the site" block: a fixed number, not a setting (spec). */
    static final int MORE_FROM_SITE_POSTS = 3;

    /**
     * The front page's sections, wrapped: {@link FrontPageSelection} section
     * for section. {@code headline} is null only when nothing is eligible;
     * {@code headlineRanked} says whether it may be labelled "most read".
     */
    public record FrontPage(WeblogEntryWrapper headline, boolean headlineRanked, List<WeblogEntryWrapper> popular,
            List<Card> blogs, List<WeblogEntryWrapper> different, List<WeblogWrapper> allBlogs) {

        public FrontPage {
            popular = List.copyOf(popular);
            blogs = List.copyOf(blogs);
            different = List.copyOf(different);
            allBlogs = List.copyOf(allBlogs);
        }
    }

    /** One "explore the blogs" card, wrapped: {@link BlogCard}. */
    public record Card(WeblogWrapper weblog, List<WeblogEntryWrapper> topEntries) {

        public Card {
            topEntries = List.copyOf(topEntries);
        }
    }

    private Weblogger weblogger;
    private URLStrategy urlStrategy;
    private FrontPage frontPage;

    @Override
    public String getModelName() {
        return "discovery";
    }

    @Override
    public void init(Map<String, Object> initData) throws WebloggerException {
        weblogger = ModelLoader.requireWeblogger(initData);
        urlStrategy = ModelLoader.requireUrlStrategy(initData);
    }

    /**
     * The front page's sections; asked of the picker on the first call of a
     * render. Display path: a picker that fails renders empty sections, not
     * a 500.
     */
    public FrontPage getFrontPage() {
        if (frontPage == null) {
            FrontPageSelection selection;
            try {
                selection = weblogger.getDiscoveryManager().selectFrontPage();
            } catch (RuntimeException e) {
                log.error("Discovery could not select the front page; rendering it empty", e);
                selection = FrontPageSelection.empty();
            }
            List<Card> cards = new ArrayList<>();
            for (BlogCard card : selection.blogs()) {
                cards.add(new Card(wrap(card.weblog()), wrapEntries(card.topEntries())));
            }
            List<WeblogWrapper> allBlogs = new ArrayList<>();
            for (Weblog weblog : selection.allBlogs()) {
                allBlogs.add(wrap(weblog));
            }
            frontPage = new FrontPage(
                    selection.headline() == null ? null : wrap(selection.headline()), selection.headlineRanked(),
                    wrapEntries(selection.popular()), cards, wrapEntries(selection.different()), allBlogs);
        }
        return frontPage;
    }

    /**
     * Up to three posts from weblogs other than {@code current}, for its
     * "more from the site" block. Empty -- so the block shows only the
     * places list and the link home -- on a weblog that has turned the
     * block off and when nothing else is eligible; also on the site space
     * and with no weblog, where the macro renders nothing anyway. Display path: a picker that
     * fails leaves the block out rather than 500ing the page from its footer.
     */
    public List<WeblogEntryWrapper> moreFromSite(WeblogWrapper current) {
        if (current == null || current.isSiteSpace() || !current.getShowMoreFromSite()) {
            return List.of();
        }
        try {
            return wrapEntries(weblogger.getDiscoveryManager()
                    .selectMoreFromSite(current.getPojo(), MORE_FROM_SITE_POSTS));
        } catch (RuntimeException e) {
            log.error("Discovery could not select more from the site for {}; leaving the block out",
                    current.getHandle(), e);
            return List.of();
        }
    }

    /**
     * The site root, {@code <ctx>/}, absolute: the address of the discovery
     * front page from any blog, a custom-domain one included. Built by the url
     * strategy from the site space; with no site space (or none that can be
     * looked up) the root is still the absolute context url.
     */
    public String getHomeUrl() {
        Weblog site = null;
        try {
            site = weblogger.getWeblogManager().getSiteSpace();
        } catch (WebloggerException e) {
            // a link, not a decision: the bare root is right without one
            log.warn("Could not look up the site space for the site root link", e);
        }
        return site != null ? urlStrategy.getWeblogURL(site, null, true)
                : WebloggerRuntimeConfig.getAbsoluteContextURL() + "/";
    }

    private WeblogWrapper wrap(Weblog weblog) {
        return WeblogWrapper.wrap(weblog, urlStrategy, weblogger);
    }

    private WeblogEntryWrapper wrap(WeblogEntry entry) {
        return WeblogEntryWrapper.wrap(entry, urlStrategy, weblogger);
    }

    private List<WeblogEntryWrapper> wrapEntries(List<WeblogEntry> entries) {
        List<WeblogEntryWrapper> wrapped = new ArrayList<>(entries.size());
        for (WeblogEntry entry : entries) {
            wrapped.add(wrap(entry));
        }
        return wrapped;
    }
}
