package com.wikantik.pressroom.ui.controllers;

import java.util.ArrayList;
import java.util.List;

import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.business.BusinessManager;
import com.wikantik.pressroom.pojos.Business;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.ui.restapi.ProblemCode;
import com.wikantik.pressroom.util.cache.CacheManager;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * The business record's rules, shared by the Businesses screen and {@code /v1/admin/businesses}
 * (spec 2026-11-20-api-gap-resources-design, Task 5): each rule is one {@link RuleViolation} with
 * the API's field name and code and the screen's message key and arguments.
 */
class BusinessRecordRulesTest {

    private static BusinessRules.Values values(String name, String type) {
        return new BusinessRules.Values(name, type, null, null, null, null, null, null, null, null);
    }

    private static BusinessRules.Values valid() {
        return values("Casa", "LocalBusiness");
    }

    private static RuleViolation only(BusinessRules.Values values) {
        List<RuleViolation> found = BusinessRules.problems(values);
        assertEquals(1, found.size(), found.toString());
        return found.get(0);
    }

    private static BusinessRules.Values with(String field, String value) {
        BusinessRules.Values v = valid();
        return new BusinessRules.Values(v.name(), v.businessType(),
                "websiteUrl".equals(field) ? value : null, "bookingUrl".equals(field) ? value : null,
                "telephone".equals(field) ? value : null, "email".equals(field) ? value : null,
                "logoUrl".equals(field) ? value : null, "sameAs".equals(field) ? value : null,
                "areaServed".equals(field) ? value : null, "description".equals(field) ? value : null);
    }

    @Test
    void aValidRecordBreaksNoRule() {
        assertEquals(List.of(), BusinessRules.problems(new BusinessRules.Values("Casa", "Organization",
                "https://casa.example.com", "https://book.example.com", "+1 555 0100", "hi@casa.example.com",
                "https://casa.example.com/logo.png", "https://a.example.com\nhttps://b.example.com", "Azores",
                "Rooms.")));
    }

    @Test
    void aMissingNameIsRequiredAndABlankOneBlank() {
        RuleViolation missing = only(values(null, "Organization"));
        assertEquals("name", missing.field());
        assertEquals("businesses.error.nameRequired", missing.messageKey());
        assertEquals(ProblemCode.FIELD_REQUIRED, missing.code());
        assertEquals(List.of("name"), missing.codeArgs());

        RuleViolation blank = only(values("  ", "Organization"));
        assertEquals("businesses.error.nameRequired", blank.messageKey());
        assertEquals(ProblemCode.FIELD_BLANK, blank.code());
    }

    @Test
    void aNameOver255IsTooLongWithItsLabel() {
        RuleViolation v = only(values("n".repeat(256), "Organization"));
        assertEquals("name", v.field());
        assertEquals("businesses.error.tooLong", v.messageKey());
        assertEquals(List.of(new RuleViolation.Label("businesses.name"), 255), v.args());
        assertEquals(ProblemCode.FIELD_TOO_LONG, v.code());
        assertEquals(List.of("name", "255"), v.codeArgs());
        assertEquals(List.of(), BusinessRules.problems(values("n".repeat(255), "Organization")));
    }

    @Test
    void anUnknownTypeIsRefusedNamingTheKnownOnes() {
        for (String type : new String[] {"Hotel", null, "organization"}) {
            RuleViolation v = only(values("Casa", type));
            assertEquals("businessType", v.field());
            assertEquals("businesses.error.typeInvalid", v.messageKey());
            assertEquals(ProblemCode.BUSINESS_TYPE_UNKNOWN, v.code());
            assertEquals(List.of("Organization, LocalBusiness, ProfessionalService"), v.codeArgs());
        }
    }

