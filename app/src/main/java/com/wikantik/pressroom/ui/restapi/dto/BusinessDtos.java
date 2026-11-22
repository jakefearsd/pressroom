package com.wikantik.pressroom.ui.restapi.dto;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import com.wikantik.pressroom.pojos.Business;
import com.wikantik.pressroom.ui.controllers.BusinessRules;
import com.wikantik.pressroom.ui.restapi.Paging;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Business profiles for the automation API (spec 2026-11-20-api-gap-resources-design, Task 5): the
 * shared records blogs point at with {@code businessId}, which every blog using one shows as
 * schema.org data. Values are judged and stored by {@link BusinessRules}, the Businesses screen's
 * rules, so the two surfaces store a value alike.
 */
public final class BusinessDtos {

    private static final String TYPES = "Organization, LocalBusiness or ProfessionalService.";

    /**
     * The fields {@code clear} may name: every field but {@code name} and {@code businessType},
     * which a business always has.
     */
    public static final List<String> CLEARABLE = List.of("websiteUrl", "bookingUrl", "telephone", "email",
            "logoUrl", "sameAs", "areaServed", "description");

    private BusinessDtos() {
    }

    /** One business profile. */
    public record BusinessView(
            @Schema(description = "The business's id; a weblog's businessId names it.",
                    requiredMode = Schema.RequiredMode.REQUIRED) String id,
            @Schema(description = "The business's name.", requiredMode = Schema.RequiredMode.REQUIRED) String name,
            @Schema(description = "The schema.org type: " + TYPES,
                    allowableValues = {"Organization", "LocalBusiness", "ProfessionalService"},
                    requiredMode = Schema.RequiredMode.REQUIRED) String businessType,
            @Schema(description = "The business's website, or null.") String websiteUrl,
            @Schema(description = "Where the business is booked, or null.") String bookingUrl,
            @Schema(description = "The business's telephone number, or null.") String telephone,
            @Schema(description = "The business's email address, or null.") String email,
            @Schema(description = "The URL of the business's logo, or null.") String logoUrl,
            @Schema(description = "Profile links that identify the business (social profiles, listings), in "
                    + "order; empty when there are none.", requiredMode = Schema.RequiredMode.REQUIRED)
            List<String> sameAs,
            @Schema(description = "The area the business serves, or null.") String areaServed,
            @Schema(description = "What the business does, or null.") String description,
            @Schema(description = "How many weblogs use this business.", requiredMode = Schema.RequiredMode.REQUIRED)
            long weblogCount,
            @Schema(description = "When the business was created.") Instant created,
            @Schema(description = "When the business was last saved.") Instant lastModified) {
    }

    /** A business as a weblog's settings offer it: what the picker shows. */
    public record BusinessChoice(
            @Schema(description = "The id to send as a weblog's businessId.",
                    requiredMode = Schema.RequiredMode.REQUIRED) String id,
            @Schema(description = "The business's name.", requiredMode = Schema.RequiredMode.REQUIRED) String name,
            @Schema(description = "The schema.org type: " + TYPES,
                    allowableValues = {"Organization", "LocalBusiness", "ProfessionalService"},
                    requiredMode = Schema.RequiredMode.REQUIRED) String businessType) {
    }

    /**
     * The body of a create or update. A null field is absent, never a value: on an update it leaves
     * the field unchanged, and a field is removed only by naming it in {@code clear}. Values are
     * stored trimmed, a blank optional one as null, as the Businesses form stores them.
     */
    public record BusinessWrite(
            @Schema(description = "The business's name, at most 255 characters. Required on create; never "
                    + "cleared.") String name,
            @Schema(description = "The schema.org type: " + TYPES + " Defaults to Organization on create; "
                    + "never cleared.", allowableValues = {"Organization", "LocalBusiness", "ProfessionalService"})
            String businessType,
            @Schema(description = "A public http or https URL, at most 255 characters.") String websiteUrl,
            @Schema(description = "Where the business is booked: a public http or https URL, at most 255 "
                    + "characters.") String bookingUrl,
            @Schema(description = "Digits, plus, parentheses, hyphen, dot and space, optionally an extension "
                    + "such as \"ext. 89\"; 3 to 32 characters.") String telephone,
            @Schema(description = "An email address, at most 255 characters.") String email,
            @Schema(description = "The logo's public http or https URL, at most 255 characters.") String logoUrl,
            @Schema(description = "Profile links, each a public http or https URL of at most 255 characters; at "
                    + "most 10. Replaces the whole list.") List<String> sameAs,
            @Schema(description = "The area the business serves, at most 255 characters.") String areaServed,
            @Schema(description = "What the business does.") String description,
            @Schema(description = "Fields to remove: websiteUrl, bookingUrl, telephone, email, logoUrl, sameAs, "
                    + "areaServed, description. Updates only. Null never removes anything.") List<String> clear) {
    }

