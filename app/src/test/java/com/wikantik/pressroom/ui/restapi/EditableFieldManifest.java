package com.wikantik.pressroom.ui.restapi;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import com.wikantik.pressroom.ui.restapi.dto.AdminDtos;
import com.wikantik.pressroom.ui.restapi.dto.BusinessDtos;
import com.wikantik.pressroom.ui.restapi.dto.EntryDtos;
import com.wikantik.pressroom.ui.restapi.dto.PageDtos;
import com.wikantik.pressroom.ui.restapi.dto.TemplateDtos;

/**
 * What each editing screen offers, in API vocabulary, and which API body
 * fields no screen offers (spec 2026-11-20 jsp-rules-and-view-models, Task 7).
 * It names no form bean, so it outlives the JSPs: whatever renders a form (the
 * JSPs today, a React screen later) is held to this list, and
 * {@link FormBodyParityTest} holds the API body to it both ways. A new editing
 * field goes into {@code fields} and into the body; a new body field is
 * either a screen field or an {@code apiOnly} entry with a reason.
 */
final class EditableFieldManifest {

    /** One resource: the screen's fields, the body that mirrors it, and the body fields no screen offers. */
    record Resource(String name, Class<? extends Record> body, Set<String> fields, Map<String, String> apiOnly) {
        @Override
        public String toString() {
            return name;
        }
    }

    private static final String CLEAR = "The API's way to remove an optional field; a form blanks the input instead.";
    private static final String CONCURRENCY = "Optimistic-concurrency guard for agents that read then write; a "
            + "form has the whole page in front of the editor.";
    private static final String NOTE = "A note on the saved revision; the editor does not ask for one.";

    private EditableFieldManifest() {
    }

    static List<Resource> resources() {
        return List.of(entry(), weblogSettings(), page(), business(), template());
    }

    static Resource entry() {
        return new Resource("entry", EntryDtos.EntryWrite.class, sorted(
                "title", "summary", "text", "status", "category", "pubTime", "tags", "metaTitle",
                "searchDescription", "canonicalUrl", "noindex", "featuredImageId", "ogImageId", "jsonLdType",
                "geoLatitude", "geoLongitude", "eventStart", "eventEnd", "eventLocation", "commentsClosed",
                "pinned", "strand"),
                new TreeMap<>(Map.of(
                        "anchor", "Where a section edit applies; an agent's way to address part of the text.",
                        "edits", "Anchored partial edits of the text; the editor replaces the whole text.",
                        "expectedContentHash", CONCURRENCY,
                        "revisionNote", NOTE,
                        "clear", CLEAR,
                        "featuredImageUrl", "Import an image from a URL as the featured image; the editor picks "
                                + "from the weblog's media.",
                        "featuredImageAlt", "Alt text for the imported featured image.",
                        "importExternalImages", "Fetch the text's external images into the media library; "
                                + "an agent convenience.")));
    }

    static Resource weblogSettings() {
        return new Resource("weblogSettings", AdminDtos.WeblogPatch.class, sorted(
                "name", "tagline", "copyright", "emailAddress", "locale", "timeZone", "entryDisplayCount", "active",
                "customDomain", "icon", "about", "newsletterListUuid", "businessId", "placeType", "placeLocality",
                "placeRegion", "placeCountry", "placeLat", "placeLng", "bookingUrl", "commentsEnabled",
                "commentNotify", "showMoreFromSite", "shareRowEnabled", "entryOrder", "pageWidth", "frontPage"),
                new TreeMap<>(Map.of(
                        "theme", "The Theme page is its own screen; the settings form does not carry it.",
                        "clear", CLEAR)));
    }

    static Resource page() {
        return new Resource("page", PageDtos.PageWrite.class, sorted(
                "slug", "title", "text", "status", "showInNav", "navOrder", "metaTitle", "searchDescription",
                "canonicalUrl", "noindex", "ogImageId", "strand"),
                new TreeMap<>(Map.of(
                        "clear", CLEAR,
                        "revisionNote", NOTE,
                        "expectedContentHash", CONCURRENCY)));
    }

    /** The Businesses screen's edit form (spec 2026-11-20-api-gap-resources-design, Task 5). */
    static Resource business() {
        return new Resource("business", BusinessDtos.BusinessWrite.class, sorted(
                "name", "businessType", "websiteUrl", "bookingUrl", "telephone", "email", "logoUrl", "sameAs",
                "areaServed", "description"),
                new TreeMap<>(Map.of("clear", CLEAR)));
    }

    /** The Template Edit form (session-only routes, SA-18; wave 3). */
    static Resource template() {
        return new Resource("template", TemplateDtos.TemplateWrite.class, sorted(
                "name", "description", "link", "navbar", "hidden", "contents", "contentType"),
                new TreeMap<>());
    }

    private static Set<String> sorted(String... names) {
        return new TreeSet<>(List.of(names));
    }
}
