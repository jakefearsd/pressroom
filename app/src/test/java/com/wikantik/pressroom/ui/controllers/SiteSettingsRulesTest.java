package com.wikantik.pressroom.ui.controllers;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ResourceBundle;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.business.MockWeblogger;
import com.wikantik.pressroom.business.PageReference;
import com.wikantik.pressroom.config.runtime.PropertyDef;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.pojos.WeblogPage;
import com.wikantik.pressroom.ui.restapi.ProblemCode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * {@link SiteSettingsRules} on plain values: no request, model, controller or
 * API type (spec 2026-11-20-jsp-rules-and-view-models-design, Task 1,
 * criterion 2). Page lookups are a {@link SiteSettingsRules.PublishedPages}
 * answering from a set, or throwing.
 */
class SiteSettingsRulesTest {

    private static final ResourceBundle BUNDLE = ResourceBundle.getBundle("ApplicationResources");
    private static final String BLOG = SiteSettingsRules.FRONT_DOOR_BLOG;
    private static final String PAGE = SiteSettingsRules.FRONT_DOOR_PAGE;
    private static final String PRIVACY = SiteSettingsRules.PRIVACY_PAGE;

    /** Published: the site pages "home" and "privacy", and "travel/about". */
    private static final Set<PageReference> PUBLISHED = Set.of(new PageReference(null, "home"),
            new PageReference(null, "privacy"), new PageReference("travel", "about"));

    private final AtomicInteger lookups = new AtomicInteger();

    private final SiteSettingsRules.PublishedPages pages = reference -> {
        lookups.incrementAndGet();
        return PUBLISHED.contains(reference);
    };

    private static final SiteSettingsRules.PublishedPages BROKEN = reference -> {
        throw new WebloggerException("database down");
    };

    private static PropertyDef def(String name, String type, String key) {
        PropertyDef def = new PropertyDef();
        def.setName(name);
        def.setType(type);
        def.setKey(key);
        return def;
    }

    private static final Map<String, PropertyDef> DEFS = Map.of(
            "site.name", def("site.name", "string", "configForm.siteName"),
            "site.description", def("site.description", "text", "configForm.siteDescription"),
            "site.pages.maxEntries", def("site.pages.maxEntries", "integer", "configForm.pageMaxEntries"),
            "uploads.file.maxsize", def("uploads.file.maxsize", "float", "configForm.maxFileSize"),
            "uploads.enabled", def("uploads.enabled", "boolean", "configForm.enableFileUploads"),
            "site.discovery.sharePercent",
            def("site.discovery.sharePercent", "integer", "configForm.discoverySharePercent"),
            BLOG, def(BLOG, "string", "configForm.frontpageWeblogHandle"),
            PAGE, def(PAGE, "string", "configForm.frontpagePage"),
            PRIVACY, def(PRIVACY, "string", "configForm.privacyPage"));

    private SiteSettingsRules.Judgement judge(Map<String, String> incoming, Map<String, String> stored) {
        return SiteSettingsRules.judge(incoming, DEFS::get, stored, pages);
    }

