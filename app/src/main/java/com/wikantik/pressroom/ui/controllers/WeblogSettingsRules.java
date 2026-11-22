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

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import org.apache.commons.lang3.StringUtils;
import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.business.BusinessManager;
import com.wikantik.pressroom.business.WeblogManager;
import com.wikantik.pressroom.business.WeblogPageManager;
import com.wikantik.pressroom.business.Weblogger;
import com.wikantik.pressroom.config.WebloggerRuntimeConfig;
import com.wikantik.pressroom.pojos.EntryOrder;
import com.wikantik.pressroom.pojos.PageWidth;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.pojos.WeblogPage;
import com.wikantik.pressroom.ui.controllers.editor.WeblogConfigBean;
import com.wikantik.pressroom.ui.controllers.util.UIUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The rules a weblog's settings must satisfy: one home for the settings form
 * ({@code WeblogConfigController}) and {@code PATCH /api/v1/weblogs/{handle}},
 * so the two cannot drift. Each rule judges the settings as they will be
 * after the change. A {@link Violation} names every field its rule involves:
 * the form marks the first, and the API reports a violation only when its
 * request touched one of them, so a value stored before a rule existed
 * cannot block an unrelated change.
 */
public final class WeblogSettingsRules {

    private static final Logger log = LoggerFactory.getLogger(WeblogSettingsRules.class);

    /** icon, about and booking_url are varchar(255). */
    public static final int MAX_TEXT = 255;
    /** place_locality and place_region are varchar(128). */
    public static final int MAX_PLACE_TEXT = 128;

