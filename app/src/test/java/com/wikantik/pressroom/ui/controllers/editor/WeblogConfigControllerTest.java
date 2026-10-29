/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  The ASF licenses this file to You
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
package com.wikantik.pressroom.ui.controllers.editor;

import java.lang.reflect.Field;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;

import com.wikantik.pressroom.WebloggerException;
import java.math.BigDecimal;

import com.wikantik.pressroom.pojos.Business;
import com.wikantik.pressroom.pojos.Weblog;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ui.Model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link WeblogConfigController} and {@link WeblogConfigBean}.
 *
 * <p>This form writes settings that govern the blog's public behaviour, so the
 * checks that matter are the ones that stop a setting from being applied: the
 * entry-display cap (a blog asking for 10,000 entries per page would render
 * itself into a timeout) and the language invariant the controller enforces
 * after copying the form onto the weblog.
 */
class WeblogConfigControllerTest extends EditorControllerTestSupport {

    private WeblogConfigController controller;
    private WeblogConfigBean bean;
    private Model model;

    @BeforeEach
    void setUp() throws Exception {
        controller = prepare(new WeblogConfigController());
        bean = new WeblogConfigBean();
        model = newModel();

        // The display-count cap is a runtime property; unstubbed it reads as -1,
        // which would reject every possible value.
        givenRuntimeProperty("site.pages.maxEntries", "30");

        weblog.setActive(Boolean.TRUE);
        bean.copyFrom(weblog);
    }

    // --- viewing ---

    @Test
    void openingTheFormLoadsTheWeblogsCurrentSettings() {
        weblog.setName("My Blog");
        weblog.setTagline("A tagline");
        weblog.setEntryDisplayCount(25);

        String view = controller.execute(request, model, bean);

        assertEquals(".WeblogConfig", view);
        assertEquals("My Blog", bean.getName());
        assertEquals("A tagline", bean.getTagline());
        assertEquals(25, bean.getEntryDisplayCount());
        assertEquals(WEBLOG_HANDLE, bean.getHandle());
    }

    // --- saving ---

    @Test
    void savingAppliesTheFormToTheWeblogAndPersistsIt() throws Exception {
        bean.setName("Renamed Blog");
        bean.setTagline("New tagline");
        bean.setEmailAddress("owner@example.com");
        bean.setEntryDisplayCount(20);

        String view = controller.save(request, model, bean);

        assertEquals(".WeblogConfig", view);
        assertEquals("Renamed Blog", weblog.getName());
        assertEquals("New tagline", weblog.getTagline());
        assertEquals("owner@example.com", weblog.getEmailAddress());
        assertEquals(20, weblog.getEntryDisplayCount());
        verify(weblogger.getWeblogManager()).saveWeblog(weblog);
        assertTrue(messages(model).contains("websiteSettings.savedChanges"),
                "Expected a save confirmation, got: " + messages(model));
    }

    @Test
    void savingStoresTheCommentSettings() throws Exception {
        bean.setCommentsEnabled(true);
        bean.setCommentNotify(false);

        controller.save(request, model, bean);

        assertEquals(Boolean.TRUE, weblog.getCommentsEnabled());
        assertEquals(Boolean.FALSE, weblog.getCommentNotify());
    }

    @Test
    void savingNeverChangesTheWeblogHandle() throws Exception {
        // The handle is the blog's URL and its identity in every permission
        // row; copyTo deliberately does not carry it across.
        bean.setHandle("hijacked");

        controller.save(request, model, bean);

        assertEquals(WEBLOG_HANDLE, weblog.getHandle(),
                "A posted handle must not be able to rename the blog out from under its URLs");
    }

    @Test
    void anEntryDisplayCountAboveTheSiteCapIsRefused() throws Exception {
        // Beyond the cap the front page renders unboundedly many entries.
        bean.setEntryDisplayCount(500);

        controller.save(request, model, bean);

        assertTrue(errors(model).contains("websiteSettings.error.entryDisplayCount"),
                "Expected an entry-display-count error, got: " + errors(model));
        verify(weblogger.getWeblogManager(), never()).saveWeblog(any());
    }

    @Test
    void anEntryDisplayCountExactlyAtTheSiteCapIsAccepted() throws Exception {
        // The check is strictly greater-than; the cap itself must be usable.
        bean.setEntryDisplayCount(30);

        controller.save(request, model, bean);

        assertTrue(errors(model).isEmpty(), "The cap itself must be allowed: " + errors(model));
        verify(weblogger.getWeblogManager()).saveWeblog(weblog);
    }

    @Test
    void aFailedSaveIsReportedRatherThanConfirmed() throws Exception {
        org.mockito.Mockito.doThrow(new WebloggerException("database down"))
                .when(weblogger.getWeblogManager()).saveWeblog(any());

        controller.save(request, model, bean);

        assertFalse(messages(model).contains("websiteSettings.savedChanges"),
                "A failed save must not report success");
        assertTrue(errors(model).size() >= 1,
                "Expected the failure to be surfaced, got: " + errors(model));
    }

    // --- custom domain ---

    private String previousVhostCertZones;

