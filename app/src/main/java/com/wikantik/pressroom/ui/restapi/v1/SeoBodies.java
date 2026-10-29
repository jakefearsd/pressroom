package com.wikantik.pressroom.ui.restapi.v1;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.commons.lang3.StringUtils;
import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.business.URLStrategy;
import com.wikantik.pressroom.business.Weblogger;
import com.wikantik.pressroom.business.seo.EntrySeoChecks;
import com.wikantik.pressroom.business.seo.SeoBody;
import com.wikantik.pressroom.pojos.MediaFile;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.pojos.WeblogEntry;

/**
 * Runs {@link SeoBody}'s checks over an entry's rendered body, looking up
 * the two things only the tier knows: whether a linked entry of this weblog
 * is published, and the file name of a linked media file. Shared by
 * {@code audits seo_entry} and {@code entries preview}, so the two report
 * the same findings for the same body.
 *
 * <p>A lookup that fails propagates: a check that could not run must not
 * read as a check that passed (CLAUDE.md, on log-only catches).
 */
final class SeoBodies {

    private SeoBodies() {
    }

    /**
     * @param entry the entry the body belongs to; a link to its own anchor
     *              is never broken, since it is published with the entry
     */
    static List<EntrySeoChecks.Finding> findings(Weblogger weblogger, Weblog weblog, WeblogEntry entry,
                                                 String html) throws WebloggerException {
        SeoBody body = SeoBody.of(html);
        URLStrategy urls = weblogger.getUrlStrategy();
        List<String> entryPrefixes = prefixes(urls.getWeblogEntryURL(weblog, null, "", false),
                urls.getWeblogEntryURL(weblog, null, "", true));
        return body.findings(new SeoBody.Site(entryPrefixes, published(weblogger, weblog, entry,
                body.entryAnchors(entryPrefixes)), fileNames(weblogger, weblog, body.imageSources())));
    }

    /** The finding messages: what a preview adds to its warnings. */
    static List<String> warnings(Weblogger weblogger, Weblog weblog, WeblogEntry entry, String html)
            throws WebloggerException {
        return findings(weblogger, weblog, entry, html).stream().map(EntrySeoChecks.Finding::message).toList();
    }

    private static Set<String> published(Weblogger weblogger, Weblog weblog, WeblogEntry entry,
                                         Set<String> anchors) throws WebloggerException {
        Set<String> published = new HashSet<>();
        for (String anchor : anchors) {
            if (anchor.equals(entry.getAnchor())) {
                published.add(anchor);
                continue;
            }
            WeblogEntry linked = weblogger.getWeblogEntryManager().getWeblogEntryByAnchor(weblog, anchor);
            if (linked != null && linked.isPublished()) {
                published.add(anchor);
            }
        }
        return published;
    }

    /**
     * The names of this weblog's media files among the image sources, keyed
     * by source. A source names a media file when it is the weblog's media
     * url (as {@code [image]} writes it) followed by an id; another weblog's
     * file is left out, as {@code [image]} leaves it out.
     */
    private static Map<String, String> fileNames(Weblogger weblogger, Weblog weblog, List<String> sources)
            throws WebloggerException {
        URLStrategy urls = weblogger.getUrlStrategy();
        List<String> mediaPrefixes = prefixes(urls.getMediaFileURL(weblog, "", false),
                urls.getMediaFileURL(weblog, "", true));
        Map<String, String> names = new HashMap<>();
        for (String src : sources) {
            String id = mediaId(src, mediaPrefixes);
            if (id == null) {
                continue;
            }
            MediaFile file = weblogger.getMediaFileManager().getMediaFile(id);
            if (file != null && file.getWeblog() != null && weblog.getId().equals(file.getWeblog().getId())) {
                names.put(src, file.getName());
            }
        }
        return names;
    }

    private static String mediaId(String src, List<String> mediaPrefixes) {
        for (String prefix : mediaPrefixes) {
            if (src.startsWith(prefix)) {
                String id = StringUtils.substringBefore(src.substring(prefix.length()), "?");
                return id.isEmpty() || id.contains("/") ? null : id;
            }
        }
        return null;
    }

    /** The urls the strategy built, leaving out any it could not build. */
    private static List<String> prefixes(String relative, String absolute) {
        List<String> prefixes = new ArrayList<>();
        if (StringUtils.isNotEmpty(absolute)) {
            prefixes.add(absolute);
        }
        if (StringUtils.isNotEmpty(relative)) {
            prefixes.add(relative);
        }
        return prefixes;
    }
}