    private static final Pattern UUID = Pattern.compile(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");
    private static final Pattern COUNTRY = Pattern.compile("^[A-Za-z]{2}$");
    private static final BigDecimal MAX_LAT = new BigDecimal(90);
    private static final BigDecimal MAX_LNG = new BigDecimal(180);
    private static final String LODGING_BUSINESS = "LodgingBusiness";

    /**
     * One broken rule. {@code fields} are bean property names, the first the
     * one the form marks; {@code labelKey} and {@code max} are set only for a
     * too-long value, whose message takes the field's label and the limit.
     * {@code detail} is the API's English, and {@code reason} with {@code
     * args} what the API keys it by (spec 2026-11-20-api-contract-design,
     * Task 3): each reason is one meaning, its args the values its text
     * names, as they read in {@code detail}.
     */
    public record Violation(List<String> fields, String messageKey, String labelKey, Integer max,
                            String detail, boolean conflict, Reason reason, List<String> args) {
        public Violation {
            fields = List.copyOf(fields);
            args = List.copyOf(args);
        }

        public String field() {
            return fields.get(0);
        }

        public boolean involvesAny(Set<String> names) {
            return fields.stream().anyMatch(names::contains);
        }
    }

    /** What a {@link Violation} means, for the API's problem code. */
    public enum Reason {
        ENTRY_DISPLAY_COUNT, LOCALE_INVALID, TIME_ZONE_INVALID, ENTRY_ORDER, PAGE_WIDTH, TOO_LONG, NOT_UUID,
        BUSINESS_UNKNOWN, FRONT_PAGE, PLACE_TYPE, PLACE_LOCALITY_REQUIRED, PLACE_COUNTRY, PLACE_LAT,
        PLACE_LNG, BOOKING_URL, CUSTOM_DOMAIN_MALFORMED, CUSTOM_DOMAIN_SITE_HOST, CUSTOM_DOMAIN_TAKEN,
        CUSTOM_DOMAIN_UNCHECKABLE
    }

    /** What the rules need beyond the bean: the weblog itself, the site, and three lookups. */
    public record Context(Weblog weblog, int maxEntries, String siteAbsoluteUrl,
                          BusinessManager businesses, WeblogManager weblogs, WeblogPageManager pages) {
        public static Context current(Weblog weblog, Weblogger weblogger) {
            return new Context(weblog,
                    WebloggerRuntimeConfig.getIntProperty("site.pages.maxEntries"),
                    WebloggerRuntimeConfig.getPropertyWithConfigFallback("site.absoluteurl"),
                    weblogger.getBusinessManager(), weblogger.getWeblogManager(),
                    weblogger.getWeblogPageManager());
        }

        public String ownHandle() {
            return weblog.getHandle();
        }
    }

    private WeblogSettingsRules() {
    }

    public static List<Violation> check(WeblogConfigBean bean, Context context) {
        List<Violation> found = new ArrayList<>();
        int count = bean.getEntryDisplayCount();
        if (count < 1 || (context.maxEntries() > 0 && count > context.maxEntries())) {
            found.add(simple("entryDisplayCount", "websiteSettings.error.entryDisplayCount",
                    "entryDisplayCount must be between 1 and " + context.maxEntries() + ".",
                    Reason.ENTRY_DISPLAY_COUNT, Integer.toString(context.maxEntries())));
        }
        String locale = StringUtils.trimToNull(bean.getLocale());
        if (locale != null && UIUtils.getLocales().stream().map(Locale::toString).noneMatch(locale::equals)) {
            found.add(simple("locale", "websiteSettings.locale.invalid",
                    "locale must be one the settings form offers, for example en_US.", Reason.LOCALE_INVALID));
        }
        String zone = StringUtils.trimToNull(bean.getTimeZone());
        if (zone != null && !UIUtils.getTimeZones().contains(zone)) {
            found.add(simple("timeZone", "websiteSettings.timeZone.invalid",
                    "timeZone must be a time zone id, for example Europe/Berlin.", Reason.TIME_ZONE_INVALID));
        }
        if (EntryOrder.parse(bean.getEntryOrder()).isEmpty()) {
            found.add(simple("entryOrder", "weblogSettings.error.entryOrder",
                    "entryOrder must be NEWEST or OLDEST.", Reason.ENTRY_ORDER));
        }
        if (PageWidth.parse(bean.getPageWidth()).isEmpty()) {
            found.add(simple("pageWidth", "weblogSettings.error.pageWidth",
                    "pageWidth must be STANDARD or WIDE.", Reason.PAGE_WIDTH));
        }
        tooLong("copyright", "websiteSettings.copyright", bean.getCopyright(), MAX_TEXT, found);
        tooLong("icon", "websiteSettings.icon", bean.getIcon(), MAX_TEXT, found);
        tooLong("about", "websiteSettings.about", bean.getAbout(), MAX_TEXT, found);
        mustBeUuid("newsletterListUuid", "websiteSettings.newsletterListUuid.invalid", bean.getNewsletterListUuid(), found);
        checkBusiness(bean, context, found);
        checkFrontDoor(bean, context, found);
        checkPlace(bean, found);
        checkCustomDomain(bean, context, found);
        return found;
    }

    private static void checkBusiness(WeblogConfigBean bean, Context context, List<Violation> found) {
        String businessId = StringUtils.trimToNull(bean.getBusinessId());
        if (businessId == null) {
            return;
        }
        boolean known;
        try {
            known = context.businesses().getBusiness(businessId) != null;
        } catch (WebloggerException e) {
            // A lookup that could not run is not one that passed.
            log.error("Error checking business", e);
            known = false;
        }
        if (!known) {
            found.add(simple("businessId", "websiteSettings.business.invalid", "No such business.",
                    Reason.BUSINESS_UNKNOWN));
        }
    }

    /**
     * The front page must be a published page of this very blog. Another
     * blog's page and an unknown id read the same: the API never reveals what
     * the caller may not see. The site space has no front-door setting.
     */
    private static void checkFrontDoor(WeblogConfigBean bean, Context context, List<Violation> found) {
        String pageId = StringUtils.trimToNull(bean.getFrontPageId());
        if (pageId == null) {
            return;
        }
        boolean usable = false;
        if (!context.weblog().isSiteSpace()) {
            try {
                WeblogPage page = context.pages().getPage(pageId);
                usable = page != null && page.getStatus() == WeblogPage.PubStatus.PUBLISHED
                        && page.getWeblog() != null
                        && Objects.equals(page.getWeblog().getId(), context.weblog().getId());
            } catch (WebloggerException e) {
                // A lookup that could not run is not one that passed.
                log.error("Error checking front page", e);
            }
        }
        if (!usable) {
            found.add(simple("frontPageId", "weblogSettings.error.frontDoor",
                    "frontPage must be the id of a published page of this blog.", Reason.FRONT_PAGE));
        }
    }

    private static void checkPlace(WeblogConfigBean bean, List<Violation> found) {
        String placeType = StringUtils.trimToNull(bean.getPlaceType());
        if (placeType != null && !LODGING_BUSINESS.equals(placeType)) {
            found.add(simple("placeType", "websiteSettings.placeType.invalid",
                    "placeType must be LodgingBusiness.", Reason.PLACE_TYPE));
        } else if (placeType != null && StringUtils.isBlank(bean.getPlaceLocality())) {
            found.add(new Violation(List.of("placeLocality", "placeType"), "websiteSettings.placeLocality.required",
                    null, null, "A place needs a placeLocality (a town or city).", false,
                    Reason.PLACE_LOCALITY_REQUIRED, List.of()));
        }
        String country = StringUtils.trimToNull(bean.getPlaceCountry());
        if (country != null && !COUNTRY.matcher(country).matches()) {
            found.add(simple("placeCountry", "websiteSettings.placeCountry.invalid",
                    "placeCountry must be a two-letter country code.", Reason.PLACE_COUNTRY));
        }
        String lat = StringUtils.trimToNull(bean.getPlaceLat());
        String lng = StringUtils.trimToNull(bean.getPlaceLng());
        if (!withinRange(lat, MAX_LAT) || (lat == null && lng != null)) {
            found.add(new Violation(List.of("placeLat", "placeLng"), "websiteSettings.placeLat.invalid", null, null,
                    "placeLat must be between -90 and 90, given together with placeLng.", false,
                    Reason.PLACE_LAT, List.of()));
        }
        if (!withinRange(lng, MAX_LNG) || (lng == null && lat != null)) {
            found.add(new Violation(List.of("placeLng", "placeLat"), "websiteSettings.placeLng.invalid", null, null,
                    "placeLng must be between -180 and 180, given together with placeLat.", false,
                    Reason.PLACE_LNG, List.of()));
        }
        tooLong("placeLocality", "websiteSettings.placeLocality", bean.getPlaceLocality(), MAX_PLACE_TEXT, found);
        tooLong("placeRegion", "websiteSettings.placeRegion", bean.getPlaceRegion(), MAX_PLACE_TEXT, found);
        String bookingUrl = StringUtils.trimToNull(bean.getBookingUrl());
        if (bookingUrl != null && !BusinessRules.isHttpUrl(bookingUrl)) {
            found.add(simple("bookingUrl", "websiteSettings.bookingUrl.invalid",
                    "bookingUrl must be an http or https URL.", Reason.BOOKING_URL));
        } else {
            tooLong("bookingUrl", "websiteSettings.bookingUrl", bookingUrl, MAX_TEXT, found);
        }
    }

    private static void checkCustomDomain(WeblogConfigBean bean, Context context, List<Violation> found) {
        String customDomain = CustomDomainRules.normalise(bean.getCustomDomain());
        bean.setCustomDomain(customDomain);
        if (customDomain == null) {
            return;
        }
        if (!CustomDomainRules.isWellFormed(customDomain)) {
            found.add(simple("customDomain", "websiteSettings.customDomain.invalid",
                    "customDomain must be a well-formed hostname.", Reason.CUSTOM_DOMAIN_MALFORMED));
        } else if (CustomDomainRules.isSiteHost(customDomain, context.siteAbsoluteUrl())) {
            // Resolving the site's own host to one weblog loops every request (see WeblogsApi's history).
            found.add(simple("customDomain", "websiteSettings.customDomain.isSiteHost",
                    "customDomain must not be the site's own hostname.", Reason.CUSTOM_DOMAIN_SITE_HOST));
        } else {
            try {
                Weblog claimant = context.weblogs().getWeblogByCustomDomain(customDomain);
                if (claimant != null && !claimant.getHandle().equals(context.ownHandle())) {
                    found.add(new Violation(List.of("customDomain"), "websiteSettings.customDomain.taken",
                            null, null, "customDomain is already in use by another weblog.", true,
                            Reason.CUSTOM_DOMAIN_TAKEN, List.of()));
                }
            } catch (WebloggerException e) {
                log.error("Error checking custom domain", e);
                found.add(simple("customDomain", "websiteSettings.customDomain.invalid",
                        "customDomain could not be checked; try again.", Reason.CUSTOM_DOMAIN_UNCHECKABLE));
            }
        }
    }

    private static void mustBeUuid(String field, String messageKey, String raw, List<Violation> found) {
        String value = StringUtils.trimToNull(raw);
        if (value != null && !UUID.matcher(value).matches()) {
            found.add(simple(field, messageKey, field + " must be a UUID.", Reason.NOT_UUID, field));
        }
    }

    private static void tooLong(String field, String labelKey, String raw, int max, List<Violation> found) {
        String value = StringUtils.trimToNull(raw);
        if (value != null && value.length() > max) {
            found.add(new Violation(List.of(field), "businesses.error.tooLong", labelKey, max,
                    "'" + field + "' must be " + max + " characters or fewer.", false,
                    Reason.TOO_LONG, List.of(field, Integer.toString(max))));
        }
    }

    /** True when blank, or a plain decimal whose magnitude is at most {@code limit}. */
    private static boolean withinRange(String text, BigDecimal limit) {
        if (text == null) {
            return true;
        }
        BigDecimal value = WeblogConfigBean.toDecimal(text);
        return value != null && value.abs().compareTo(limit) <= 0;
    }

    private static Violation simple(String field, String messageKey, String detail, Reason reason, String... args) {
        return new Violation(List.of(field), messageKey, null, null, detail, false, reason, List.of(args));
    }
}
