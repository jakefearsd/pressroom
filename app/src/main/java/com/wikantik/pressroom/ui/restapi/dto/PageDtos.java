package com.wikantik.pressroom.ui.restapi.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import com.wikantik.pressroom.pojos.ReservedSlugs;
import com.wikantik.pressroom.ui.rendering.util.LocaleSegment;
import com.wikantik.pressroom.pojos.WeblogPage;
import com.wikantik.pressroom.ui.restapi.ApiException;
import com.wikantik.pressroom.ui.restapi.ColumnLimits;
import com.wikantik.pressroom.ui.restapi.FieldErrors;

/**
 * Views of a weblog static page for the automation API.
 *
 * <p>A page title is stored RAW -- the opposite of an entry title, which
 * {@code EntryDtos.applyWrite} stores HTML-escaped via {@code
 * EntryFieldRules.escapeTitle}. {@code WeblogPage.getTitle()} returns raw
 * author input, and every page-rendering template calls
 * {@code $utils.escapeHTML(...)} on it at render time (see CLAUDE.md's
 * Themes section). Escaping it again here, on the way in, would double-encode
 * every page title in the system -- do not copy {@code EntryFieldRules
 * .escapeTitle} over to this file.
 */
public final class PageDtos {

    /** The fields {@code clear} may name on a page. */
    public static final List<String> PAGE_CLEARABLE = List.of("metaTitle", "searchDescription", "canonicalUrl",
            "ogImageId");

    private PageDtos() {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record PageView(String id, String slug, String title, String text,
                            String status, boolean showInNav, int navOrder,
                            String metaTitle, String searchDescription, String canonicalUrl,
                            boolean noindex, String ogImageId, Instant updateTime) {
    }

    /**
     * A create or partial update. Every component is nullable and null means
     * ABSENT, not "clear this" -- same "null means absent" PATCH convention
     * {@code EntryDtos.EntryWrite} uses; removing a value is {@code clear}.
     * The SEO card's fields follow the entry's rules ({@link SeoFields}).
     */
    public record PageWrite(
            String slug, String title, String text,
            @Schema(description = "DRAFT or PUBLISHED; a new page without one is a DRAFT.")
            String status,
            @Schema(description = "List the page in the blog's navigation.")
            Boolean showInNav,
            @Schema(description = "Position in the navigation, lowest first; pages with the same number "
                    + "sort by title.")
            Integer navOrder,
            @Schema(description = "Replaces the whole <title> and og:title; at most 255 characters.")
            String metaTitle,
            @Schema(description = "The meta description search engines and share previews show; at most "
                    + "255 characters.")
            String searchDescription,
            @Schema(description = "An absolute http(s) URL this page canonicalises to, when it was first "
                    + "published elsewhere.")
            String canonicalUrl,
            @Schema(description = "True asks search engines not to index the page. Not clearable.")
            Boolean noindex,
            @Schema(description = "Id of an image in this weblog's media, outside any private directory, "
                    + "used as the share image.")
            String ogImageId,
            @Schema(description = "On update, fields to remove: metaTitle, searchDescription, canonicalUrl, "
                    + "ogImageId. Null never removes anything.")
            List<String> clear) {
    }

    public static PageView toView(WeblogPage page) {
        return new PageView(
                page.getId(),
                page.getSlug(),
                page.getTitle(),
                page.getContent(),
                page.getStatus() == null ? null : page.getStatus().name(),
                page.getShowInNav() != null && page.getShowInNav(),
                page.getNavOrder(),
                page.getMetaTitle(),
                page.getSearchDescription(),
                page.getCanonicalUrl(),
                Boolean.TRUE.equals(page.getNoindex()),
                page.getOgImageId(),
                instant(page.getUpdated()));
    }

    private static Instant instant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }

