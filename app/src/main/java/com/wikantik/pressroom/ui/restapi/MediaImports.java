package com.wikantik.pressroom.ui.restapi;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import org.apache.commons.lang3.StringUtils;
import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.business.ContentHashes;
import com.wikantik.pressroom.business.MediaFileManager;
import com.wikantik.pressroom.business.Weblogger;
import com.wikantik.pressroom.business.fetch.FetchRefusal;
import com.wikantik.pressroom.business.fetch.FetchedImage;
import com.wikantik.pressroom.business.fetch.RemoteImageFetcher;
import com.wikantik.pressroom.pojos.MediaFile;
import com.wikantik.pressroom.pojos.MediaFileDirectory;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.ui.controllers.MediaUploads;
import com.wikantik.pressroom.util.PressroomMessages;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One image import from a public URL, shared by the media import endpoint
 * and the entry writes that import, so they cannot drift on what an import
 * is. The fetched bytes are stored through {@link MediaUploads#store}, the
 * path a browser upload takes, so every upload rule applies. Bytes already in
 * the target directory (matched by content hash) return that file instead of
 * a copy, which makes a retried or re-run publish cost a download, not a
 * duplicate.
 *
 * <p>Each import commits ({@code weblogger.flush()}) before it returns:
 * creating a media file writes its bytes to disk at once, so an import that
 * did not commit would leave a file on disk with no row.
 */
public final class MediaImports {

    private static final Logger IMPORT_LOG = LoggerFactory.getLogger("pressroom.media.import");

    /** The file an import produced or matched; {@code created} is false for a match. */
    public record Imported(MediaFile file, boolean created) {
    }

    /** Why an import produced no file. The message is safe to show the caller. */
    public static final class ImportRefused extends Exception {
        private static final long serialVersionUID = 1L;
        private final String outcome;

        ImportRefused(String outcome, String detail, Throwable cause) {
            super(detail, cause);
            this.outcome = outcome;
        }

        /** The {@code outcome=} word the import log line carried. */
        public String outcome() {
            return outcome;
        }
    }

    private MediaImports() {
    }

    /**
     * Fetches {@code url} into {@code directory}. A file already there with
     * the same bytes is returned unchanged, except that a null alt text takes
     * {@code altText} ({@code ""} is a deliberate "decorative" and is kept).
     * One INFO line on {@code pressroom.media.import} either way.
     */
    public static Imported importImage(Weblogger weblogger, RemoteImageFetcher fetcher, Weblog weblog,
                                       MediaFileDirectory directory, String url, String name, String altText)
            throws ImportRefused, WebloggerException {
        long started = System.nanoTime();
        String outcome = "error";
        long bytes = 0;
        try {
            FetchedImage image = fetcher.fetch(url, MediaUploads.maxFileBytes(), name);
            bytes = image.size();
            MediaFileManager mfm = weblogger.getMediaFileManager();
            MediaFile existing = mfm.getMediaFileByContentHash(directory, ContentHashes.sha256Hex(image.bytes()));
            if (existing != null) {
                if (altText != null && existing.getAltText() == null) {
                    existing.setAltText(altText);
                    mfm.updateMediaFile(weblog, existing);
                }
                weblogger.flush();
                outcome = "matched";
                return new Imported(existing, false);
            }
            PressroomMessages messages = new PressroomMessages();
            MediaUploads.Result stored = MediaUploads.store(
                    MediaUploads.Source.of(image.fileName(), image.kind().contentType(), image.bytes()),
                    weblog, directory, mfm, messages);
            if (stored.file() == null) {
                outcome = stored.status();
                throw new ImportRefused(stored.status(), stored.detail(), null);
            }
            MediaFile file = stored.file();
            if (altText != null) {
                file.setAltText(altText);
                mfm.updateMediaFile(weblog, file);
            }
            weblogger.flush();
            outcome = "created";
            return new Imported(file, true);
        } catch (FetchRefusal e) {
            outcome = "refused";
            throw new ImportRefused(outcome, e.getMessage(), e);
        } finally {
            log(weblog, url, outcome, bytes, started);
        }
    }

    /**
     * The import log line: weblog, host, outcome, bytes, ms. Never the path
     * or query, where a signed URL keeps its credentials.
     */
    public static void log(Weblog weblog, String url, String outcome, long bytes, long startedNanos) {
        IMPORT_LOG.info("weblog={} host={} outcome={} bytes={} ms={}", weblog.getHandle(), hostOf(url),
                outcome, bytes, (System.nanoTime() - startedNanos) / 1_000_000);
    }

    /** The URL's host only, lower-cased; "?" when it has none. URI parsing keeps it to hostname characters. */
    static String hostOf(String url) {
        if (StringUtils.isBlank(url)) {
            return "?";
        }
        try {
            String host = new URI(url.trim()).getHost();
            return host == null ? "?" : host.toLowerCase(Locale.ROOT);
        } catch (URISyntaxException e) {
            return "?";
        }
    }
}