    @Test
    void eachUrlFieldIsAPublicHttpUrlOfAtMost255() {
        String[][] fields = {{"websiteUrl", "businesses.websiteUrl"}, {"bookingUrl", "businesses.bookingUrl"},
            {"logoUrl", "businesses.logoUrl"}};
        for (String[] field : fields) {
            RuleViolation bad = only(with(field[0], "http://localhost/x"));
            assertEquals(field[0], bad.field());
            assertEquals("businesses.error.urlInvalid", bad.messageKey());
            assertEquals(List.of(new RuleViolation.Label(field[1])), bad.args());
            assertEquals(ProblemCode.FIELD_NOT_PUBLIC_URL, bad.code());
            assertEquals(List.of(field[0]), bad.codeArgs());

            RuleViolation tooLong = only(with(field[0], "https://example.com/" + "p".repeat(240)));
            assertEquals(ProblemCode.FIELD_TOO_LONG, tooLong.code());
            assertEquals(List.of(new RuleViolation.Label(field[1]), 255), tooLong.args());
        }
    }

    @Test
    void aTelephoneOffThePatternIsRefused() {
        RuleViolation v = only(with("telephone", "call me"));
        assertEquals("telephone", v.field());
        assertEquals("businesses.error.telephoneInvalid", v.messageKey());
        assertEquals(ProblemCode.TELEPHONE_INVALID, v.code());
    }

    @Test
    void anEmailIsValidAndAtMost255() {
        RuleViolation bad = only(with("email", "nope"));
        assertEquals("email", bad.field());
        assertEquals("businesses.error.emailInvalid", bad.messageKey());
        assertEquals(ProblemCode.EMAIL_INVALID, bad.code());

        String label = "d".repeat(60);
        RuleViolation tooLong = only(with("email", "l".repeat(64) + "@" + label + "." + label + "." + label + "."
                + label + ".com"));
        assertEquals(ProblemCode.FIELD_TOO_LONG, tooLong.code());
        assertEquals(List.of("email", "255"), tooLong.codeArgs());
    }

    @Test
    void anAreaServedOver255IsTooLong() {
        RuleViolation v = only(with("areaServed", "a".repeat(256)));
        assertEquals("areaServed", v.field());
        assertEquals(List.of(new RuleViolation.Label("businesses.areaServed"), 255), v.args());
        assertEquals(List.of("areaServed", "255"), v.codeArgs());
    }

    @Test
    void atMostTenSameAsLinks() {
        StringBuilder lines = new StringBuilder();
        for (int i = 0; i < 11; i++) {
            lines.append("https://p").append(i).append(".example.com\n");
        }
        RuleViolation v = only(with("sameAs", lines.toString()));
        assertEquals("sameAs", v.field());
        assertEquals("businesses.error.sameAsTooMany", v.messageKey());
        assertEquals(List.of(10), v.args());
        assertEquals(ProblemCode.SAME_AS_TOO_MANY, v.code());
        assertEquals(List.of("10"), v.codeArgs());
    }

    @Test
    void eachSameAsLinkIsAPublicHttpUrlOfAtMost255() {
        String overlong = "https://example.com/" + "q".repeat(240);
        List<RuleViolation> found = BusinessRules.problems(with("sameAs", "https://ok.example.com\nftp://x.com\n"
                + overlong));
        assertEquals(2, found.size());
        assertEquals(List.of("ftp://x.com"), found.get(0).args());
        assertEquals("businesses.error.sameAsInvalid", found.get(0).messageKey());
        assertEquals(ProblemCode.SAME_AS_ENTRY_INVALID, found.get(0).code());
        assertEquals(List.of("ftp://x.com"), found.get(0).codeArgs());
        assertEquals(List.of(overlong), found.get(1).codeArgs());
    }

    @Test
    void everyRuleIsJudgedAndReportedInFormOrder() {
        List<RuleViolation> found = BusinessRules.problems(new BusinessRules.Values("", "Hotel", "x", "x", "x",
                "x", "x", "x", "a".repeat(256), null));
        assertEquals(List.of("name", "businessType", "websiteUrl", "bookingUrl", "logoUrl", "telephone", "email",
                "areaServed", "sameAs"), found.stream().map(RuleViolation::field).toList());
        assertTrue(found.stream().allMatch(v -> v.kind() == RuleViolation.Kind.INVALID));
    }