    /**
     * Applies every field {@code write} actually carries onto {@code page},
     * then removes the {@code cleared} ones (already validated by {@code
     * ClearFields}). Every field's problem is collected; the one 400 thrown at
     * the end names them all, as an entry write's does. The title is copied
     * through with NO escaping -- see this class's header comment for why;
     * {@code PageBean.copyTo} (the JSP editor's own form-to-entity mapper)
     * does the exact same unescaped copy.
     *
     * <p>{@code slug} is copied through verbatim too, not trimmed or
     * validated here -- the controller calls {@link #requireUsableSlug}
     * itself, exactly once per write, since a PATCH that omits {@code slug}
     * must not re-validate the page's existing, already-valid one.
     */
    public static void applyWrite(WeblogPage page, PageWrite write, Set<String> cleared) {
        FieldErrors errors = new FieldErrors();
        if (write.slug() != null) {
            page.setSlug(write.slug());
        }
        if (write.title() != null) {
            errors.check("title", () -> {
                ColumnLimits.requireMaxLength("title", write.title(), ColumnLimits.PAGE_TITLE);
                page.setTitle(write.title());
            });
        }
        if (write.text() != null) {
            page.setContent(write.text());
        }
        if (write.status() != null) {
            errors.check("status", () -> page.setStatus(parseStatus(write.status())));
        }
        if (write.showInNav() != null) {
            page.setShowInNav(write.showInNav());
        }
        if (write.navOrder() != null) {
            page.setNavOrder(write.navOrder());
        }
        if (write.metaTitle() != null) {
            errors.check("metaTitle", () -> page.setMetaTitle(SeoFields.metaTitle(write.metaTitle())));
        }
        if (write.searchDescription() != null) {
            errors.check("searchDescription",
                    () -> page.setSearchDescription(SeoFields.searchDescription(write.searchDescription())));
        }
        if (write.canonicalUrl() != null) {
            errors.check("canonicalUrl", () -> page.setCanonicalUrl(SeoFields.canonicalUrl(write.canonicalUrl())));
        }
        if (write.noindex() != null) {
            page.setNoindex(write.noindex());
        }
        if (write.ogImageId() != null) {
            errors.check("ogImageId", () -> page.setOgImageId(SeoFields.ogImageId(write.ogImageId())));
        }
        for (String name : cleared) {
            clearField(page, name);
        }
        errors.throwIfAny();
    }

    private static void clearField(WeblogPage page, String name) {
        switch (name) {
            case "metaTitle" -> page.setMetaTitle(null);
            case "searchDescription" -> page.setSearchDescription(null);
            case "canonicalUrl" -> page.setCanonicalUrl(null);
            case "ogImageId" -> page.setOgImageId(null);
            default -> throw new IllegalArgumentException("Not clearable: " + name);
        }
    }

    static WeblogPage.PubStatus parseStatus(String raw) {
        try {
            return WeblogPage.PubStatus.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw ApiException.invalidField("status", "Unknown status '" + raw + "'.", e);
        }
    }

    /**
     * Refuses a slug {@code ReservedSlugs} would refuse to route -- blank,
     * or a name reserved for a context {@code WeblogPageRequest} parses --
     * before the controller ever calls {@code WeblogPageManager.savePage},
     * which enforces the same rule but only as a bare {@code
     * WebloggerException} the generic handler can only render as an opaque
     * 500. {@code ReservedSlugs.isReserved} already treats null/blank as
     * reserved ("nothing to route"), so this is a thin wrapper turning that
     * boolean into the 400 an API caller needs.
     *
     * <p>Delegates to {@code ReservedSlugs} rather than restating its list --
     * see CLAUDE.md's Pages section: two lists would drift, and a slug that
     * collided with a routed context would make the page unreachable at its
     * own URL.
     *
     * <p>Also mirrors {@code JPAWeblogPageManagerImpl.savePage}'s separate
     * refusal of a slug containing {@code '/'} -- a rule {@code
     * ReservedSlugs.isReserved} does not and should not express, since it
     * only tests membership in a fixed set of whole names, not shape. There
     * is no shared constant on the manager side for this one to delegate to
     * (the manager expresses it as an inline {@code indexOf('/') >= 0}), so
     * it is restated here; if a future change touches that check, this is
     * the second place to update.
     *
     * @return the trimmed, usable slug
     */
    public static String requireUsableSlug(String slug) {
        String trimmed = slug == null ? null : slug.trim();
        if (ReservedSlugs.isReserved(trimmed) || LocaleSegment.isLocale(trimmed)) {
            throw ApiException.invalidField("slug",
                    "slug is blank or reserved: '" + slug + "'.");
        }
        if (trimmed.indexOf('/') >= 0) {
            throw ApiException.invalidField("slug",
                    "slug may not contain '/': '" + slug + "'.");
        }
        ColumnLimits.requireMaxLength("slug", trimmed, ColumnLimits.PAGE_SLUG);
        return trimmed;
    }
}
