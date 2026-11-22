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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.validator.routines.EmailValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.business.BookingLink;
import com.wikantik.pressroom.business.BusinessManager;
import com.wikantik.pressroom.pojos.Business;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.ui.restapi.ProblemCode;
import com.wikantik.pressroom.util.cache.CacheManager;

/**
 * The field rules for a business record and a blog's place fields, shared by
 * the Businesses admin screen, {@code /v1/admin/businesses} and the blog
 * settings form so all of them refuse the same inputs. The record's rules
 * ({@link #problems}, {@link #apply}, {@link #invalidateUsers}) moved here
 * from the Businesses screen's controller (spec
 * 2026-11-20-api-gap-resources-design, Task 5). Everything here ends up in schema.org JSON-LD or an anchor on a
 * public page, so a value that fails is refused rather than cleaned up.
 */
public final class BusinessRules {

    static final Pattern TELEPHONE = Pattern.compile(
            "^(?=.{3,32}$)[0-9+()\\-. ]{3,}(?: *(?:extension|ext\\.?|x) *\\d{1,6})?$",
            Pattern.CASE_INSENSITIVE);

    /** Most sameAs profile links a business may carry. */
    public static final int MAX_SAME_AS = 10;

    /** The longest a business's name, links, email or area served may be: their columns' width. */
    public static final int MAX_LENGTH = 255;

    private static final String KNOWN_TYPES = Arrays.stream(Business.BusinessType.values())
            .map(Enum::name).collect(Collectors.joining(", "));

    private static final Logger log = LoggerFactory.getLogger(BusinessRules.class);

    private BusinessRules() {
    }

    /**
     * True for an absolute http or https URL. Delegates to
     * {@link BookingLink#isHttpUrl}, so the forms accept exactly the links
     * the card and the [cta] shortcode will use.
     */
    public static boolean isHttpUrl(String s) {
        return BookingLink.isHttpUrl(s);
    }

    public static boolean isEmail(String s) {
        return s != null && EmailValidator.getInstance().isValid(s);
    }

    /**
     * Digits, plus, parentheses, hyphen, dot and space, then optionally an
     * extension ("ext", "ext.", "extension" or "x" and 1 to 6 digits); 3 to
     * 32 characters in all, the column's width.
     */
    public static boolean isTelephone(String s) {
        return s != null && TELEPHONE.matcher(s).matches();
    }

    /**
     * A business record's values as a form posts them, every one a raw string: the type by name,
     * so a forged one reaches {@link #problems} rather than failing to bind, and {@code sameAs}
     * newline-separated, as the form's text area sends it. The components are the API's field
     * names; the screen's control for each is {@code bean_} and the name.
     */
    public record Values(String name, String businessType, String websiteUrl, String bookingUrl, String telephone,
            String email, String logoUrl, String sameAs, String areaServed, String description) {

        /** What {@code business} holds, as the edit form shows it. */
        public static Values of(Business business) {
            return new Values(business.getName(),
                    business.getBusinessType() == null ? null : business.getBusinessType().name(),
                    business.getWebsiteUrl(), business.getBookingUrl(), business.getTelephone(), business.getEmail(),
                    business.getLogoUrl(), business.getSameAs(), business.getAreaServed(),
                    business.getDescription());
        }
    }

    /**
     * Every rule {@code values} breaks, in the form's order, all of them 400s: a name (missing is
     * {@code field.required}, blank {@code field.blank}) of at most {@link #MAX_LENGTH}; a known
     * type; the website, booking and logo links public http(s) URLs of at most {@link #MAX_LENGTH};
     * the telephone on its pattern; a valid email of at most {@link #MAX_LENGTH}; the area served
     * at most {@link #MAX_LENGTH}; at most {@link #MAX_SAME_AS} profile links, each a public http(s)
     * URL of at most {@link #MAX_LENGTH}. Values are judged trimmed, as {@link #apply} stores them.
     * Empty when the record passes.
     */
    public static List<RuleViolation> problems(Values values) {
        List<RuleViolation> found = new ArrayList<>();
        String name = StringUtils.trimToNull(values.name());
        if (name == null) {
            found.add(RuleViolation.invalid("name", "businesses.error.nameRequired", List.of(),
                    values.name() == null ? ProblemCode.FIELD_REQUIRED : ProblemCode.FIELD_BLANK, "name"));
        } else {
            tooLong("name", "businesses.name", name, found);
        }
        if (!isKnownType(values.businessType())) {
            found.add(RuleViolation.invalid("businessType", "businesses.error.typeInvalid", List.of(),
                    ProblemCode.BUSINESS_TYPE_UNKNOWN, KNOWN_TYPES));
        }
        url("websiteUrl", "businesses.websiteUrl", values.websiteUrl(), found);
        url("bookingUrl", "businesses.bookingUrl", values.bookingUrl(), found);
        url("logoUrl", "businesses.logoUrl", values.logoUrl(), found);
        String phone = StringUtils.trimToNull(values.telephone());
        if (phone != null && !isTelephone(phone)) {
            found.add(RuleViolation.invalid("telephone", "businesses.error.telephoneInvalid", List.of(),
                    ProblemCode.TELEPHONE_INVALID));
        }
        String email = StringUtils.trimToNull(values.email());
        if (email != null) {
            if (isEmail(email)) {
                tooLong("email", "businesses.email", email, found);
            } else {
                found.add(RuleViolation.invalid("email", "businesses.error.emailInvalid", List.of(),
                        ProblemCode.EMAIL_INVALID));
            }
        }
        tooLong("areaServed", "businesses.areaServed", StringUtils.trimToNull(values.areaServed()), found);
        sameAs(values.sameAs(), found);
        return found;
    }