    @Test
    void applyStoresTrimmedValuesBlanksAsNullAndSameAsOnePerLine() {
        Business b = new Business();
        b.setWebsiteUrl("https://old.example.com");
        BusinessRules.apply(new BusinessRules.Values("  Casa ", "ProfessionalService", "  ", " https://b.example.com ",
                " 555 0100 ", " hi@casa.example.com ", "", "\n https://a.example.com \n\nhttps://c.example.com",
                " Azores ", " Rooms. "), b);
        assertEquals("Casa", b.getName());
        assertEquals(Business.BusinessType.ProfessionalService, b.getBusinessType());
        assertNull(b.getWebsiteUrl());
        assertEquals("https://b.example.com", b.getBookingUrl());
        assertEquals("555 0100", b.getTelephone());
        assertEquals("hi@casa.example.com", b.getEmail());
        assertNull(b.getLogoUrl());
        assertEquals("https://a.example.com\nhttps://c.example.com", b.getSameAs());
        assertEquals("Azores", b.getAreaServed());
        assertEquals("Rooms.", b.getDescription());

        BusinessRules.apply(values("Casa", "Organization"), b);
        assertNull(b.getSameAs());
        assertNull(b.getDescription());
    }

    @Test
    void theValuesOfAStoredBusinessAreWhatItHolds() {
        Business b = new Business();
        b.setName("Casa");
        b.setBusinessType(Business.BusinessType.LocalBusiness);
        b.setSameAs("https://a.example.com\nhttps://b.example.com");
        b.setDescription("Rooms.");
        b.setTelephone("555 0100");
        assertEquals(new BusinessRules.Values("Casa", "LocalBusiness", null, null, "555 0100", null, null,
                "https://a.example.com\nhttps://b.example.com", null, "Rooms."), BusinessRules.Values.of(b));
        b.setBusinessType(null);
        assertNull(BusinessRules.Values.of(b).businessType());
    }

    /** Criterion 3, the shared half: every blog using the business drops its site-wide cache entries. */
    @Test
    void invalidateUsersDropsTheSiteWideCacheOfEveryBlogUsingTheBusiness() throws Exception {
        Business b = new Business();
        Weblog one = new Weblog();
        Weblog two = new Weblog();
        BusinessManager manager = mock(BusinessManager.class);
        when(manager.getWeblogsUsing(b)).thenReturn(List.of(one, two));
        List<Weblog> invalidated = new ArrayList<>();

        try (MockedStatic<CacheManager> cache = mockStatic(CacheManager.class)) {
            cache.when(() -> CacheManager.invalidate(any(Weblog.class)))
                    .thenAnswer(call -> invalidated.add(call.getArgument(0)));
            BusinessRules.invalidateUsers(b, manager);
        }
        assertEquals(List.of(one, two), invalidated);
    }

    /** The save has committed: a failure while invalidating is logged, never thrown. */
    @Test
    void invalidateUsersSwallowsAFailureAfterTheSave() throws Exception {
        BusinessManager manager = mock(BusinessManager.class);
        when(manager.getWeblogsUsing(any())).thenThrow(new WebloggerException("down"));
        try (MockedStatic<CacheManager> cache = mockStatic(CacheManager.class)) {
            BusinessRules.invalidateUsers(new Business(), manager);
            cache.verifyNoInteractions();
        }
        BusinessManager failing = mock(BusinessManager.class);
        when(failing.getWeblogsUsing(any())).thenReturn(List.of(new Weblog()));
        try (MockedStatic<CacheManager> cache = mockStatic(CacheManager.class)) {
            cache.when(() -> CacheManager.invalidate(any(Weblog.class))).thenThrow(new IllegalStateException("x"));
            BusinessRules.invalidateUsers(new Business(), failing);
        }
    }
}
