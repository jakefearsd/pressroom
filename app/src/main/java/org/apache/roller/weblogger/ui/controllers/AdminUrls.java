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
package org.apache.roller.weblogger.ui.controllers;

import java.util.Objects;
import java.util.function.Supplier;

import org.apache.roller.weblogger.business.URLStrategy;
import org.apache.roller.weblogger.pojos.MediaFile;
import org.apache.roller.weblogger.pojos.Weblog;
import org.apache.roller.weblogger.pojos.WeblogEntry;

/**
 * The one place the admin JSPs build weblog-content urls, exposed to every
 * admin view as the {@code urls} model attribute by {@link BaseController}
 * (e.g. {@code ${urls.weblogAbsolute(actionWeblog)}}, which works inside a
 * {@code c:forEach} where a per-page attribute would not).
 *
 * <p>This replaced five getters on the JPA entities -- {@code Weblog.getURL()}
 * / {@code getAbsoluteURL()}, {@code WeblogEntry.getPermalink()},
 * {@code MediaFile.getPermalink()} / {@code getThumbnailURL()} -- each of which
 * reached the {@link URLStrategy} through the static service locator from
 * inside a getter. Each method here asks the strategy for exactly what the
 * getter it replaced asked for; {@code AdminUrlsTest} pins the arguments.
 *
 * <p>The strategy is supplied lazily and resolved on first use: the helper is
 * built for every controller request, including the install wizard's before
 * the business tier exists, and the {@code @Lazy Weblogger} proxy behind the
 * supplier would try to build the whole graph if it were touched then.
 * (Velocity themes never see this class -- the template wrappers already hold
 * their own, preview-aware strategy.)
 */
public final class AdminUrls {

    private final Supplier<URLStrategy> source;
    private URLStrategy strategy;

    public AdminUrls(Supplier<URLStrategy> source) {
        this.source = Objects.requireNonNull(source, "source");
    }

    /** The weblog's context-relative url -- what {@code Weblog.getURL()} returned. */
    public String weblog(Weblog weblog) {
        Objects.requireNonNull(weblog, "weblog");
        return strategy().getWeblogURL(weblog, null, false);
    }

    /** The weblog's absolute url -- what {@code Weblog.getAbsoluteURL()} returned. */
    public String weblogAbsolute(Weblog weblog) {
        Objects.requireNonNull(weblog, "weblog");
        return strategy().getWeblogURL(weblog, null, true);
    }

    /**
     * The absolute url of a page with this slug, from the one rule the renderer
     * uses -- the site space's pages hang off the context root, and its
     * front-door page is the root itself. The slug is encoded by the strategy.
     */
    public String staticPage(Weblog weblog, String slug) {
        Objects.requireNonNull(weblog, "weblog");
        return strategy().getWeblogStaticPageURL(weblog, slug == null ? "" : slug, true);
    }

    /** The entry's absolute permalink -- what {@code WeblogEntry.getPermalink()} returned. */
    public String entry(WeblogEntry entry) {
        Objects.requireNonNull(entry, "entry");
        return strategy().getWeblogEntryURL(entry.getWebsite(), null, entry.getAnchor(), true);
    }

    /** The media file's absolute url -- what {@code MediaFile.getPermalink()} returned. */
    public String media(MediaFile file) {
        Objects.requireNonNull(file, "file");
        return strategy().getMediaFileURL(file.getWeblog(), file.getId(), true);
    }

    /** The media file's absolute thumbnail url -- what {@code MediaFile.getThumbnailURL()} returned. */
    public String mediaThumbnail(MediaFile file) {
        Objects.requireNonNull(file, "file");
        return strategy().getMediaFileThumbnailURL(file.getWeblog(), file.getId(), true);
    }

    private URLStrategy strategy() {
        if (strategy == null) {
            strategy = Objects.requireNonNull(source.get(), "URLStrategy");
        }
        return strategy;
    }
}
