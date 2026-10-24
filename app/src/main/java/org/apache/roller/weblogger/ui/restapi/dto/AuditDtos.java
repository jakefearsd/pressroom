package org.apache.roller.weblogger.ui.restapi.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.apache.commons.lang3.StringUtils;
import org.apache.roller.weblogger.pojos.MediaFile;
import org.apache.roller.weblogger.pojos.MediaFileDirectory;
import org.apache.roller.weblogger.pojos.WeblogEntry;

/**
 * Views for the SEO and media audit endpoints.
 *
 * <p>The media side genuinely shares its definition of "missing" with the
 * renderer and the admin UI's own marker: {@link MediaDtos#isAltTextMissing}
 * is called directly by both, keyed off {@code StringUtils.isBlank}.
 *
 * <p>The SEO side ({@link #gapsFor}) is NOT proven to match the renderer --
 * see that method's javadoc for the actual, checked relationship, which is
 * "deliberately stricter than," not "identical to."
 */
public final class AuditDtos {

    private AuditDtos() {
    }

    /** One entry's SEO gaps, and the fields an agent needs to go fix them. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record SeoGap(String entryId, String anchor, String title, List<String> gaps) {
    }

    /**
     * {@code total} and {@code counts} describe every gappy entry the
     * search turned up; {@code entries} is only the requested page -- an
     * agent asking for totals does not have to page through everything to
     * get them.
     */
    public record SeoAudit(int total, Map<String, Integer> counts, List<SeoGap> entries) {
    }

    /**
     * {@code directoryName} is the owning directory's NAME, not its id --
     * deliberately named differently from {@code MediaDtos.MediaView
     * .directory}, which is an id, so the two media-shaped payloads in this
     * API cannot be confused by a client author skimming both schemas and
     * assuming a shared field name means shared semantics. An audit item is
     * a human/agent work-list entry, not a round-trippable write payload
     * (the fix goes through {@code mediaId} via {@code MediaApi.update}),
     * so the readable name is more useful here than an id the caller would
     * have to look up again. Null for a file not filed in any directory.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record MediaGap(String mediaId, String name, String directoryName) {
    }

    public record MediaAudit(int missingAltText, List<MediaGap> items) {
    }

    /**
     * The SEO gaps for a single entry, in a stable order. Every text field
     * check is {@code StringUtils.isBlank} rather than {@code == null} --
     * whitespace is not a description, and this is deliberately the
     * STRICTER of the two possible rules.
     *
     * <p>Entries written through {@link EntryDtos#applyWrite} or the web
     * editor have these fields trimmed and a blank one stored as null, so the
     * renderer (whose {@code $utils.isNotEmpty} is {@code StringUtils.isNotEmpty},
     * not {@code isNotBlank}) and this audit agree for them. A value stored
     * before that, whitespace only, still differs: the renderer treats it as
     * present while this audit reports the gap. {@code isBlank} stays the
     * rule here.
     *
     * <p>{@code noindex} is the one gap that is not a blank-string check: it
     * fires only when the entry is explicitly opted out of search,
     * {@code Boolean.TRUE.equals(...)} rather than a null check, since
     * {@code noindex} defaults to {@code Boolean.FALSE} and null must not be
     * mistaken for "opted out".
     */
    public static List<String> gapsFor(WeblogEntry entry) {
        List<String> gaps = new ArrayList<>();
        if (StringUtils.isBlank(entry.getSearchDescription())) {
            gaps.add("missing_search_description");
        }
        if (StringUtils.isBlank(entry.getMetaTitle())) {
            gaps.add("missing_meta_title");
        }
        if (StringUtils.isBlank(entry.getFeaturedImageId())) {
            gaps.add("missing_featured_image");
        }
        if (Boolean.TRUE.equals(entry.getNoindex())) {
            gaps.add("noindex");
        }
        return gaps;
    }

    public static SeoGap toSeoGap(WeblogEntry entry) {
        return new SeoGap(entry.getId(), entry.getAnchor(), entry.getTitle(), gapsFor(entry));
    }

    public static MediaGap toMediaGap(MediaFile file) {
        MediaFileDirectory directory = file.getDirectory();
        return new MediaGap(file.getId(), file.getName(), directory == null ? null : directory.getName());
    }
}
