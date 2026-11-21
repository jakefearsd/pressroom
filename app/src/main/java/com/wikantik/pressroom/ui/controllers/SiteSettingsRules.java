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
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.ResourceBundle;
import java.util.function.Function;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.business.PageReference;
import com.wikantik.pressroom.business.Weblogger;
import com.wikantik.pressroom.business.discovery.DiscoverySettings;
import com.wikantik.pressroom.config.runtime.PropertyDef;
import com.wikantik.pressroom.config.runtime.PropertyType;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.ui.restapi.ProblemCode;

/**
 * The rules a save of runtime properties (site settings) must pass, for the
 * Global Config screen and {@code PATCH /api/v1/admin/config} alike (spec
 * 2026-11-20-jsp-rules-and-view-models-design, Task 1). It judges incoming
 * values against the stored ones and the property definitions and returns
 * every {@link RuleViolation}; it touches no request, model or response, and
 * stores nothing. {@link SiteSettingsEffects} does what a save must do after.
 *
 * <ul>
 *   <li>Each value against its declared type ({@link PropertyType#check}): a
 *       number must parse, a boolean is {@code true} or {@code false}, and a
 *       discovery setting lies in its range. String values are stored
 *       trimmed ({@link #normalise}).</li>
 *   <li>The front door: the site space is never the front-door blog (any
 *       case); a <em>changed</em> front-door page must be a published site
 *       page; and the two front-door settings are never both non-empty.</li>
 *   <li>A <em>changed</em> privacy page must be a {@link PageReference} to a
 *       published page.</li>
 * </ul>
 * Only a changed reference is looked up, so a page unpublished after it was
 * chosen never blocks an unrelated save. Every lookup is a decision path: one
 * that throws refuses, it never passes.
 */
public final class SiteSettingsRules {

    private static final Logger log = LoggerFactory.getLogger(SiteSettingsRules.class);

    /** The front door's blog: a handle, or empty for the site directory (or a site page). */
    public static final String FRONT_DOOR_BLOG = "site.frontpage.weblog.handle";
    /** The front door's site page: a slug; non-empty, it wins over the blog. */
    public static final String FRONT_DOOR_PAGE = "site.frontpage.page";
    /** The privacy page: {@code slug} on the site, or {@code handle/slug} on a blog. */
    public static final String PRIVACY_PAGE = "site.privacy.page";

    /** The property labels the screen's messages name, read as the screen always read them. */
    private static final ResourceBundle LABELS = ResourceBundle.getBundle("ApplicationResources");

    private SiteSettingsRules() {
    }

    /** Whether a page exists and is published; throws when that cannot be found out. */
    @FunctionalInterface
    public interface PublishedPages {
        boolean isPublished(PageReference reference) throws WebloggerException;
    }

    /** {@link PublishedPages} answered by the business tier. */
    public static PublishedPages publishedPages(Weblogger weblogger) {
        return reference -> reference.resolvePublished(weblogger).isPresent();
    }

    /**
     * What a set of incoming values comes to.
     *
     * @param values     each value as it would be stored (normalised), in the incoming order
     * @param violations every rule broken: the values' own, in the incoming order, then the references'
     */
    public record Judgement(Map<String, String> values, List<RuleViolation> violations) {
        public Judgement {
            values = Collections.unmodifiableMap(new LinkedHashMap<>(values));
            violations = List.copyOf(violations);
        }
    }

    /**
     * Judges {@code incoming} (name to non-null value; every name defined) against
     * {@code stored} (name to stored value; a name with no row is absent or null).
     */
    public static Judgement judge(Map<String, String> incoming, Function<String, PropertyDef> definitions,
            Map<String, String> stored, PublishedPages pages) {
        Map<String, String> values = new LinkedHashMap<>();
        List<RuleViolation> violations = new ArrayList<>();
        for (Map.Entry<String, String> entry : incoming.entrySet()) {
            PropertyDef def = definitions.apply(entry.getKey());
            String value = normalise(def, entry.getValue());
            values.put(entry.getKey(), value);
            checkValue(def, value).ifPresent(violations::add);
        }
        violations.addAll(checkReferences(values, stored, pages));
        return new Judgement(values, violations);
    }

    /** The value as it is stored: a string setting trimmed, any other as sent. */
    public static String normalise(PropertyDef def, String value) {
        return PropertyType.of(def.getType()) == PropertyType.STRING ? value.trim() : value;
    }

