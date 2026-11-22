package com.wikantik.pressroom.ui.restapi.v1;

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
import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.business.ExternalImages;
import com.wikantik.pressroom.business.URLStrategy;
import com.wikantik.pressroom.business.Weblogger;
import com.wikantik.pressroom.business.fetch.RemoteImageFetcher;
import com.wikantik.pressroom.config.WebloggerRuntimeConfig;
import com.wikantik.pressroom.pojos.MediaFile;
import com.wikantik.pressroom.pojos.MediaFileDirectory;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.ui.controllers.MediaUploads;
import com.wikantik.pressroom.ui.restapi.ApiException;
import com.wikantik.pressroom.ui.restapi.ColumnLimits;
import com.wikantik.pressroom.ui.restapi.FieldErrors;
import com.wikantik.pressroom.ui.restapi.MediaImports;
import com.wikantik.pressroom.ui.restapi.ProblemCode;
import com.wikantik.pressroom.ui.restapi.dto.EntryDtos;

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

    /**
     * What the import step will do, worked out before anything is fetched.
     * {@code defaulted}: the body import runs because the write sent text and
     * left importExternalImages out, not because it asked. A defaulted import
     * is best effort (spec 2026-11-07): a default must never make a write fail
     * that would have saved without it, so a body image it cannot copy stays
     * linked where it was, and only the first {@link #MAX_BODY_IMAGES} are
     * tried. Asked for with true, it stays all or nothing.
     */
    record Plan(String featuredUrl, String featuredAlt, String text, ExternalImages.Scan scan, boolean defaulted) {
        static final Plan NONE = new Plan(null, null, null, null, false);

        boolean isEmpty() {
            return featuredUrl == null && (scan == null || scan.refs().isEmpty());
        }
    }

    private record Wanted(String url, String alt, String field) {
    }

    private final Weblogger weblogger;
    private final RemoteImageFetcher fetcher;
    private final Clock clock;
    /** Ids {@link #run} imported: public images of the weblog, though not yet committed with the entry. */
    private final Set<String> importedIds = new HashSet<>();

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
     * {@code defaultImport} false makes the import a write gets without asking
     * a no-op (a contributor's: adding media is a publisher's, spec 2026-11-21
     * owner decisions, Task 3, fix round 1), so its external images stay linked.
     */
    Plan plan(FieldErrors errors, Weblog weblog, EntryDtos.EntryWrite body, Set<String> cleared, String storedText,
              boolean defaultImport) {
        // Spec 2026-11-07: a write that sends text imports unless it says
        // false. One that sends no text does not, unless asked: a status
        // change must not start fetching. An update's edits arrive here
        // already resolved into text.
        boolean defaulted = defaultImport && body.importExternalImages() == null && body.text() != null;
        boolean importBody = Boolean.TRUE.equals(body.importExternalImages()) || defaulted;
        String featuredUrl = body.featuredImageUrl();
        if (body.featuredImageAlt() != null) {
            checkFeaturedAlt(errors, body);
        }
        if (featuredUrl == null && !importBody) {
            return Plan.NONE;
        }
        boolean uploads = WebloggerRuntimeConfig.getBooleanProperty("uploads.enabled");
        if (defaulted && !uploads) {
            // Not asked for, so a site that takes no uploads saves the text as sent.
            importBody = false;
        }
        if (featuredUrl != null) {
            checkFeaturedUrl(errors, body, cleared, uploads);
        }
        if (!importBody) {
            return featuredUrl == null ? Plan.NONE : new Plan(featuredUrl, body.featuredImageAlt(), null, null, false);
        }
        if (!uploads) {
            errors.add("importExternalImages", ProblemCode.UPLOAD_DISABLED);
        }
        String text = body.text() != null ? body.text() : storedText;
        ExternalImages.Scan scan = ExternalImages.scan(text, ownHosts(weblogger.getUrlStrategy(), weblog));
        if (!defaulted) {
            checkExplicitScan(errors, scan);
        }
        return new Plan(featuredUrl, body.featuredImageAlt(), text, scan, defaulted);
    }

    private static void checkFeaturedAlt(FieldErrors errors, EntryDtos.EntryWrite body) {
        if (body.featuredImageUrl() == null) {
            errors.add("featuredImageAlt", ProblemCode.IMAGE_ALT_WITHOUT_URL);
        }
        errors.check("featuredImageAlt", () -> ColumnLimits.requireMaxLength(
                "featuredImageAlt", body.featuredImageAlt(), ColumnLimits.MEDIA_ALT_TEXT));
    }

    private static void checkFeaturedUrl(FieldErrors errors, EntryDtos.EntryWrite body, Set<String> cleared,
            boolean uploads) {
        if (body.featuredImageUrl().isBlank()) {
            errors.add("featuredImageUrl", ProblemCode.FIELD_BLANK, "featuredImageUrl");
        }
        if (body.featuredImageId() != null) {
            errors.add("featuredImageUrl", ProblemCode.IMAGE_URL_AND_ID);
        }
        if (cleared.contains("featuredImageId")) {
            errors.add("featuredImageUrl", ProblemCode.IMAGE_URL_AND_CLEAR);
        }
        if (!uploads) {
            errors.add("featuredImageUrl", ProblemCode.UPLOAD_DISABLED);
        }
    }

    /** An import the write asked for refuses what it cannot import, and more than the cap. */
    private static void checkExplicitScan(FieldErrors errors, ExternalImages.Scan scan) {
        scan.refusals().forEach(refusal -> errors.add("text", ProblemCode.IMAGE_SCAN_REFUSED, refusal));
        int distinct = scan.distinctUrls().size();
        if (distinct > MAX_BODY_IMAGES) {
            errors.add("text", ProblemCode.IMAGE_TOO_MANY, Integer.toString(distinct), Integer.toString(MAX_BODY_IMAGES));
        }
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
        if (directory.isPrivate() && plan.defaulted() && plan.featuredUrl() == null) {
            // Not asked for: readers could not see the copies, so keep the text as sent.
            return body;
        }
        if (directory.isPrivate()) {
            throw ApiException.problemOnField(field, ProblemCode.IMAGE_DIRECTORY_PRIVATE);
        }
        Map<String, MediaFile> imported = importAll(plan, weblog, directory);
        imported.values().forEach(file -> importedIds.add(file.getId()));

        String featuredId = plan.featuredUrl() == null ? null : imported.get(plan.featuredUrl()).getId();
        String text = null;
        if (plan.scan() != null && !plan.scan().refs().isEmpty()) {
            Map<String, String> local = new HashMap<>();
            Map<String, String> ids = new HashMap<>();
            imported.forEach((url, file) -> {
                local.put(url, weblogger.getUrlStrategy().getMediaFileURL(weblog, file.getId(), true));
                ids.put(url, file.getId());
            });
            text = ExternalImages.rewrite(plan.text(), plan.scan().refs(), local, ids);
        }
        return body.withImports(featuredId, text);
    }

    /**
     * Imports every wanted image within the time budget, by URL. Refuses
     * with one error per image that failed, except a defaulted body image:
     * that import is best effort, and a failure leaves the image linked.
     */
    private Map<String, MediaFile> importAll(Plan plan, Weblog weblog, MediaFileDirectory directory)
            throws WebloggerException {
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
            Failure failure = importOne(wanted, started, weblog, directory, imported);
            boolean mayFail = plan.defaulted() && "text".equals(wanted.field());
            if (failure != null && !mayFail) {
                failures.add(wanted.field(), failure.code(), failure.args().toArray(String[]::new));
            }
        }
        failures.throwIfAny();
        return imported;
    }

    /**
     * Imports one image into {@code imported}. Null when it was imported,
     * else why not, as the problem to report against its field.
     */
    private Failure importOne(Wanted wanted, Instant started, Weblog weblog, MediaFileDirectory directory,
            Map<String, MediaFile> imported) throws WebloggerException {
        if (Duration.between(started, clock.instant()).compareTo(BUDGET) >= 0) {
            return new Failure(ProblemCode.IMAGE_IMPORT_BUDGET,
                    List.of(ExternalImages.abbreviate(wanted.url()), Long.toString(BUDGET.toSeconds())));
        }
        try {
            imported.put(wanted.url(), MediaImports.importImage(weblogger, fetcher, weblog, directory,
                    wanted.url(), null, wanted.alt()).file());
            return null;
        } catch (MediaImports.ImportRefused e) {
            // {1} is the import step's own text, passed through whole, as ImportRefused states it.
            return new Failure(ProblemCode.IMAGE_IMPORT_FAILED,
                    List.of(ExternalImages.abbreviate(wanted.url()), e.getMessage()));
        }
    }

    /** Why one image was not imported: the problem to report against its field. */
    private record Failure(ProblemCode code, List<String> args) {
    }

    /** Ids of the files {@link #run} imported, for the featured-image default. */
    Set<String> importedIds() {
        return Set.copyOf(importedIds);
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
            // Explicit imports refused more than the cap in plan(); a defaulted
            // one tries the first MAX_BODY_IMAGES and leaves the rest linked.
            altByUrl.entrySet().stream().limit(MAX_BODY_IMAGES).forEach(e ->
                    wanted.add(new Wanted(e.getKey(), e.getValue().isEmpty() ? null : e.getValue(), "text")));
        }
        return wanted;
    }

    /**
     * This weblog's host (a custom domain included) and the site's, lower
     * case. Shared with the SEO checks ({@code SeoBodies}), so the image the
     * hot-link warning names is the image a save would import.
     */
    static Set<String> ownHosts(URLStrategy urls, Weblog weblog) {
        Set<String> hosts = new HashSet<>();
        addHost(hosts, urls.getWeblogURL(weblog, null, true));
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
