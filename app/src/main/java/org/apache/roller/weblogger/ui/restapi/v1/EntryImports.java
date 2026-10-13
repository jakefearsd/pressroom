package org.apache.roller.weblogger.ui.restapi.v1;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.apache.roller.weblogger.WebloggerException;
import org.apache.roller.weblogger.business.ExternalImages;
import org.apache.roller.weblogger.business.Weblogger;
import org.apache.roller.weblogger.business.fetch.RemoteImageFetcher;
import org.apache.roller.weblogger.config.WebloggerRuntimeConfig;
import org.apache.roller.weblogger.pojos.MediaFile;
import org.apache.roller.weblogger.pojos.MediaFileDirectory;
import org.apache.roller.weblogger.pojos.Weblog;
import org.apache.roller.weblogger.ui.controllers.MediaUploads;
import org.apache.roller.weblogger.ui.restapi.ApiException;
import org.apache.roller.weblogger.ui.restapi.ColumnLimits;
import org.apache.roller.weblogger.ui.restapi.FieldErrors;
import org.apache.roller.weblogger.ui.restapi.MediaImports;
import org.apache.roller.weblogger.ui.restapi.dto.EntryDtos;

/**
 * The import step of an entry write: {@code featuredImageUrl} and {@code
 * importExternalImages}. {@link #plan} checks everything that can be checked
 * without the network, alongside the write's other field checks, so a write
 * refused for a typo fetches nothing. {@link #run} imports in order -- the
 * featured image, then the body's images by first appearance -- each URL once,
 * and is all or nothing for the entry: any image refused, or not fetched
 * within the budget, refuses the write naming every such image. Images that
 * did import stay in the library (each import commits); a retry matches them
 * by content hash, so it costs downloads, not copies.
 */
final class EntryImports {

    /** Distinct external images one write may import. */
    static final int MAX_BODY_IMAGES = 10;
    /**
     * The import step stops starting fetches this long after it began. Each
     * fetch already has its own 30-second deadline; Cloudflare ends a request
     * at 100 seconds, and the write still has to save after the last fetch.
     */
    static final Duration BUDGET = Duration.ofSeconds(60);

    /** What the import step will do, worked out before anything is fetched. */
    record Plan(String featuredUrl, String featuredAlt, String text, ExternalImages.Scan scan) {
        static final Plan NONE = new Plan(null, null, null, null);

        boolean isEmpty() {
            return featuredUrl == null && (scan == null || scan.refs().isEmpty());
        }
    }

    private record Wanted(String url, String alt, String field) {
    }

    private final Weblogger weblogger;
    private final RemoteImageFetcher fetcher;
    private final Clock clock;

    EntryImports(Weblogger weblogger, RemoteImageFetcher fetcher, Clock clock) {
        this.weblogger = weblogger;
        this.fetcher = fetcher;
        this.clock = clock;
    }

    /**
     * Checks a write's import fields into {@code errors} and returns what to
     * import. The uploads switch and the site's own hosts are read only when
     * the write asks for an import. {@code storedText} is the entry's current
     * text, scanned when an update asks for an import without sending text.
     */
    Plan plan(FieldErrors errors, Weblog weblog, EntryDtos.EntryWrite body, Set<String> cleared, String storedText) {
        boolean importBody = Boolean.TRUE.equals(body.importExternalImages());
        String featuredUrl = body.featuredImageUrl();
        if (body.featuredImageAlt() != null) {
            if (featuredUrl == null) {
                errors.add("featuredImageAlt", "featuredImageAlt applies only with featuredImageUrl.");
            }
            errors.check("featuredImageAlt", () -> ColumnLimits.requireMaxLength(
                    "featuredImageAlt", body.featuredImageAlt(), ColumnLimits.MEDIA_ALT_TEXT));
        }
        if (featuredUrl == null && !importBody) {
            return Plan.NONE;
        }
        boolean uploads = WebloggerRuntimeConfig.getBooleanProperty("uploads.enabled");
        if (featuredUrl != null) {
            if (featuredUrl.isBlank()) {
                errors.add("featuredImageUrl", "featuredImageUrl cannot be blank.");
            }
            if (body.featuredImageId() != null) {
                errors.add("featuredImageUrl", "Give featuredImageUrl or featuredImageId, not both.");
            }
            if (cleared.contains("featuredImageId")) {
                errors.add("featuredImageUrl",
                        "featuredImageUrl sets the featured image; do not also clear featuredImageId.");
            }
            if (!uploads) {
                errors.add("featuredImageUrl", "File upload is disabled for this site.");
            }
        }
        if (!importBody) {
            return new Plan(featuredUrl, body.featuredImageAlt(), null, null);
        }
        if (!uploads) {
            errors.add("importExternalImages", "File upload is disabled for this site.");
        }
        String text = body.text() != null ? body.text() : storedText;
        ExternalImages.Scan scan = ExternalImages.scan(text, ownHosts(weblog));
        scan.refusals().forEach(refusal -> errors.add("text", refusal));
        int distinct = scan.distinctUrls().size();
        if (distinct > MAX_BODY_IMAGES) {
            errors.add("text", "The text loads " + distinct + " images from other sites; at most "
                    + MAX_BODY_IMAGES + " are imported in one write.");
        }
        return new Plan(featuredUrl, body.featuredImageAlt(), text, scan);
    }