    /** What is wrong with {@code value} (non-null) as {@code def}'s type, or empty. */
    public static Optional<RuleViolation> checkValue(PropertyDef def, String value) {
        String name = def.getName();
        PropertyType type = PropertyType.of(def.getType());
        return type.check(name, value).map(problem -> switch (problem) {
            case PropertyType.Range range -> RuleViolation.invalid(name, DiscoverySettings.RANGE_ERROR_KEY,
                    List.of(label(def), Integer.toString(range.min()), Integer.toString(range.max())),
                    ProblemCode.CONFIG_OUT_OF_RANGE, name, Integer.toString(range.min()),
                    Integer.toString(range.max()));
            case PropertyType.Unparsed _ -> switch (type) {
                case BOOLEAN -> RuleViolation.invalid(name, "ConfigForm.invalidBooleanProperty",
                        List.of(label(def), value), ProblemCode.CONFIG_NOT_BOOLEAN, name);
                case INTEGER -> RuleViolation.invalid(name, "ConfigForm.invalidIntegerProperty",
                        List.of(label(def), value), ProblemCode.CONFIG_NOT_INTEGER, name);
                default -> RuleViolation.invalid(name, "ConfigForm.invalidFloatProperty",
                        List.of(label(def), value), ProblemCode.CONFIG_NOT_NUMBER, name);
            };
        });
    }

    /**
     * The front-door and privacy rules over {@code values} (normalised) and
     * {@code stored}: the site space as the front-door blog, then a changed
     * front-door page, then both front-door settings non-empty, then a
     * changed privacy page.
     */
    public static List<RuleViolation> checkReferences(Map<String, String> values, Map<String, String> stored,
            PublishedPages pages) {
        List<RuleViolation> violations = new ArrayList<>();
        String blog = values.get(FRONT_DOOR_BLOG);
        if (blog != null && isSiteSpace(blog)) {
            violations.add(RuleViolation.invalid(FRONT_DOOR_BLOG, "ConfigForm.invalidProperty",
                    List.of(FRONT_DOOR_BLOG), ProblemCode.CONFIG_FRONT_DOOR_SITE_SPACE));
        }
        String page = values.get(FRONT_DOOR_PAGE);
        if (page != null && !page.isEmpty() && changed(FRONT_DOOR_PAGE, values, stored)
                && !isPublished(new PageReference(null, page), pages, "the front door's site page")) {
            violations.add(RuleViolation.invalid(FRONT_DOOR_PAGE, "configForm.error.frontpagePage.notFound",
                    List.of(), ProblemCode.CONFIG_FRONT_DOOR_PAGE_UNPUBLISHED));
        } else if ((blog != null || page != null) && !after(FRONT_DOOR_BLOG, values, stored).isEmpty()
                && !after(FRONT_DOOR_PAGE, values, stored).isEmpty()) {
            violations.add(RuleViolation.invalid(FRONT_DOOR_PAGE, "configForm.error.frontpage.bothSet",
                    List.of(), ProblemCode.CONFIG_FRONT_DOOR_BOTH_SET));
        }
        String privacy = values.get(PRIVACY_PAGE);
        if (privacy != null && !privacy.isEmpty() && changed(PRIVACY_PAGE, values, stored)
                && PageReference.parse(privacy)
                        .filter(reference -> isPublished(reference, pages, "the privacy page")).isEmpty()) {
            violations.add(RuleViolation.invalid(PRIVACY_PAGE, "configForm.error.privacyPage.notFound",
                    List.of(), ProblemCode.CONFIG_PRIVACY_PAGE_UNPUBLISHED));
        }
        return violations;
    }

    /** True when {@code values} carries {@code name} and it differs from the stored value (none reads as ""). */
    public static boolean changed(String name, Map<String, String> values, Map<String, String> stored) {
        return values.containsKey(name) && !Objects.toString(stored.get(name), "").equals(values.get(name));
    }

    /** The value {@code name} would hold after the save: the incoming one, else the stored one, else "". */
    private static String after(String name, Map<String, String> values, Map<String, String> stored) {
        return Objects.toString(values.containsKey(name) ? values.get(name) : stored.get(name), "");
    }

    @SuppressFBWarnings(
            value = "IMPROPER_UNICODE",
            justification = "Refuses the site space's handle in any case. Handles are ASCII "
                    + "alphanumerics, so a miss could only fail to refuse here; the lookups "
                    + "behind it refuse the site space anyway.")
    private static boolean isSiteSpace(String handle) {
        return Weblog.SITE_SPACE_HANDLE.equalsIgnoreCase(handle);
    }

    /** A decision path: a lookup that could not run is not a page that exists. */
    private static boolean isPublished(PageReference reference, PublishedPages pages, String what) {
        try {
            return pages.isPublished(reference);
        } catch (WebloggerException ex) {
            log.error("Error checking {}", what, ex);
            return false;
        }
    }

    /** The property's label, as the screen's messages name it; its name when the bundle has none. */
    private static String label(PropertyDef def) {
        String key = def.getKey();
        return key != null && LABELS.containsKey(key) ? LABELS.getString(key) : def.getName();
    }
}
