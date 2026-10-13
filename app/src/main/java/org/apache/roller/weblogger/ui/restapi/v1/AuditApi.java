package org.apache.roller.weblogger.ui.restapi.v1;

import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.roller.weblogger.WebloggerException;
import org.apache.roller.weblogger.pojos.MediaFile;
import org.apache.roller.weblogger.pojos.MediaFileFilter;
import org.apache.roller.weblogger.pojos.Weblog;
import org.apache.roller.weblogger.pojos.WeblogEntry;
import org.apache.roller.weblogger.pojos.WeblogEntrySearchCriteria;
import org.apache.roller.weblogger.pojos.WeblogPermission;
import org.apache.roller.weblogger.ui.controllers.UISecurityEnforced;
import org.apache.roller.weblogger.ui.restapi.ApiException;
import org.apache.roller.weblogger.ui.restapi.dto.AuditDtos;
import org.apache.roller.weblogger.ui.restapi.dto.EntryDtos;
import org.apache.roller.weblogger.ui.restapi.dto.MediaDtos;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The "agentic SEO" pair: turns a vague goal ("improve SEO", "describe your
 * images") into a work list an agent can iterate -- find what is undescribed,
 * fix it, re-run, confirm the count dropped. {@code UISecurityEnforced}
 * declares {@code WeblogPermission.EDIT_DRAFT} -- auditing is a read, the
 * same level {@code EntriesApi}'s reads are open to -- and
 * {@code RollerHandlerInterceptor} is what actually enforces it; this
 * controller adds no permission checking of its own.
 */
@RestController
@RequestMapping("/v1/weblogs/{handle}/audit")
public class AuditApi extends BaseApiController implements UISecurityEnforced {

    /** Same cap EntriesApi.list uses for its own offset/limit convention. */
    // 200 is the documented cap for every list endpoint (docs/api/README.md, Pagination); the cap is
    // repeated in each controller, so change all of them together.
    private static final int MAX_LIMIT = 200;

    /**
     * Entries whose {@link AuditDtos#gapsFor} is non-empty, over
     * {@code PUBLISHED} entries by default -- an SEO audit is about what a
     * reader can find, so widening it needs an explicit {@code ?status=}.
     *
     * <p><b>{@code status=TRASHED} is refused with a 400, unlike
     * {@code EntriesApi.list}'s otherwise-identical status filter.</b> That
     * sibling is a general browsing/management endpoint where seeing the
     * trash is legitimate (to restore or purge it). This endpoint is a
     * to-do-list generator meant to be iterated -- find a gap, fix it,
     * re-run, confirm the count dropped -- and handing that loop a trashed
     * entry framed as "needs missing_meta_title fixed" invites writing
     * metadata onto a deleted entry, the side-door-resurrection class of bug
     * this repo has already firefought once (see CLAUDE.md's Trash
     * section). The refusal is explicit rather than a silent
     * {@code includeTrashed=false} override, because silently ignoring a
     * parameter the client asked for is its own trap. A caller who asks for
     * nothing at all gets the safe {@code PUBLISHED} default regardless.
     *
     * <p>{@code total}/{@code counts} are computed over every gappy entry
     * the search returns, not just the requested page -- an agent asking
     * for the shape of the problem should not have to page through
     * everything to see it. Only {@code entries} is sliced by
     * {@code offset}/{@code limit}.
     */
    @GetMapping("/seo")
    public AuditDtos.SeoAudit seo(
            HttpServletRequest request,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "offset", defaultValue = "0") int offset,
            @RequestParam(value = "limit", defaultValue = "50") int limit) throws WebloggerException {

        // Same guard as EntriesApi.list, for the same reason: reject before
        // doing any work, rather than let Math.min(limit, MAX) let a
        // negative limit through as "no limit" at all. An audit walking an
        // entire weblog is exactly where an unbounded read hurts most.
        if (limit < 1 || offset < 0) {
            throw ApiException.badRequest(
                    "limit must be at least 1 and offset must not be negative.");
        }

        Weblog weblog = requireActionWeblog(request);
        int boundedLimit = Math.min(limit, MAX_LIMIT);

        WeblogEntry.PubStatus filterStatus = (status == null || status.isBlank())
                ? WeblogEntry.PubStatus.PUBLISHED
                : EntryDtos.parseFilterStatus(status);
        if (filterStatus == WeblogEntry.PubStatus.TRASHED) {
            // Unlike EntriesApi.list, this endpoint never widens to the
            // trash -- see the javadoc above for why. Refused before any
            // criteria is built or the manager is called.
            throw ApiException.badRequest(
                    "The SEO audit does not cover trashed entries; status=TRASHED is not accepted here.");
        }

        WeblogEntrySearchCriteria criteria = new WeblogEntrySearchCriteria();
        criteria.setWeblog(weblog);
        criteria.setStatus(filterStatus);
        // Unreachable false: filterStatus can never be TRASHED past the
        // guard above, but this stays explicit (rather than omitted) so a
        // future change to the guard cannot silently flip the default this
        // relies on -- includeTrashed's own default is false regardless.
        criteria.setIncludeTrashed(false);

        List<WeblogEntry> found = weblogger.getWeblogEntryManager().getWeblogEntries(criteria);

        Map<String, Integer> counts = new LinkedHashMap<>();
        counts.put("missing_search_description", 0);
        counts.put("missing_meta_title", 0);
        counts.put("missing_featured_image", 0);
        counts.put("noindex", 0);

        List<AuditDtos.SeoGap> gappy = new ArrayList<>();
        for (WeblogEntry entry : found) {
            List<String> gaps = AuditDtos.gapsFor(entry);
            if (gaps.isEmpty()) {
                continue;
            }
            for (String gap : gaps) {
                counts.merge(gap, 1, Integer::sum);
            }
            gappy.add(new AuditDtos.SeoGap(entry.getId(), entry.getAnchor(), entry.getTitle(), gaps));
        }

        int total = gappy.size();
        List<AuditDtos.SeoGap> page = offset >= total
                ? List.of()
                : gappy.subList(offset, Math.min(offset + boundedLimit, total));

        return new AuditDtos.SeoAudit(total, counts, page);
    }

    /**
     * Every media file in the weblog where {@link MediaDtos#isAltTextMissing}
     * is true -- the exact same definition {@code MediaApi} and the admin
     * UI's own marker use, so this list never disagrees with what a reader
     * actually sees rendered.
     */
    @GetMapping("/media")
    public AuditDtos.MediaAudit media(HttpServletRequest request) throws WebloggerException {
        Weblog weblog = requireActionWeblog(request);

        List<MediaFile> files = weblogger.getMediaFileManager()
                .searchMediaFiles(weblog, new MediaFileFilter());

        List<AuditDtos.MediaGap> items = files.stream()
                .filter(MediaDtos::isAltTextMissing)
                .map(AuditDtos::toMediaGap)
                .toList();

        return new AuditDtos.MediaAudit(items.size(), items);
    }

    @Override
    public boolean isUserRequired() {
        return true;
    }

    @Override
    public boolean isWeblogRequired() {
        return true;
    }

    @Override
    public List<String> requiredWeblogPermissionActions() {
        return List.of(WeblogPermission.EDIT_DRAFT);
    }

    @Override
    public List<String> requiredGlobalPermissionActions() {
        return List.of();
    }
}