    @AfterEach
    void restoreVhostCertZones() {
        // myValidate reads this straight off WebloggerConfig, which is
        // process-global, so any test that overrides it must put it back.
        if (previousVhostCertZones != null) {
            overrideConfigProperty("vhost.cert.zones", previousVhostCertZones);
            previousVhostCertZones = null;
        }
    }

    @Test
    void blankCustomDomainIsAcceptedAndClearsTheWeblog() throws Exception {
        weblog.setCustomDomain("old.example.com");
        bean.setCustomDomain("  ");

        controller.save(request, model, bean);

        assertTrue(errors(model).isEmpty(), "Expected no errors, got: " + errors(model));
        assertNull(weblog.getCustomDomain());
        verify(weblogger.getWeblogManager()).saveWeblog(weblog);
    }

    @Test
    void aWellFormedCustomDomainIsNormalisedAndSaved() throws Exception {
        bean.setCustomDomain("  VHost.Example.COM ");

        controller.save(request, model, bean);

        assertTrue(errors(model).isEmpty(), "Expected no errors, got: " + errors(model));
        assertEquals("vhost.example.com", weblog.getCustomDomain());
        assertEquals("vhost.example.com", bean.getCustomDomain(),
                "The bean re-rendered on the response must show the normalised value too");
        verify(weblogger.getWeblogManager()).saveWeblog(weblog);
    }

    @Test
    void aMalformedCustomDomainIsRejected() throws Exception {
        bean.setCustomDomain("not a hostname");

        controller.save(request, model, bean);

        assertTrue(errors(model).contains("websiteSettings.customDomain.invalid"),
                "Expected an invalid-domain error, got: " + errors(model));
        verify(weblogger.getWeblogManager(), never()).saveWeblog(any());
    }

    @Test
    void aCustomDomainAlreadyClaimedByAnotherWeblogIsRejected() throws Exception {
        Weblog other = new Weblog();
        other.setHandle("otherblog");
        when(weblogger.getWeblogManager().getWeblogByCustomDomain("taken.example.com"))
                .thenReturn(other);
        bean.setCustomDomain("taken.example.com");

        controller.save(request, model, bean);

        assertTrue(errors(model).contains("websiteSettings.customDomain.taken"),
                "Expected a taken-domain error, got: " + errors(model));
        verify(weblogger.getWeblogManager(), never()).saveWeblog(any());
    }

    /**
     * Re-saving a weblog's own unchanged domain must not trip the uniqueness
     * check against itself.
     */
    @Test
    void reSavingThisWeblogsOwnCustomDomainIsNotAConflict() throws Exception {
        Weblog self = new Weblog();
        self.setHandle(WEBLOG_HANDLE);
        when(weblogger.getWeblogManager().getWeblogByCustomDomain("mine.example.com"))
                .thenReturn(self);
        bean.setCustomDomain("mine.example.com");

        controller.save(request, model, bean);

        assertTrue(errors(model).isEmpty(), "Expected no errors, got: " + errors(model));
        verify(weblogger.getWeblogManager()).saveWeblog(weblog);
    }

    /**
     * I4: {@code BaseController.initBeanBinder} sets {@code
     * setFieldDefaultPrefix("bean.")}, so any {@code bean.<property>} POST
     * parameter binds -- including {@code bean.handle}, which the JSP never
     * renders. Before this fix the uniqueness check compared the claimant to
     * the SUBMITTED {@code bean.getHandle()} rather than the real action
     * weblog, so an attacker posting {@code bean.handle=<the claimant's own
     * handle>} alongside a taken {@code customDomain} made the check
     * trivially pass (the claimant's handle equals itself) -- the V027
     * unique index still caught the actual write, but this validation gate
     * must not be foolable by a field the form never exposes.
     */
    @Test
    void theUniquenessCheckComparesAgainstTheRealActionWeblogNotTheSubmittedHandle() throws Exception {
        Weblog claimant = new Weblog();
        claimant.setHandle("victim");
        when(weblogger.getWeblogManager().getWeblogByCustomDomain("taken.example.com"))
                .thenReturn(claimant);
        bean.setCustomDomain("taken.example.com");
        // Stands in for an attacker directly posting bean.handle=victim; the
        // JSP itself never renders this field.
        bean.setHandle(claimant.getHandle());

        controller.save(request, model, bean);

        assertTrue(errors(model).contains("websiteSettings.customDomain.taken"),
                "Expected a taken-domain error even though the submitted bean.handle "
                        + "matched the claimant's own handle, got: " + errors(model));
        verify(weblogger.getWeblogManager(), never()).saveWeblog(any());
    }

    /**
     * I4b: nothing rejected setting customDomain to the site's own hostname.
     * Once claimed, VirtualHostRegistry resolves that host to the claiming
     * weblog for every request -- including /roller-ui/**, which
     * ControlPlaneHostFilter then redirects back to the very host it just
     * arrived on once site.absoluteurl is set, an infinite loop on the admin
     * UI with no route back except a manual database edit.
     */
    @Test
    void claimingTheSitesOwnHostnameAsACustomDomainIsRejected() throws Exception {
        givenRuntimeProperty("site.absoluteurl", "https://blog.example.com");
        bean.setCustomDomain("blog.example.com");

        controller.save(request, model, bean);

        assertTrue(errors(model).contains("websiteSettings.customDomain.isSiteHost"),
                "Expected a site-host rejection, got: " + errors(model));
        verify(weblogger.getWeblogManager(), never()).saveWeblog(any());
    }