    /** One page of the site's business profiles. */
    public record BusinessPage(
            @Schema(description = Paging.ITEMS_DESCRIPTION, requiredMode = Schema.RequiredMode.REQUIRED)
            List<BusinessView> items,
            @Schema(description = Paging.OFFSET_DESCRIPTION, requiredMode = Schema.RequiredMode.REQUIRED) int offset,
            @Schema(description = Paging.LIMIT_DESCRIPTION, requiredMode = Schema.RequiredMode.REQUIRED) int limit,
            @Schema(description = Paging.HAS_MORE_DESCRIPTION, requiredMode = Schema.RequiredMode.REQUIRED)
            boolean hasMore,
            @Schema(description = "How many business profiles the site has, across every page.",
                    requiredMode = Schema.RequiredMode.REQUIRED) int total) {
    }

    /** One page of the business profiles a weblog may point at. */
    public record BusinessChoicePage(
            @Schema(description = Paging.ITEMS_DESCRIPTION, requiredMode = Schema.RequiredMode.REQUIRED)
            List<BusinessChoice> items,
            @Schema(description = Paging.OFFSET_DESCRIPTION, requiredMode = Schema.RequiredMode.REQUIRED) int offset,
            @Schema(description = Paging.LIMIT_DESCRIPTION, requiredMode = Schema.RequiredMode.REQUIRED) int limit,
            @Schema(description = Paging.HAS_MORE_DESCRIPTION, requiredMode = Schema.RequiredMode.REQUIRED)
            boolean hasMore,
            @Schema(description = "How many business profiles there are to choose from, across every page.",
                    requiredMode = Schema.RequiredMode.REQUIRED) int total) {
    }

    public static BusinessView toView(Business business, long weblogCount) {
        return new BusinessView(business.getId(), business.getName(), typeName(business), business.getWebsiteUrl(),
                business.getBookingUrl(), business.getTelephone(), business.getEmail(), business.getLogoUrl(),
                business.getSameAsList(), business.getAreaServed(), business.getDescription(), weblogCount,
                instant(business.getCreated()), instant(business.getLastModified()));
    }

    /** {@code businesses} sorted by name ignoring case, then id: the order both lists answer in. */
    public static List<Business> sorted(List<Business> businesses) {
        List<Business> sorted = new ArrayList<>(businesses);
        sorted.sort(Comparator.comparing((Business b) -> b.getName() == null ? "" : b.getName(),
                String.CASE_INSENSITIVE_ORDER).thenComparing(Business::getId));
        return sorted;
    }

    public static BusinessChoice toChoice(Business business) {
        return new BusinessChoice(business.getId(), business.getName(), typeName(business));
    }

    /**
     * The values a write leaves: each field {@code body} sends replaces {@code base}'s, each field
     * named in {@code cleared} is removed, and the rest are {@code base}'s. {@code sameAs} is sent
     * as a list and judged, as the form's text area is, one link per line.
     */
    public static BusinessRules.Values overlay(BusinessRules.Values base, BusinessWrite body, Set<String> cleared) {
        return new BusinessRules.Values(
                pick(body.name(), base.name(), false),
                pick(body.businessType(), base.businessType(), false),
                pick(body.websiteUrl(), base.websiteUrl(), cleared.contains("websiteUrl")),
                pick(body.bookingUrl(), base.bookingUrl(), cleared.contains("bookingUrl")),
                pick(body.telephone(), base.telephone(), cleared.contains("telephone")),
                pick(body.email(), base.email(), cleared.contains("email")),
                pick(body.logoUrl(), base.logoUrl(), cleared.contains("logoUrl")),
                pick(lines(body.sameAs()), base.sameAs(), cleared.contains("sameAs")),
                pick(body.areaServed(), base.areaServed(), cleared.contains("areaServed")),
                pick(body.description(), base.description(), cleared.contains("description")));
    }

    private static String pick(String sent, String base, boolean cleared) {
        if (cleared) {
            return null;
        }
        return sent != null ? sent : base;
    }

    /** The links one per line, a null entry skipped; null when no list was sent. */
    private static String lines(List<String> links) {
        if (links == null) {
            return null;
        }
        return String.join("\n", links.stream().filter(Objects::nonNull).toList());
    }

    private static String typeName(Business business) {
        return business.getBusinessType() == null ? null : business.getBusinessType().name();
    }

    private static Instant instant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }
}
