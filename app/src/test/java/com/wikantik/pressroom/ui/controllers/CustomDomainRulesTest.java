package com.wikantik.pressroom.ui.controllers;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The one home for custom-domain rules, shared by the JSP editor and the
 * automation API so the two surfaces cannot drift -- the same reason
 * EntryFieldRules exists for entry titles and pubtimes.
 */
class CustomDomainRulesTest {

    // ---------------------------------------------------------- normalise

    @Test
    void normaliseLowercasesAndTrims() {
        assertEquals("vhost.example.com", CustomDomainRules.normalise("  VHost.Example.COM "));
    }

    /** Blank means "no custom domain", which is null, not the empty string. */
    @Test
    void normaliseTurnsBlankIntoNull() {
        assertNull(CustomDomainRules.normalise(""));
        assertNull(CustomDomainRules.normalise("   "));
        assertNull(CustomDomainRules.normalise(null));
    }

    // -------------------------------------------------------- isWellFormed

    @Test
    void ordinaryHostnamesAreWellFormed() {
        assertTrue(CustomDomainRules.isWellFormed("vhost.example.com"));
        assertTrue(CustomDomainRules.isWellFormed("berlin.thelocalwiki.com"));
        assertTrue(CustomDomainRules.isWellFormed("maiiavorobiova.com"));
        assertTrue(CustomDomainRules.isWellFormed("a-b.example.co.uk"));
    }

    /**
     * A single-label name cannot be reached from the public internet, so
     * accepting one would store a value that can never work. Deliberately
     * stricter than the RFC.
     */
    @Test
    void aSingleLabelNameIsRejected() {
        assertFalse(CustomDomainRules.isWellFormed("localhost"));
    }

    @Test
    void junkIsRejected() {
        assertFalse(CustomDomainRules.isWellFormed("not a hostname"));
        assertFalse(CustomDomainRules.isWellFormed("https://vhost.example.com"));
        assertFalse(CustomDomainRules.isWellFormed("vhost.example.com/path"));
        assertFalse(CustomDomainRules.isWellFormed("vhost.example.com:8443"));
        assertFalse(CustomDomainRules.isWellFormed("-lead.example.com"));
        assertFalse(CustomDomainRules.isWellFormed("trail-.example.com"));
        assertFalse(CustomDomainRules.isWellFormed("under_score.example.com"));
    }

    // ------------------------------------------------- isOutsideCertZones

    @Test
    void aHostInsideAConfiguredZoneIsNotOutside() {
        assertFalse(CustomDomainRules.isOutsideCertZones(
                "berlin.thelocalwiki.com", "thelocalwiki.com"));
    }

    @Test
    void aHostInAnyOfSeveralZonesIsNotOutside() {
        assertFalse(CustomDomainRules.isOutsideCertZones(
                "berlin.thelocalwiki.com", "example.com, thelocalwiki.com"));
    }

    @Test
    void anApexOrForeignHostIsOutside() {
        assertTrue(CustomDomainRules.isOutsideCertZones(
                "maiiavorobiova.com", "thelocalwiki.com"));
        assertTrue(CustomDomainRules.isOutsideCertZones(
                "berlin.otherwiki.com", "thelocalwiki.com"));
    }

    /**
     * A wildcard covers ONE label. *.thelocalwiki.com does not cover
     * a.b.thelocalwiki.com, so a deeper name must still warn -- otherwise the
     * warning silently misses the case most likely to surprise someone.
     */
    @Test
    void aDeeperSubdomainIsOutsideBecauseAWildcardCoversOneLabel() {
        assertTrue(CustomDomainRules.isOutsideCertZones(
                "a.b.thelocalwiki.com", "thelocalwiki.com"));
    }

    /** The zone apex itself is not covered by *.zone either. */
    @Test
    void theZoneApexItselfIsOutside() {
        assertTrue(CustomDomainRules.isOutsideCertZones(
                "thelocalwiki.com", "thelocalwiki.com"));
    }

    /** No zones configured means warn about nothing. */
    @Test
    void noConfiguredZonesWarnsAboutNothing() {
        assertFalse(CustomDomainRules.isOutsideCertZones("anything.example.com", ""));
        assertFalse(CustomDomainRules.isOutsideCertZones("anything.example.com", null));
    }

    // ------------------------------------------------------- isSiteHost

    @Test
    void aHostMatchingTheSiteAbsoluteUrlIsTheSiteHost() {
        assertTrue(CustomDomainRules.isSiteHost(
                "blog.example.com", "https://blog.example.com"));
    }

    /** Scheme, port and any path in site.absoluteurl do not matter -- only the host. */
    @Test
    void schemePortAndPathAreIgnoredWhenComparingToTheSiteHost() {
        assertTrue(CustomDomainRules.isSiteHost(
                "blog.example.com", "http://blog.example.com:8080/pressroom"));
    }

    @Test
    void aDifferentHostIsNotTheSiteHost() {
        assertFalse(CustomDomainRules.isSiteHost(
                "vhost.example.com", "https://blog.example.com"));
    }

    @Test
    void aBlankOrUnsetSiteAbsoluteUrlNeverMatches() {
        assertFalse(CustomDomainRules.isSiteHost("blog.example.com", null));
        assertFalse(CustomDomainRules.isSiteHost("blog.example.com", ""));
        assertFalse(CustomDomainRules.isSiteHost("blog.example.com", "   "));
    }

    @Test
    void aNullNormalisedHostNeverMatches() {
        assertFalse(CustomDomainRules.isSiteHost(null, "https://blog.example.com"));
    }

    /** A malformed site.absoluteurl has no host to compare against. */
    @Test
    void aMalformedSiteAbsoluteUrlNeverMatches() {
        assertFalse(CustomDomainRules.isSiteHost("blog.example.com", "not a url"));
    }
}