    /** Scheme and path in site.absoluteurl must not defeat the host comparison. */
    @Test
    void claimingTheSitesOwnHostnameIsRejectedRegardlessOfSchemeOrPath() throws Exception {
        givenRuntimeProperty("site.absoluteurl", "https://blog.example.com/roller");
        bean.setCustomDomain("blog.example.com");

        controller.save(request, model, bean);

        assertTrue(errors(model).contains("websiteSettings.customDomain.isSiteHost"),
                "Expected a site-host rejection, got: " + errors(model));
        verify(weblogger.getWeblogManager(), never()).saveWeblog(any());
    }

    /** A hostname that merely differs from the site host must still be allowed. */
    @Test
    void aHostnameOtherThanTheSitesOwnIsNotRejectedAsTheSiteHost() throws Exception {
        givenRuntimeProperty("site.absoluteurl", "https://blog.example.com");
        bean.setCustomDomain("vhost.example.com");

        controller.save(request, model, bean);

        assertFalse(errors(model).contains("websiteSettings.customDomain.isSiteHost"),
                "Expected no site-host rejection, got: " + errors(model));
        verify(weblogger.getWeblogManager()).saveWeblog(weblog);
    }

    /** With site.absoluteurl unset there is no site host to collide with. */
    @Test
    void withNoSiteAbsoluteUrlConfiguredNoHostnameIsRejectedAsTheSiteHost() throws Exception {
        bean.setCustomDomain("blog.example.com");

        controller.save(request, model, bean);

        assertFalse(errors(model).contains("websiteSettings.customDomain.isSiteHost"),
                "Expected no site-host rejection, got: " + errors(model));
        verify(weblogger.getWeblogManager()).saveWeblog(weblog);
    }

    @Test
    void aWebloggerExceptionDuringTheUniquenessCheckIsReportedAsInvalid() throws Exception {
        when(weblogger.getWeblogManager().getWeblogByCustomDomain("vhost.example.com"))
                .thenThrow(new WebloggerException("database down"));
        bean.setCustomDomain("vhost.example.com");

        controller.save(request, model, bean);

        assertTrue(errors(model).contains("websiteSettings.customDomain.invalid"),
                "Expected the lookup failure to surface as an invalid-domain error, got: " + errors(model));
        verify(weblogger.getWeblogManager(), never()).saveWeblog(any());
    }

    @Test
    void aCustomDomainOutsideConfiguredZonesWarnsButStillSaves() throws Exception {
        previousVhostCertZones = overrideConfigProperty("vhost.cert.zones", "thelocalwiki.com");
        bean.setCustomDomain("maiiavorobiova.com");

        controller.save(request, model, bean);

        assertTrue(errors(model).isEmpty(), "A zone mismatch must warn, not block: " + errors(model));
        assertEquals("maiiavorobiova.com", model.getAttribute("customDomainWarning"));
        verify(weblogger.getWeblogManager()).saveWeblog(weblog);
    }

    @Test
    void aCustomDomainInsideAConfiguredZoneDoesNotWarn() throws Exception {
        previousVhostCertZones = overrideConfigProperty("vhost.cert.zones", "thelocalwiki.com");
        bean.setCustomDomain("berlin.thelocalwiki.com");

        controller.save(request, model, bean);

        assertTrue(errors(model).isEmpty(), "Expected no errors, got: " + errors(model));
        assertNull(model.getAttribute("customDomainWarning"));
        verify(weblogger.getWeblogManager()).saveWeblog(weblog);
    }

    /**
     * The zone-warning block must not run independently of the rest of
     * validation: a malformed hostname is rejected (nothing saves), and the
     * page must not ALSO claim "Saved, but this server has no wildcard
     * certificate..." for a domain that was never saved at all. Before this
     * fix myValidate computed isOutsideCertZones() unconditionally, so a
     * configured zone plus a mistyped hostname produced a validation error
     * and a false success-shaped warning banner in the same response.
     */
    @Test
    void aMalformedCustomDomainInAConfiguredZoneShowsNoZoneWarning() throws Exception {
        previousVhostCertZones = overrideConfigProperty("vhost.cert.zones", "thelocalwiki.com");
        bean.setCustomDomain("bad_host.otherzone.com");

        controller.save(request, model, bean);

        assertTrue(errors(model).contains("websiteSettings.customDomain.invalid"),
                "Expected an invalid-domain error, got: " + errors(model));
        assertNull(model.getAttribute("customDomainWarning"),
                "A failed save must not also claim the zone-warning success banner");
        verify(weblogger.getWeblogManager(), never()).saveWeblog(any());
    }