    /**
     * Runs a plan {@link #plan} passed and returns the write to apply in
     * place of {@code body}. Refuses (400) when the default directory is
     * private, and with one error per image that failed.
     */
    EntryDtos.EntryWrite run(Plan plan, Weblog weblog, EntryDtos.EntryWrite body) throws WebloggerException {
        if (plan.isEmpty()) {
            return body;
        }
        String field = plan.featuredUrl() != null ? "featuredImageUrl" : "text";
        MediaFileDirectory directory = MediaUploads.defaultDirectory(weblog, weblogger.getMediaFileManager());
        if (directory.isPrivate()) {
            throw ApiException.invalidField(field,
                    "The weblog's default media directory is private, so readers could not see imported images.");
        }
        Instant started = clock.instant();
        Map<String, MediaFile> imported = new LinkedHashMap<>();
        FieldErrors failures = new FieldErrors();
        // Attempts, not successes: a URL that failed as the featured image is
        // not fetched (or reported) again as a body image.
        Set<String> attempted = new HashSet<>();
        for (Wanted wanted : wanted(plan)) {
            if (!attempted.add(wanted.url())) {
                continue;
            }
            if (Duration.between(started, clock.instant()).compareTo(BUDGET) >= 0) {
                failures.add(wanted.field(), "Image " + ExternalImages.abbreviate(wanted.url())
                        + " was not fetched: the import step's " + BUDGET.toSeconds() + "-second budget ran out.");
                continue;
            }
            try {
                imported.put(wanted.url(), MediaImports.importImage(weblogger, fetcher, weblog, directory,
                        wanted.url(), null, wanted.alt()).file());
            } catch (MediaImports.ImportRefused e) {
                failures.add(wanted.field(),
                        "Image " + ExternalImages.abbreviate(wanted.url()) + ": " + e.getMessage());
            }
        }
        failures.throwIfAny();

        String featuredId = plan.featuredUrl() == null ? null : imported.get(plan.featuredUrl()).getId();
        String text = null;
        if (plan.scan() != null && !plan.scan().refs().isEmpty()) {
            Map<String, String> local = new HashMap<>();
            imported.forEach((url, file) ->
                    local.put(url, weblogger.getUrlStrategy().getMediaFileURL(weblog, file.getId(), true)));
            text = ExternalImages.rewrite(plan.text(), plan.scan().refs(), local);
        }
        return body.withImports(featuredId, text);
    }

    /** The featured image first, then each body URL once, with the first alt text it carries. */
    private static List<Wanted> wanted(Plan plan) {
        List<Wanted> wanted = new ArrayList<>();
        if (plan.featuredUrl() != null) {
            wanted.add(new Wanted(plan.featuredUrl(), plan.featuredAlt(), "featuredImageUrl"));
        }
        if (plan.scan() != null) {
            Map<String, String> altByUrl = new LinkedHashMap<>();
            for (ExternalImages.Ref ref : plan.scan().refs()) {
                altByUrl.merge(ref.url(), ref.alt() == null ? "" : ref.alt(),
                        (first, next) -> first.isEmpty() ? next : first);
            }
            altByUrl.forEach((url, alt) -> wanted.add(new Wanted(url, alt.isEmpty() ? null : alt, "text")));
        }
        return wanted;
    }

    /** This weblog's host (a custom domain included) and the site's, lower case. */
    private Set<String> ownHosts(Weblog weblog) {
        Set<String> hosts = new HashSet<>();
        addHost(hosts, weblogger.getUrlStrategy().getWeblogURL(weblog, null, true));
        addHost(hosts, WebloggerRuntimeConfig.getAbsoluteContextURL());
        return hosts;
    }

    private static void addHost(Set<String> hosts, String url) {
        String host = url == null ? null : hostOf(url);
        if (host != null) {
            hosts.add(host.toLowerCase(Locale.ROOT));
        }
    }

    /**
     * The host of a site URL, or null when it names none. An unparseable site
     * URL names no host, so none is added as "ours": an image on it is then
     * fetched, and refused there if it is not public.
     */
    private static String hostOf(String url) {
        try {
            return URI.create(url.trim()).getHost();
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
