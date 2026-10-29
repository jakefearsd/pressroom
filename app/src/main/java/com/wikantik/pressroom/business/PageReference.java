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
package com.wikantik.pressroom.business;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

import java.util.Optional;

import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.pojos.WeblogPage;

/**
 * A reference to one page, as the privacy setting stores it: {@code slug} is a
 * page of the site space, {@code handle/slug} a page of that blog. Handles
 * cannot contain {@code /}, so the form is unambiguous.
 *
 * @param handle the blog's handle, or null for the site space
 * @param slug   the page's slug
 */
public record PageReference(String handle, String slug) {

    /** The parsed reference; empty for blank, more than one {@code /}, or a blank part. */
    public static Optional<PageReference> parse(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        String[] parts = value.trim().split("/", -1);
        if (parts.length > 2) {
            return Optional.empty();
        }
        for (String part : parts) {
            if (part.isBlank()) {
                return Optional.empty();
            }
        }
        return parts.length == 1
                ? Optional.of(new PageReference(null, parts[0].trim()))
                : Optional.of(new PageReference(parts[0].trim(), parts[1].trim()));
    }

    /** The page, when it exists and is published. */
    public Optional<WeblogPage> resolvePublished(Weblogger weblogger) throws WebloggerException {
        Weblog blog = weblog(weblogger);
        if (blog == null) {
            return Optional.empty();
        }
        WeblogPage page = weblogger.getWeblogPageManager().getPageBySlug(blog, slug);
        return page != null && page.getStatus() == WeblogPage.PubStatus.PUBLISHED
                ? Optional.of(page) : Optional.empty();
    }

    /** The page's absolute URL; empty when the blog it names does not exist. */
    public Optional<String> absoluteUrl(Weblogger weblogger) throws WebloggerException {
        Weblog blog = weblog(weblogger);
        return blog == null ? Optional.empty()
                : Optional.of(weblogger.getUrlStrategy().getWeblogStaticPageURL(blog, slug, true));
    }

    /**
     * The weblog holding the page. The site space is reached only by the bare
     * form: naming it as a blog ({@code site-space/x}) resolves to nothing.
     */
    @SuppressFBWarnings(
            value = "IMPROPER_UNICODE",
            justification = "Refuses the site space's handle in any case. Handles are ASCII "
                    + "alphanumerics, so a miss could only fail to refuse here; the lookups "
                    + "behind it refuse the site space anyway.")
    private Weblog weblog(Weblogger weblogger) throws WebloggerException {
        if (handle == null) {
            return weblogger.getWeblogManager().getSiteSpace();
        }
        if (Weblog.SITE_SPACE_HANDLE.equalsIgnoreCase(handle)) {
            return null;
        }
        Weblog blog = weblogger.getWeblogManager().getWeblogByHandle(handle);
        return blog == null || blog.isSiteSpace() ? null : blog;
    }
}