    private static Map<String, String> map(String... pairs) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put(pairs[i], pairs[i + 1]);
        }
        return map;
    }

    // --- values ---

    @Test
    void stringAndTextValuesAreTrimmedAndOthersAreKeptAsSent() {
        SiteSettingsRules.Judgement judgement = judge(map("site.name", "  A \t", "site.description", "\n B \n",
                "site.pages.maxEntries", "12", "uploads.enabled", "FALSE"), Map.of());

        assertEquals(List.of(), judgement.violations());
        assertEquals(map("site.name", "A", "site.description", "B", "site.pages.maxEntries", "12",
                "uploads.enabled", "FALSE"), judgement.values());
    }

    @Test
    void aRangeErrorNamesTheLabelForTheScreenAndTheNameForTheApi() {
        RuleViolation violation = only(judge(map("site.discovery.sharePercent", "101"), Map.of()));

        assertEquals(RuleViolation.invalid("site.discovery.sharePercent", "configForm.error.discoveryRange",
                List.of(BUNDLE.getString("configForm.discoverySharePercent"), "0", "100"),
                ProblemCode.CONFIG_OUT_OF_RANGE, "site.discovery.sharePercent", "0", "100"), violation);
    }

    @Test
    void anUnparsedValueNamesItsTypeAndTheRawValue() {
        assertEquals(RuleViolation.invalid("site.pages.maxEntries", "ConfigForm.invalidIntegerProperty",
                List.of(BUNDLE.getString("configForm.pageMaxEntries"), "<b>x</b>"),
                ProblemCode.CONFIG_NOT_INTEGER, "site.pages.maxEntries"),
                only(judge(map("site.pages.maxEntries", "<b>x</b>"), Map.of())));
        assertEquals(RuleViolation.invalid("uploads.file.maxsize", "ConfigForm.invalidFloatProperty",
                List.of(BUNDLE.getString("configForm.maxFileSize"), "big"),
                ProblemCode.CONFIG_NOT_NUMBER, "uploads.file.maxsize"),
                only(judge(map("uploads.file.maxsize", "big"), Map.of())));
        assertEquals(RuleViolation.invalid("uploads.enabled", "ConfigForm.invalidBooleanProperty",
                List.of(BUNDLE.getString("configForm.enableFileUploads"), "on"),
                ProblemCode.CONFIG_NOT_BOOLEAN, "uploads.enabled"),
                only(judge(map("uploads.enabled", "on"), Map.of())));
    }

    @Test
    void aPropertyWithNoLabelInTheBundleIsNamedByItsName() {
        PropertyDef bare = def("site.pages.maxEntries", "integer", null);
        PropertyDef unknownKey = def("site.pages.maxEntries", "integer", "configForm.noSuchLabel");

        assertEquals(List.of("site.pages.maxEntries", "x"), SiteSettingsRules.checkValue(bare, "x").orElseThrow().args());
        assertEquals(List.of("site.pages.maxEntries", "x"),
                SiteSettingsRules.checkValue(unknownKey, "x").orElseThrow().args());
    }

    @Test
    void severalBadValuesAreEachNamedInTheIncomingOrder() {
        SiteSettingsRules.Judgement judgement = judge(map("uploads.file.maxsize", "big",
                "site.pages.maxEntries", "many", PRIVACY, "nope"), Map.of());

        assertEquals(List.of("uploads.file.maxsize", "site.pages.maxEntries", PRIVACY),
                judgement.violations().stream().map(RuleViolation::field).toList());
    }

    // --- the front door ---

    @Test
    void theSiteSpaceInAnyCaseIsNeverTheFrontDoorBlog() {
        for (String handle : List.of(Weblog.SITE_SPACE_HANDLE, "Site-Space", "SITE-SPACE")) {
            assertEquals(RuleViolation.invalid(BLOG, "ConfigForm.invalidProperty", List.of(BLOG),
                    ProblemCode.CONFIG_FRONT_DOOR_SITE_SPACE), only(judge(map(BLOG, handle), Map.of())), handle);
        }
        assertEquals(List.of(), judge(map(BLOG, "travel"), Map.of()).violations());
    }

    @Test
    void aChangedFrontDoorPageMustBeAPublishedSitePage() {
        RuleViolation refused = RuleViolation.invalid(PAGE, "configForm.error.frontpagePage.notFound", List.of(),
                ProblemCode.CONFIG_FRONT_DOOR_PAGE_UNPUBLISHED);

        assertEquals(refused, only(judge(map(BLOG, "", PAGE, "draft"), map(PAGE, "home"))));
        assertEquals(refused, only(judge(map(BLOG, "", PAGE, "about"), Map.of())), "a blog's page is no site page");
        assertEquals(List.of(), judge(map(BLOG, "", PAGE, " home "), map(PAGE, "old")).violations());
    }

    @Test
    void anUnchangedOrClearedFrontDoorPageIsNotLookedUp() {
        assertEquals(List.of(), judge(map(BLOG, "", PAGE, "gone"), map(PAGE, "gone")).violations());
        assertEquals(List.of(), judge(map(BLOG, "travel", PAGE, ""), map(PAGE, "gone")).violations());
        assertEquals(0, lookups.get());
    }

    @Test
    void aFrontDoorPageLookupThatThrowsRefuses() {
        assertEquals(List.of(RuleViolation.invalid(PAGE, "configForm.error.frontpagePage.notFound", List.of(),
                ProblemCode.CONFIG_FRONT_DOOR_PAGE_UNPUBLISHED)),
                SiteSettingsRules.checkReferences(map(PAGE, "home"), Map.of(), BROKEN));
    }

    @Test
    void bothFrontDoorSettingsNonEmptyAfterTheSaveAreRefusedOnThePage() {
        RuleViolation both = RuleViolation.invalid(PAGE, "configForm.error.frontpage.bothSet", List.of(),
                ProblemCode.CONFIG_FRONT_DOOR_BOTH_SET);

        assertEquals(both, only(judge(map(BLOG, "travel", PAGE, "home"), Map.of())));
        assertEquals(both, only(judge(map(PAGE, "home"), map(BLOG, "travel"))), "the stored blog stays");
        assertEquals(both, only(judge(map(BLOG, "travel"), map(PAGE, "home"))), "the stored page stays");
        assertEquals(List.of(), judge(map(BLOG, "", PAGE, "home"), map(BLOG, "travel")).violations());
        assertEquals(List.of(), judge(map(BLOG, "travel", PAGE, ""), map(PAGE, "home")).violations());
    }

    @Test
    void aSaveThatNamesNeitherFrontDoorSettingNeverJudgesThePair() {
        assertEquals(List.of(), judge(map("site.name", "x"), map(BLOG, "travel", PAGE, "home")).violations());
    }

    @Test
    void aRefusedFrontDoorPageIsNotAlsoReportedAsBothSet() {
        assertEquals(List.of(PAGE), judge(map(BLOG, "travel", PAGE, "draft"), Map.of()).violations().stream()
                .map(RuleViolation::field).toList());
    }

    // --- the privacy page ---

    @Test
    void aChangedPrivacyPageMustResolveToAPublishedPage() {
        RuleViolation refused = RuleViolation.invalid(PRIVACY, "configForm.error.privacyPage.notFound", List.of(),
                ProblemCode.CONFIG_PRIVACY_PAGE_UNPUBLISHED);

        for (String reference : List.of("draft", "travel/draft", "ghost/about", "a/b/c", "travel/", "site-space/home")) {
            assertEquals(refused, only(judge(map(PRIVACY, reference), Map.of())), reference);
        }
        assertEquals(List.of(), judge(map(PRIVACY, " privacy "), Map.of()).violations());
        assertEquals(List.of(), judge(map(PRIVACY, "travel/about"), Map.of()).violations());
    }

    @Test
    void anUnchangedOrBlankPrivacyPageIsNotLookedUp() {
        assertEquals(List.of(), judge(map(PRIVACY, "gone"), map(PRIVACY, "gone")).violations());
        assertEquals(List.of(), judge(map(PRIVACY, "  "), map(PRIVACY, "gone")).violations());
        assertEquals(0, lookups.get());
    }

    @Test
    void aPrivacyPageLookupThatThrowsRefuses() {
        assertEquals(List.of(PRIVACY), SiteSettingsRules.checkReferences(map(PRIVACY, "privacy"), Map.of(), BROKEN)
                .stream().map(RuleViolation::field).toList());
    }

    // --- changed ---

    @Test
    void changedMeansSentAndDifferentWithNoStoredValueReadingAsEmpty() {
        Map<String, String> stored = new HashMap<>();
        stored.put("a", "1");
        stored.put("n", null);

        assertTrue(SiteSettingsRules.changed("a", map("a", "2"), stored));
        assertFalse(SiteSettingsRules.changed("a", map("a", "1"), stored));
        assertFalse(SiteSettingsRules.changed("a", map("b", "2"), stored), "not sent");
        assertFalse(SiteSettingsRules.changed("n", map("n", ""), stored), "a stored null reads as empty");
        assertTrue(SiteSettingsRules.changed("x", map("x", "v"), stored), "no row reads as empty");
    }

    // --- the business-tier lookup ---

    @Test
    void publishedPagesAsksTheBusinessTier() throws Exception {
        MockWeblogger weblogger = MockWeblogger.attached();
        try {
            Weblog site = new Weblog();
            site.setSiteSpace(true);
            when(weblogger.weblogManager().getSiteSpace()).thenReturn(site);
            WeblogPage home = new WeblogPage();
            home.setStatus(WeblogPage.PubStatus.PUBLISHED);
            when(weblogger.weblogPageManager().getPageBySlug(site, "home")).thenReturn(home);
            SiteSettingsRules.PublishedPages published = SiteSettingsRules.publishedPages(weblogger.weblogger());

            assertTrue(published.isPublished(new PageReference(null, "home")));
            assertFalse(published.isPublished(new PageReference(null, "nope")));
        } finally {
            weblogger.detach();
        }
    }

    private static RuleViolation only(SiteSettingsRules.Judgement judgement) {
        assertEquals(1, judgement.violations().size(), judgement.violations().toString());
        return judgement.violations().get(0);
    }
}
