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

package com.wikantik.pressroom.ui.controllers;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.business.WeblogEntryManager;
import com.wikantik.pressroom.business.WeblogPageManager;
import com.wikantik.pressroom.pojos.ReservedSlugs;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.pojos.WeblogPage;
import com.wikantik.pressroom.ui.rendering.util.LocaleSegment;
import com.wikantik.pressroom.ui.restapi.ProblemCode;

/**
 * What a page's slug and strand switch must satisfy, shared by the page
 * editor and the pages API (spec 2026-11-20-jsp-rules-and-view-models-design,
 * Task 4). Each rule returns a {@link RuleViolation}, or null when the value
 * passes, and touches no screen. A lookup that fails throws from the rules
 * that need the store: the API lets that through as its opaque 500, and the
 * screens call {@link #slugProblem}, which turns it into a refusal.
 */
public final class PageRules {

    private static final Logger log = LoggerFactory.getLogger(PageRules.class);

    /** The API's name for the slug field (the screen's field id is its own). */
    public static final String SLUG = "slug";
    public static final String STRAND = "strand";

    private PageRules() {
    }

    /** The slug a new page left without one takes: its title's. May be empty, which the shape rule refuses. */
    public static String defaultSlug(String title) {
        return WeblogPage.slugFromTitle(title);
    }

    /**
     * The first rule {@code slug} breaks on its own, without the store, in
     * the order the API judges them: blank or reserved (a protected name, or
     * one that reads as a locale), a {@code /}, over {@link
     * ColumnWidths#PAGE_SLUG} characters. All are judged on the trimmed value
     * (what is stored), but the API's details quote the slug as sent. Null
     * when it passes.
     */
    public static RuleViolation slugShapeProblem(String slug) {
        String trimmed = slug == null ? null : slug.trim();
        if (trimmed == null || trimmed.isEmpty()) {
            // The screen words a blank slug as "invalid" (it says blank or slash); the API's code is the reserved one.
            return RuleViolation.invalid(SLUG, "pageEdit.error.slugInvalid", List.of(), ProblemCode.SLUG_RESERVED,
                    String.valueOf(slug));
        }
        if (ReservedSlugs.isReserved(trimmed) || LocaleSegment.isLocale(trimmed)) {
            return RuleViolation.invalid(SLUG, "pageEdit.error.slugReserved", List.of(), ProblemCode.SLUG_RESERVED,
                    String.valueOf(slug));
        }
        if (trimmed.indexOf('/') >= 0) {
            return RuleViolation.invalid(SLUG, "pageEdit.error.slugInvalid", List.of(), ProblemCode.SLUG_HAS_SLASH,
                    slug);
        }
        if (trimmed.length() > ColumnWidths.PAGE_SLUG) {
            return RuleViolation.invalid(SLUG, "pageEdit.error.slugTooLong", List.of(), ProblemCode.FIELD_TOO_LONG,
                    SLUG, Integer.toString(ColumnWidths.PAGE_SLUG));
        }
        return null;
    }

    /**
     * A conflict when another page of {@code weblog} (not the one with
     * {@code excludingId}, null for none) holds {@code slug}: a trashed page
     * keeps its address until it is restored or deleted for good, and says so.
     * A lookup that fails throws: a caller that reads null as "free" must
     * not be handed one.
     */
    public static RuleViolation slugHolderProblem(Weblog weblog, String slug, String excludingId,
            WeblogPageManager pages) throws WebloggerException {
        WeblogPage holder = pages.getPageBySlug(weblog, slug);
        if (holder == null || holder.getId().equals(excludingId)) {
            return null;
        }
        if (holder.getStatus() == WeblogPage.PubStatus.TRASHED) {
            return RuleViolation.conflict(SLUG, "pageEdit.error.slugInTrash", List.of(),
                    ProblemCode.SLUG_HELD_BY_TRASH, slug);
        }
        return RuleViolation.conflict(SLUG, "pageEdit.error.slugTaken", List.of(), ProblemCode.SLUG_IN_USE, slug);
    }

    /**
     * {@link #slugShapeProblem} then {@link #slugHolderProblem}, for a caller
     * with no way to let a failure through (the screens): a uniqueness check
     * that could not run refuses, with {@code generic.error.check.logs},
     * rather than reading as "free".
     */
    public static RuleViolation slugProblem(Weblog weblog, String slug, String excludingId,
            WeblogPageManager pages) {
        RuleViolation shape = slugShapeProblem(slug);
        if (shape != null) {
            return shape;
        }
        try {
            return slugHolderProblem(weblog, slug.trim(), excludingId, pages);
        } catch (WebloggerException e) {
            log.error("Error checking slug {} against the pages of weblog {}", slug, weblog.getHandle(), e);
            return RuleViolation.invalid(SLUG, "generic.error.check.logs", List.of(),
                    ProblemCode.SERVER_INTERNAL_ERROR);
        }
    }

    /**
     * The page editor's two strand rules: a site page is never a strand, and
     * a strand that still has parts (any status, the trash included) cannot
     * be unset. A count that cannot be read throws rather than passing.
     *
     * @param stored the stored page, or null for a page not yet created (which has no parts)
     * @param wanted the switch as requested; null leaves it alone
     */
    public static RuleViolation strandProblem(Weblog weblog, WeblogPage stored, Boolean wanted,
            WeblogEntryManager entries) throws WebloggerException {
        if (Boolean.TRUE.equals(wanted) && weblog.isSiteSpace()) {
            return RuleViolation.invalid(STRAND, "pageEdit.error.strandSitePage", List.of(),
                    ProblemCode.STRAND_SITE_PAGE);
        }
        if (Boolean.FALSE.equals(wanted) && stored != null && Boolean.TRUE.equals(stored.getStrand())) {
            long parts = entries.getStrandPartCount(stored);
            if (parts > 0) {
                return RuleViolation.invalid(STRAND, "pageEdit.error.strandHasParts", List.of(parts),
                        ProblemCode.STRAND_HAS_PARTS, Long.toString(parts), "{id}");
            }
        }
        return null;
    }
}
