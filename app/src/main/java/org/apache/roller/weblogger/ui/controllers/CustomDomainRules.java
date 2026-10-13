package org.apache.roller.weblogger.ui.controllers;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.regex.Pattern;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

/**
 * The rules for a weblog's custom domain, in one place because two surfaces
 * apply them -- the JSP Weblog Settings form and the automation API's weblog
 * PATCH. Same reason {@code EntryFieldRules} exists: a rule reimplemented per
 * surface is a rule that drifts.
 *
 * <p>NOTE there are two normalise methods in this wave and they are not
 * interchangeable. {@link org.apache.roller.weblogger.business.VirtualHostRegistry#normalise}
 * cleans an inbound HTTP {@code Host} header, so it also strips a port and a
 * trailing root label. This one cleans a value an author typed into a form
 * before it is stored, where a port or a trailing dot is a validation failure
 * rather than something to quietly remove. Do not merge them.
 *
 * <p>Uniqueness is deliberately NOT here: it needs a database lookup, and this
 * class is pure so it can be tested without one. Callers check it themselves
 * against {@code WeblogManager.getWeblogByCustomDomain}, and the unique index
 * added in V027 is the actual guarantee either way.
 */
@SuppressFBWarnings(
        value = "REDOS",
        justification = "HOSTNAME, this class's one pattern (class level because a suppression cannot sit on "
                + "the static initializer that compiles it): the lookahead refuses anything over 253 "
                + "characters before the labels are tried, and each label must end at a dot, so a label "
                + "boundary is never ambiguous and the work is bounded by that length.")
public final class CustomDomainRules {

    /**
     * A hostname label set, deliberately stricter than the RFC: no
     * underscores, no leading or trailing hyphen, and at least two labels. A
     * single-label name cannot be reached from the public internet, so
     * accepting one would store a value that can never work.
     */
    private static final Pattern HOSTNAME = Pattern.compile(
            "^(?=.{1,253}$)([a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z]{2,63}$");

    private CustomDomainRules() {
    }

    /** Trims and lowercases; blank becomes null, meaning "no custom domain". */
    public static String normalise(String raw) {
        if (raw == null) {
            return null;
        }
        String host = raw.trim().toLowerCase(Locale.ROOT);
        return host.isEmpty() ? null : host;
    }

    /** True when the value is a usable public hostname. Null is not. */
    public static boolean isWellFormed(String normalisedHost) {
        return normalisedHost != null && HOSTNAME.matcher(normalisedHost).matches();
    }

    /**
     * True when no configured wildcard zone covers this host, which is a
     * WARNING and never an error (spec Decision 4) -- hard-validating it would
     * couple the app to the certificate model and make apex-domain support a
     * Roller change instead of a proxy change.
     *
     * <p>A wildcard covers exactly ONE label: {@code *.thelocalwiki.com} covers
     * {@code berlin.thelocalwiki.com} but neither {@code thelocalwiki.com} nor
     * {@code a.b.thelocalwiki.com}. Treating it as a plain suffix match would
     * stay silent on precisely the deeper name most likely to surprise someone.
     *
     * @param zones comma-separated apex names, or null/blank to warn about nothing
     */
    @SuppressFBWarnings(
            value = "IMPROPER_UNICODE",
            justification = "Compares an ASCII-validated host with the operator's certificate zones only "
                    + "to decide whether to show a warning; it allows and refuses nothing.")
    public static boolean isOutsideCertZones(String normalisedHost, String zones) {
        if (normalisedHost == null || zones == null || zones.isBlank()) {
            return false;
        }
        for (String raw : zones.split(",")) {
            String zone = raw.trim().toLowerCase(Locale.ROOT);
            if (zone.isEmpty()) {
                continue;
            }
            String suffix = "." + zone;
            if (normalisedHost.endsWith(suffix)) {
                String label = normalisedHost.substring(
                        0, normalisedHost.length() - suffix.length());
                if (!label.isEmpty() && label.indexOf('.') < 0) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * True when {@code normalisedHost} names the SAME host as the site's own
     * {@code site.absoluteurl} (I4b). Nothing else rejects a weblog claiming
     * the site's own hostname as its custom domain, and once claimed,
     * {@code VirtualHostRegistry.handleFor} resolves that host to the
     * claiming weblog for every request -- including {@code /roller-ui/**},
     * which {@code ControlPlaneHostFilter} then redirects back to the site
     * host it just arrived on once {@code site.absoluteurl} is set,
     * producing an infinite redirect loop on the admin UI with no route back
     * except a manual {@code UPDATE weblog SET custom_domain = NULL}.
     *
     * <p>Compared on host only: scheme, port and any path in {@code
     * siteAbsoluteUrl} are irrelevant to a DNS/{@code Host}-header match, and
     * {@code normalisedHost} (from {@link #normalise}) never carries any of
     * those anyway.
     *
     * @param siteAbsoluteUrl the configured {@code site.absoluteurl} value
     *                        (see {@link org.apache.roller.weblogger.config.WebloggerRuntimeConfig#getPropertyWithConfigFallback}),
     *                        or null/blank when unset -- in which case there
     *                        is no site host to collide with and this
     *                        returns false
     */
    public static boolean isSiteHost(String normalisedHost, String siteAbsoluteUrl) {
        if (normalisedHost == null || siteAbsoluteUrl == null || siteAbsoluteUrl.isBlank()) {
            return false;
        }
        String siteHost = hostOf(siteAbsoluteUrl.trim());
        return normalisedHost.equals(siteHost);
    }

    private static String hostOf(String absoluteUrl) {
        try {
            String host = new URI(absoluteUrl).getHost();
            return host == null ? null : host.toLowerCase(Locale.ROOT);
        } catch (URISyntaxException e) {
            return null;
        }
    }
}
