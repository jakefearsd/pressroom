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

package org.apache.roller.weblogger.ui.controllers;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.roller.weblogger.WebloggerException;
import org.apache.roller.weblogger.business.Weblogger;
import org.apache.roller.weblogger.pojos.ReaderComment;
import org.apache.roller.weblogger.pojos.Weblog;
import org.apache.roller.weblogger.pojos.WeblogCategory;
import org.apache.roller.weblogger.pojos.WeblogEntry;
import org.apache.roller.weblogger.pojos.WeblogPage;
import org.apache.roller.weblogger.pojos.WeblogRedirect;
import org.apache.roller.weblogger.pojos.WeblogTemplate;

/**
 * By-id ownership lookups for {@code WeblogEntry}, {@code WeblogCategory},
 * {@code WeblogTemplate} and {@code WeblogPage}, extracted from
 * {@link BaseController}'s four {@code lookupX} methods (Task 7).
 *
 * <p>{@code getWeblogEntry}/{@code getWeblogCategory}/{@code getTemplate}/
 * {@code getPage} are each a global by-id lookup, and every id that reaches
 * them arrives as client input. The permission interceptor only vouches for
 * the <em>action</em> weblog, so without the comparison performed here, any
 * editor could read or rewrite any other weblog's rows by guessing an id.
 * This is the single place that check is made; a new caller (e.g. a REST
 * controller) gets it by calling here rather than by copying the check
 * again.
 *
 * <p>All four methods return {@code null} when the id is blank, unknown, or
 * owned by a different weblog than the one passed in -- an unknown id and a
 * foreign one are deliberately indistinguishable to the caller, so a probe
 * cannot map one weblog's ids from another.
 *
 * <p>This is a pure extraction: every body here is moved verbatim from
 * {@code BaseController}, which now delegates to it.
 */
public final class WeblogOwnership {

    private static final Logger log = LoggerFactory.getLogger(WeblogOwnership.class);

    private WeblogOwnership() {
    }

    /**
     * The entry with this id, but only when it belongs to {@code weblog}.
     */
    public static WeblogEntry entry(Weblogger weblogger, String id, Weblog weblog) {
        // Blank as well as null: an empty id names nothing, and some managers
        // read an empty string as a wildcard rather than a miss.
        if (id == null || id.isBlank()) {
            return null;
        }
        try {
            WeblogEntry entry = weblogger.getWeblogEntryManager().getWeblogEntry(id);
            if (entry == null || entry.getWebsite() == null
                    || !entry.getWebsite().equals(weblog)) {
                return null;
            }
            return entry;
        } catch (WebloggerException ex) {
            log.error("Error looking up an entry for weblog {}", handleOf(weblog), ex);
        }
        return null;
    }

    /**
     * The category with this id, but only when it belongs to {@code weblog}.
     */
    public static WeblogCategory category(Weblogger weblogger, String id, Weblog weblog) {
        // Blank as well as null: an empty id names nothing, and some managers
        // read an empty string as a wildcard rather than a miss.
        if (id == null || id.isBlank()) {
            return null;
        }
        try {
            WeblogCategory category = weblogger.getWeblogEntryManager().getWeblogCategory(id);
            if (category == null || category.getWeblog() == null
                    || !category.getWeblog().equals(weblog)) {
                return null;
            }
            return category;
        } catch (WebloggerException ex) {
            log.error("Error looking up a category for weblog {}", handleOf(weblog), ex);
        }
        return null;
    }

    /**
     * The template with this id, but only when it belongs to {@code weblog}.
     */
    public static WeblogTemplate template(Weblogger weblogger, String id, Weblog weblog) {
        // Blank as well as null: an empty id names nothing, and some managers
        // read an empty string as a wildcard rather than a miss.
        if (id == null || id.isBlank()) {
            return null;
        }
        try {
            WeblogTemplate template = weblogger.getWeblogManager().getTemplate(id);
            if (template == null || template.getWeblog() == null
                    || !template.getWeblog().equals(weblog)) {
                return null;
            }
            return template;
        } catch (WebloggerException ex) {
            log.error("Error looking up a template for weblog {}", handleOf(weblog), ex);
        }
        return null;
    }

    /**
     * The redirect rule with this id, but only when it belongs to
     * {@code weblog}.
     */
    public static WeblogRedirect redirect(Weblogger weblogger, String id, Weblog weblog) {
        if (StringUtils.isBlank(id)) {
            return null;
        }
        try {
            WeblogRedirect rule = weblogger.getWeblogRedirectManager().getRedirect(id);
            if (rule == null || rule.getWeblog() == null
                    || !rule.getWeblog().equals(weblog)) {
                return null;
            }
            return rule;
        } catch (WebloggerException ex) {
            log.error("Error looking up a redirect for weblog {}", handleOf(weblog), ex);
        }
        return null;
    }

    /**
     * The page with this id, but only when it belongs to {@code weblog}.
     */
    public static WeblogPage page(Weblogger weblogger, String id, Weblog weblog) {
        if (StringUtils.isBlank(id)) {
            return null;
        }
        try {
            WeblogPage page = weblogger.getWeblogPageManager().getPage(id);
            if (page == null || page.getWeblog() == null
                    || !page.getWeblog().equals(weblog)) {
                return null;
            }
            return page;
        } catch (WebloggerException ex) {
            log.error("Error looking up a page for weblog {}", handleOf(weblog), ex);
        }
        return null;
    }

    /**
     * The reader comment with this id, but only when its entry belongs to
     * {@code weblog}.
     */
    public static ReaderComment comment(Weblogger weblogger, String id, Weblog weblog) {
        if (StringUtils.isBlank(id)) {
            return null;
        }
        try {
            ReaderComment comment = weblogger.getCommentManager().getComment(id);
            if (comment == null || comment.getEntry() == null
                    || comment.getEntry().getWebsite() == null
                    || !comment.getEntry().getWebsite().equals(weblog)) {
                return null;
            }
            return comment;
        } catch (WebloggerException ex) {
            log.error("Error looking up a comment for weblog {}", handleOf(weblog), ex);
        }
        return null;
    }

    /**
     * The weblog to name in a failed lookup's log line. The id is never logged:
     * it is a request parameter, and a CR/LF in it could forge a line of its own.
     */
    private static String handleOf(Weblog weblog) {
        return weblog == null ? "-" : weblog.getHandle();
    }
}