    /**
     * Same blind spot as above, via the taken-domain rejection path rather
     * than the malformed-hostname path.
     */
    @Test
    void aTakenCustomDomainInAConfiguredZoneShowsNoZoneWarning() throws Exception {
        previousVhostCertZones = overrideConfigProperty("vhost.cert.zones", "thelocalwiki.com");
        Weblog other = new Weblog();
        other.setHandle("otherblog");
        when(weblogger.getWeblogManager().getWeblogByCustomDomain("taken.otherzone.com"))
                .thenReturn(other);
        bean.setCustomDomain("taken.otherzone.com");

        controller.save(request, model, bean);

        assertTrue(errors(model).contains("websiteSettings.customDomain.taken"),
                "Expected a taken-domain error, got: " + errors(model));
        assertNull(model.getAttribute("customDomainWarning"),
                "A failed save must not also claim the zone-warning success banner");
        verify(weblogger.getWeblogManager(), never()).saveWeblog(any());
    }

    // --- WeblogConfigBean ---

    /**
     * Multi-locale weblogs are gone: {@code WeblogConfigBean} must carry no
     * field for either the "publish in multiple languages" toggle or the
     * "show all languages" toggle that used to sit beside it.
     */
    @Test
    void multiLocaleFieldsAreGone() {
        List<String> offenders = Arrays.stream(WeblogConfigBean.class.getDeclaredFields())
                .map(Field::getName)
                .filter(n -> n.contains("MultiLang") || n.contains("AllLangs"))
                .toList();
        assertTrue(offenders.isEmpty(), "multi-locale fields survive: " + offenders);
    }

    @Test
    void beanRoundTripsTheSettingsItOwns() {
        Weblog source = new Weblog();
        source.setHandle("source");
        source.setName("Source Blog");
        source.setTagline("Tag");
        source.setEmailAddress("a@example.com");
        source.setLocale("fr_FR");
        source.setTimeZone("Asia/Tokyo");
        source.setEntryDisplayCount(7);
        source.setAbout("About me");
        source.setIconPath("icon.png");
        source.setActive(Boolean.TRUE);
        source.setCustomDomain("vhost.example.com");

        WeblogConfigBean copy = new WeblogConfigBean();
        copy.copyFrom(source);

        assertEquals("source", copy.getHandle());
        assertEquals("Source Blog", copy.getName());
        assertEquals("fr_FR", copy.getLocale());
        assertEquals("Asia/Tokyo", copy.getTimeZone());
        assertEquals(7, copy.getEntryDisplayCount());
        assertEquals("About me", copy.getAbout());
        assertEquals("icon.png", copy.getIcon());
        assertEquals("vhost.example.com", copy.getCustomDomain());

        Weblog target = new Weblog();
        copy.copyTo(target);

        assertEquals("Source Blog", target.getName());
        assertEquals("Asia/Tokyo", target.getTimeZone());
        assertEquals("vhost.example.com", target.getCustomDomain());
        assertNull(target.getHandle(), "copyTo must never write the handle");
    }


    // --- errors point at the field they name (B8) ---

    @Test
    void anInvalidListUuidNamesItsField() throws Exception {
        bean.setNewsletterListUuid("not-a-uuid");

        controller.save(request, model, bean);

        assertEquals(List.of("weblog_bean_newsletterListUuid"), invalidFields(model),
                "The banner at the top says something was refused; only the marker says "
                        + "which of this form's thirty-odd fields it was");
    }

    @Test
    void eachRefusedSettingOnThisFormNamesItsOwnField() throws Exception {
        // Five independent checks in one myValidate pass: every one of them
        // has to carry its own control's id, or the marker points at the
        // wrong box on the one screen where that is easiest to get wrong.
        bean.setEntryDisplayCount(500);
        bean.setNewsletterListUuid("not-a-uuid");
        bean.setAnalyticsSiteId("not-a-uuid");
        bean.setAnalyticsShareUrl("ftp://example.com/share");
        bean.setCustomDomain("not a hostname");

        controller.save(request, model, bean);

        assertEquals(List.of("weblog_bean_entryDisplayCount",
                        "weblog_bean_newsletterListUuid",
                        "weblog_bean_analyticsSiteId",
                        "weblog_bean_analyticsShareUrl",
                        "weblog_bean_customDomain"),
                invalidFields(model));
    }

    // --- business and place ---

    private Business givenBusiness(String id) throws Exception {
        Business business = new Business();
        business.setId(id);
        business.setName("Casa do Mar");
        when(weblogger.businessManager().getBusiness(id)).thenReturn(business);
        return business;
    }

    private void assertBusinessFieldRefused(String fieldId) throws Exception {
        controller.save(request, model, bean);
        assertEquals(List.of(fieldId), invalidFields(model));
        verify(weblogger.getWeblogManager(), never()).saveWeblog(any());
    }