    /**
     * Stores {@code values} on {@code business}, each trimmed, a blank one as null, the profile
     * links one per line. Only for values {@link #problems} passed: the name and type must be set.
     */
    public static void apply(Values values, Business business) {
        business.setName(values.name().trim());
        business.setBusinessType(Business.BusinessType.valueOf(values.businessType()));
        business.setWebsiteUrl(StringUtils.trimToNull(values.websiteUrl()));
        business.setBookingUrl(StringUtils.trimToNull(values.bookingUrl()));
        business.setTelephone(StringUtils.trimToNull(values.telephone()));
        business.setEmail(StringUtils.trimToNull(values.email()));
        business.setLogoUrl(StringUtils.trimToNull(values.logoUrl()));
        List<String> links = sameAsLines(values.sameAs());
        business.setSameAs(links.isEmpty() ? null : String.join("\n", links));
        business.setAreaServed(StringUtils.trimToNull(values.areaServed()));
        business.setDescription(StringUtils.trimToNull(values.description()));
    }

    /**
     * Drops the eager site-wide cache for every blog using {@code business}, after a save. The
     * page and feed caches expire through weblog.lastModified, which {@code saveBusiness}
     * touches; SiteWideCache ignores it. The save has already happened, so a failure here is
     * logged, not reported: the cache still expires on its own timeout.
     */
    public static void invalidateUsers(Business business, BusinessManager businesses) {
        try {
            for (Weblog weblog : businesses.getWeblogsUsing(business)) {
                CacheManager.invalidate(weblog);
            }
        } catch (WebloggerException | RuntimeException ex) {
            // The save has committed; whatever failed here must not turn it into a failure.
            log.error("Saved business {} but could not invalidate the blogs using it", business.getId(), ex);
        }
    }

    private static void url(String field, String labelKey, String raw, List<RuleViolation> found) {
        String value = StringUtils.trimToNull(raw);
        if (value == null) {
            return;
        }
        if (isHttpUrl(value)) {
            tooLong(field, labelKey, value, found);
        } else {
            found.add(RuleViolation.invalid(field, "businesses.error.urlInvalid",
                    List.of(new RuleViolation.Label(labelKey)), ProblemCode.FIELD_NOT_PUBLIC_URL, field));
        }
    }

    private static void tooLong(String field, String labelKey, String value, List<RuleViolation> found) {
        if (value != null && value.length() > MAX_LENGTH) {
            found.add(RuleViolation.invalid(field, "businesses.error.tooLong",
                    List.of(new RuleViolation.Label(labelKey), MAX_LENGTH), ProblemCode.FIELD_TOO_LONG, field,
                    Integer.toString(MAX_LENGTH)));
        }
    }

    private static void sameAs(String raw, List<RuleViolation> found) {
        List<String> links = sameAsLines(raw);
        if (links.size() > MAX_SAME_AS) {
            found.add(RuleViolation.invalid("sameAs", "businesses.error.sameAsTooMany", List.of(MAX_SAME_AS),
                    ProblemCode.SAME_AS_TOO_MANY, Integer.toString(MAX_SAME_AS)));
        }
        for (String link : links) {
            if (!isHttpUrl(link) || link.length() > MAX_LENGTH) {
                found.add(RuleViolation.invalid("sameAs", "businesses.error.sameAsInvalid", List.of(link),
                        ProblemCode.SAME_AS_ENTRY_INVALID, link));
            }
        }
    }

    private static boolean isKnownType(String type) {
        for (Business.BusinessType known : Business.BusinessType.values()) {
            if (known.name().equals(type)) {
                return true;
            }
        }
        return false;
    }

    /** The non-blank lines of a newline-separated list, each trimmed. */
    public static List<String> sameAsLines(String raw) {
        List<String> lines = new ArrayList<>();
        if (raw == null) {
            return lines;
        }
        for (String line : raw.split("\\R")) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty()) {
                lines.add(trimmed);
            }
        }
        return lines;
    }
}