    @Test
    void savingAValidBusinessAndPlaceCopiesEveryField() throws Exception {
        Business business = givenBusiness("biz-1");
        bean.setBusinessId("biz-1");
        bean.setPlaceType("LodgingBusiness");
        bean.setPlaceLocality("Ponta Delgada");
        bean.setPlaceRegion("Azores");
        bean.setPlaceCountry("pt");
        bean.setPlaceLat("37.7412");
        bean.setPlaceLng("-25.6756");
        bean.setBookingUrl("https://book.example.com/stay");

        controller.save(request, model, bean);

        assertTrue(errors(model).isEmpty(), "Expected no errors, got: " + errors(model));
        assertEquals(business, weblog.getBusiness());
        assertEquals("LodgingBusiness", weblog.getPlaceType());
        assertEquals("Ponta Delgada", weblog.getPlaceLocality());
        assertEquals("Azores", weblog.getPlaceRegion());
        assertEquals("PT", weblog.getPlaceCountry(), "lower-case is accepted and stored upper-case");
        assertEquals(new BigDecimal("37.74"), weblog.getPlaceLat());
        assertEquals(new BigDecimal("-25.68"), weblog.getPlaceLng());
        assertEquals("https://book.example.com/stay", weblog.getBookingUrl());
        verify(weblogger.getWeblogManager()).saveWeblog(weblog);
    }

    @Test
    void aBlankBusinessIdClearsTheBusiness() throws Exception {
        weblog.setBusiness(givenBusiness("biz-1"));
        bean.setBusinessId("  ");

        controller.save(request, model, bean);

        assertNull(weblog.getBusiness());
        verify(weblogger.getWeblogManager()).saveWeblog(weblog);
    }

    @Test
    void openingTheFormShowsTheStoredBusinessAndPlace() throws Exception {
        weblog.setBusiness(givenBusiness("biz-1"));
        weblog.setPlaceType("LodgingBusiness");
        weblog.setPlaceLat(new BigDecimal("37.74"));
        weblog.setBookingUrl("https://book.example.com/x");
        when(weblogger.businessManager().getBusinesses()).thenReturn(List.of());

        controller.execute(request, model, bean);

        assertEquals("biz-1", bean.getBusinessId());
        assertEquals("LodgingBusiness", bean.getPlaceType());
        assertEquals("37.74", bean.getPlaceLat());
        assertEquals("https://book.example.com/x", bean.getBookingUrl());
        assertEquals(List.of(), model.getAttribute("businesses"));
    }

    /**
     * The lookup passes validation, then fails when the save resolves it
     * again. The weblog must come out untouched and unsaved.
     */
    @Test
    void aBusinessLookupThatFailsWhileSavingLeavesTheWeblogUntouched() throws Exception {
        Business business = givenBusiness("biz-1");
        when(weblogger.businessManager().getBusiness("biz-1"))
                .thenReturn(business).thenThrow(new WebloggerException("db down"));
        String before = weblog.getName();
        bean.setBusinessId("biz-1");
        bean.setName("Renamed in a failed save");

        controller.save(request, model, bean);

        assertEquals(before, weblog.getName(), "a failed save must not half-update the weblog");
        assertEquals(List.of("generic.error.check.logs"), errors(model));
        verify(weblogger.getWeblogManager(), never()).saveWeblog(any());
    }

    /**
     * Characterisation (passed immediately): booking links are public-facing,
     * so localhost stays refused on this form as on the Businesses screen.
     */
    @Test
    void aLocalhostBookingUrlIsRefusedWithTheBookingMessage() throws Exception {
        bean.setBookingUrl("http://localhost:8080/book");
        assertBusinessFieldRefused("weblog_bean_bookingUrl");
        assertEquals(List.of("websiteSettings.bookingUrl.invalid"), errors(model));
    }

    @Test
    void anUnknownBusinessIsRefused() throws Exception {
        bean.setBusinessId("nope");
        assertBusinessFieldRefused("weblog_bean_businessId");
    }

    @Test
    void aBusinessLookupThatFailsIsRefusedNotSkipped() throws Exception {
        when(weblogger.businessManager().getBusiness("biz-1"))
                .thenThrow(new WebloggerException("db down"));
        bean.setBusinessId("biz-1");
        assertBusinessFieldRefused("weblog_bean_businessId");
    }

    @Test
    void anUnknownPlaceTypeIsRefused() throws Exception {
        bean.setPlaceType("Hotel");
        bean.setPlaceLocality("Lisbon");
        assertBusinessFieldRefused("weblog_bean_placeType");
    }

    @Test
    void aPlaceTypeWithoutALocalityIsRefused() throws Exception {
        bean.setPlaceType("LodgingBusiness");
        bean.setPlaceLocality("  ");
        assertBusinessFieldRefused("weblog_bean_placeLocality");
    }

    @Test
    void aCountryThatIsNotTwoLettersIsRefused() throws Exception {
        for (String bad : new String[] {"prt", "P1", "p"}) {
            model = newModel();
            bean.setPlaceCountry(bad);
            controller.save(request, model, bean);
            assertEquals(List.of("weblog_bean_placeCountry"), invalidFields(model), bad);
        }
        verify(weblogger.getWeblogManager(), never()).saveWeblog(any());
    }

    @Test
    void aLatitudeOutOfRangeOrNotANumberIsRefused() throws Exception {
        for (String bad : new String[] {"91", "-90.5", "north"}) {
            model = newModel();
            bean.setPlaceLat(bad);
            bean.setPlaceLng("10");
            controller.save(request, model, bean);
            assertEquals(List.of("weblog_bean_placeLat"), invalidFields(model), bad);
        }
        verify(weblogger.getWeblogManager(), never()).saveWeblog(any());
    }

    /**
     * A coordinate is a plain decimal. Exponent notation is refused at
     * validation, before any BigDecimal arithmetic: "1E-100000000" is
     * numerically 0 and so passes a range check, but rounding it to two
     * places computes 10^99999998 and ties up a request thread for ~25 s.
     * Bounded, so a regression fails here rather than hanging the build.
     */
    @Test
    void aCoordinateWithATinyExponentIsRefusedQuickly() throws Exception {
        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
            bean.setPlaceLat("1E-100000000");
            bean.setPlaceLng("10");
            controller.save(request, model, bean);
            assertEquals(List.of("weblog_bean_placeLat"), invalidFields(model));

            model = newModel();
            bean.setPlaceLat("10");
            bean.setPlaceLng("-1e-100000000");
            controller.save(request, model, bean);
            assertEquals(List.of("weblog_bean_placeLng"), invalidFields(model));

            // Beyond BigInteger's range the rounding throws instead of
            // hanging; that must be a field error too, not a failed save.
            model = newModel();
            bean.setPlaceLat("1E-999999999");
            bean.setPlaceLng("10");
            controller.save(request, model, bean);
            assertEquals(List.of("weblog_bean_placeLat"), invalidFields(model));
        });
        verify(weblogger.getWeblogManager(), never()).saveWeblog(any());
    }

    @Test
    void aCoordinateInExponentNotationIsRefusedEvenWhenInRange() throws Exception {
        String[][] cases = {
                {"1e1", "10", "weblog_bean_placeLat"},
                {"10", "1E+2", "weblog_bean_placeLng"},
        };
        for (String[] c : cases) {
            model = newModel();
            bean.setPlaceLat(c[0]);
            bean.setPlaceLng(c[1]);
            controller.save(request, model, bean);
            assertEquals(List.of(c[2]), invalidFields(model), c[0] + "," + c[1]);
        }
        verify(weblogger.getWeblogManager(), never()).saveWeblog(any());
    }

    @Test
    void plainDecimalCoordinatesAtTheirLimitsAreAccepted() throws Exception {
        bean.setPlaceLat("-90.00000000");
        bean.setPlaceLng("180");

        controller.save(request, model, bean);

        assertTrue(errors(model).isEmpty(), "Expected no errors, got: " + errors(model));
        assertEquals(new BigDecimal("-90.00"), weblog.getPlaceLat());
        assertEquals(new BigDecimal("180.00"), weblog.getPlaceLng());
    }

    /** A leading plus and a missing integer part are ordinary ways to type a coordinate. */
    @Test
    void aLeadingPlusAndAMissingIntegerPartAreAccepted() throws Exception {
        bean.setPlaceLat("+10");
        bean.setPlaceLng(".5");
        controller.save(request, model, bean);
        assertTrue(errors(model).isEmpty(), "Expected no errors, got: " + errors(model));
        assertEquals(new BigDecimal("10.00"), weblog.getPlaceLat());
        assertEquals(new BigDecimal("0.50"), weblog.getPlaceLng());

        model = newModel();
        bean.setPlaceLat("-.5");
        bean.setPlaceLng("+.25");
        controller.save(request, model, bean);
        assertTrue(errors(model).isEmpty(), "Expected no errors, got: " + errors(model));
        assertEquals(new BigDecimal("-0.50"), weblog.getPlaceLat());
    }

    @Test
    void signAndDotAloneAreStillRefused() throws Exception {
        for (String bad : new String[] {"+", "-", ".", "+.", "+-5", "++5", "5.", "+1e1", "+.5e1"}) {
            model = newModel();
            bean.setPlaceLat(bad);
            bean.setPlaceLng("10");
            controller.save(request, model, bean);
            assertEquals(List.of("weblog_bean_placeLat"), invalidFields(model), bad);
        }
    }

    @Test
    void aLongitudeOutOfRangeIsRefused() throws Exception {
        bean.setPlaceLat("10");
        bean.setPlaceLng("181");
        assertBusinessFieldRefused("weblog_bean_placeLng");
    }

    @Test
    void aLongitudeWithoutALatitudeIsRefused() throws Exception {
        bean.setPlaceLng("10");
        assertBusinessFieldRefused("weblog_bean_placeLat");
    }

    @Test
    void aLatitudeWithoutALongitudeIsRefused() throws Exception {
        bean.setPlaceLat("10");
        assertBusinessFieldRefused("weblog_bean_placeLng");
    }

    // --- the business list could not load (fail closed) ---

    @Test
    void aBusinessListThatCannotLoadIsReportedAndTheFormKeepsTheCurrentBusiness() throws Exception {
        weblog.setBusiness(givenBusiness("biz-1"));
        when(weblogger.businessManager().getBusinesses()).thenThrow(new WebloggerException("db down"));

        controller.execute(request, model, bean);

        assertEquals(List.of("websiteSettings.business.listUnavailable"), errors(model));
        assertEquals(Boolean.TRUE, model.getAttribute("businessesUnavailable"));
        assertEquals("biz-1", bean.getBusinessId(),
                "the form must carry the current business, or the next save unlinks it");
    }

    @Test
    void aBusinessListThatLoadsSetsNoUnavailableFlag() throws Exception {
        when(weblogger.businessManager().getBusinesses()).thenReturn(List.of());

        controller.execute(request, model, bean);

        assertTrue(errors(model).isEmpty(), "Expected no errors, got: " + errors(model));
        assertNull(model.getAttribute("businessesUnavailable"));
    }

    @Test
    void aSaveThatPostsTheCarriedBusinessKeepsIt() throws Exception {
        Business business = givenBusiness("biz-1");
        weblog.setBusiness(business);
        bean.setBusinessId("biz-1");

        controller.save(request, model, bean);

        assertTrue(errors(model).isEmpty(), "Expected no errors, got: " + errors(model));
        assertEquals(business, weblog.getBusiness());
        verify(weblogger.getWeblogManager()).saveWeblog(weblog);
    }

    @Test
    void aSaveWhileTheBusinessListCannotLoadChangesNothingAndKeepsTheInput() throws Exception {
        Business business = givenBusiness("biz-1");
        weblog.setBusiness(business);
        when(weblogger.businessManager().getBusinesses()).thenThrow(new WebloggerException("db down"));
        bean.setBusinessId("");
        bean.setPlaceLocality("Typed town");

        controller.save(request, model, bean);

        assertTrue(errors(model).contains("websiteSettings.business.listUnavailable"),
                "Expected the list failure to be reported, got: " + errors(model));
        assertEquals(business, weblog.getBusiness(), "a save that could not see the list must not unlink");
        verify(weblogger.getWeblogManager(), never()).saveWeblog(any());
        assertEquals("", bean.getBusinessId(), "the re-rendered form keeps what was posted");
        assertEquals("Typed town", bean.getPlaceLocality());
    }

    // --- column lengths (place_locality/place_region varchar(128), booking_url varchar(255)) ---

    @Test
    void placeTextLongerThanItsColumnIsAFieldErrorNotAFailedSave() throws Exception {
        String[][] cases = {
                {"placeLocality", "a".repeat(129), "weblog_bean_placeLocality"},
                {"placeRegion", "r".repeat(129), "weblog_bean_placeRegion"},
                {"bookingUrl", "https://book.example.com/" + "p".repeat(231), "weblog_bean_bookingUrl"},
        };
        for (String[] c : cases) {
            model = newModel();
            bean = new WeblogConfigBean();
            bean.copyFrom(weblog);
            switch (c[0]) {
                case "placeLocality" -> bean.setPlaceLocality(c[1]);
                case "placeRegion" -> bean.setPlaceRegion(c[1]);
                default -> bean.setBookingUrl(c[1]);
            }
            controller.save(request, model, bean);
            assertEquals(List.of(c[2]), invalidFields(model), c[0]);
            assertEquals(List.of("businesses.error.tooLong"), errors(model), c[0]);
        }
        verify(weblogger.getWeblogManager(), never()).saveWeblog(any());
    }

    @Test
    void overlongCopyrightIsAFieldError() throws Exception {
        bean.setCopyright("c".repeat(256));

        controller.save(request, model, bean);

        assertEquals(List.of("weblog_bean_copyright"), invalidFields(model));
        assertEquals(List.of("businesses.error.tooLong"), errors(model));
        verify(weblogger.getWeblogManager(), never()).saveWeblog(any());
    }

    @Test
    void placeTextExactlyAtItsColumnLengthIsAccepted() throws Exception {
        bean.setPlaceLocality("a".repeat(128));
        bean.setPlaceRegion("r".repeat(128));
        bean.setBookingUrl("https://book.example.com/" + "p".repeat(230));

        controller.save(request, model, bean);

        assertTrue(errors(model).isEmpty(), "Expected no errors, got: " + errors(model));
        assertEquals(255, weblog.getBookingUrl().length());
        verify(weblogger.getWeblogManager()).saveWeblog(weblog);
    }

    @Test
    void aBookingUrlThatIsNotHttpIsRefused() throws Exception {
        bean.setBookingUrl("javascript:alert(1)");
        assertBusinessFieldRefused("weblog_bean_bookingUrl");
    }

    // --- one field error per rule, pinned before the rules moved to WeblogSettingsRules ---

    private void assertFieldRefused(String fieldId) throws Exception {
        controller.save(request, model, bean);
        assertTrue(invalidFields(model).contains(fieldId),
                "Expected " + fieldId + " among " + invalidFields(model));
        verify(weblogger.getWeblogManager(), never()).saveWeblog(any());
    }

    /** Characterisation: written against the pre-extraction controller and expected to pass unchanged. */
    @Test
    void charEntryDisplayCountOverTheMaxNamesItsField() throws Exception {
        bean.setEntryDisplayCount(31);
        assertFieldRefused("weblog_bean_entryDisplayCount");
    }

    /** Characterisation: written against the pre-extraction controller and expected to pass unchanged. */
    @Test
    void charNewsletterListUuidNamesItsField() throws Exception {
        bean.setNewsletterListUuid("not-a-uuid");
        assertFieldRefused("weblog_bean_newsletterListUuid");
    }

    /** Characterisation: written against the pre-extraction controller and expected to pass unchanged. */
    @Test
    void charAnalyticsSiteIdNamesItsField() throws Exception {
        bean.setAnalyticsSiteId("nope");
        assertFieldRefused("weblog_bean_analyticsSiteId");
    }

    /** Characterisation: written against the pre-extraction controller and expected to pass unchanged. */
    @Test
    void charAnalyticsShareUrlNamesItsField() throws Exception {
        bean.setAnalyticsShareUrl("ftp://x");
        assertFieldRefused("weblog_bean_analyticsShareUrl");
    }

    /** Characterisation: written against the pre-extraction controller and expected to pass unchanged. */
    @Test
    void charUnknownBusinessNamesItsField() throws Exception {
        bean.setBusinessId("unknown-biz");
        assertFieldRefused("weblog_bean_businessId");
    }

    /** Characterisation: written against the pre-extraction controller and expected to pass unchanged. */
    @Test
    void charFailingBusinessLookupNamesItsField() throws Exception {
        when(weblogger.businessManager().getBusiness("biz-x"))
                .thenThrow(new WebloggerException("db down"));
        bean.setBusinessId("biz-x");
        assertFieldRefused("weblog_bean_businessId");
    }

    /** Characterisation: written against the pre-extraction controller and expected to pass unchanged. */
    @Test
    void charHotelPlaceTypeNamesItsField() throws Exception {
        bean.setPlaceType("Hotel");
        bean.setPlaceLocality("Lisbon");
        assertFieldRefused("weblog_bean_placeType");
    }

    /** Characterisation: written against the pre-extraction controller and expected to pass unchanged. */
    @Test
    void charPlaceTypeWithBlankLocalityNamesTheLocality() throws Exception {
        bean.setPlaceType("LodgingBusiness");
        bean.setPlaceLocality("");
        assertFieldRefused("weblog_bean_placeLocality");
    }

    /** Characterisation: written against the pre-extraction controller and expected to pass unchanged. */
    @Test
    void charThreeLetterCountryNamesItsField() throws Exception {
        bean.setPlaceCountry("USA");
        assertFieldRefused("weblog_bean_placeCountry");
    }

    /** Characterisation: written against the pre-extraction controller and expected to pass unchanged. */
    @Test
    void charLatitudeOutOfRangeNamesItsField() throws Exception {
        bean.setPlaceLat("91");
        bean.setPlaceLng("0");
        assertFieldRefused("weblog_bean_placeLat");
    }

    /** Characterisation: written against the pre-extraction controller and expected to pass unchanged. */
    @Test
    void charLongitudeWithBlankLatitudeNamesTheLatitude() throws Exception {
        bean.setPlaceLat("");
        bean.setPlaceLng("10");
        assertFieldRefused("weblog_bean_placeLat");
    }

    /** Characterisation: written against the pre-extraction controller and expected to pass unchanged. */
    @Test
    void charLongLocalityNamesItsField() throws Exception {
        bean.setPlaceLocality("l".repeat(129));
        assertFieldRefused("weblog_bean_placeLocality");
    }

    /** Characterisation: written against the pre-extraction controller and expected to pass unchanged. */
    @Test
    void charMailtoBookingUrlNamesItsField() throws Exception {
        bean.setBookingUrl("mailto:x@example.com");
        assertFieldRefused("weblog_bean_bookingUrl");
    }

    /** Characterisation: written against the pre-extraction controller and expected to pass unchanged. */
    @Test
    void charMalformedCustomDomainNamesItsField() throws Exception {
        bean.setCustomDomain("not a host");
        assertFieldRefused("weblog_bean_customDomain");
    }

    /** Characterisation: written against the pre-extraction controller and expected to pass unchanged. */
    @Test
    void charSiteHostCustomDomainNamesItsField() throws Exception {
        givenRuntimeProperty("site.absoluteurl", "https://blog.example.com");
        bean.setCustomDomain("blog.example.com");
        assertFieldRefused("weblog_bean_customDomain");
    }

    /** Characterisation: written against the pre-extraction controller and expected to pass unchanged. */
    @Test
    void charClaimedCustomDomainNamesItsField() throws Exception {
        Weblog other = new Weblog();
        other.setHandle("someoneelse");
        when(weblogger.getWeblogManager().getWeblogByCustomDomain("claimed.example.com"))
                .thenReturn(other);
        bean.setCustomDomain("claimed.example.com");
        assertFieldRefused("weblog_bean_customDomain");
    }

    /** The one intended change of the extraction: a count below one used to be saved. */
    @Test
    void anEntryDisplayCountBelowOneIsRefused() throws Exception {
        bean.setEntryDisplayCount(0);
        assertFieldRefused("weblog_bean_entryDisplayCount");
    }
}
