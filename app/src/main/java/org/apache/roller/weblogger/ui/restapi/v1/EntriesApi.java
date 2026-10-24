package org.apache.roller.weblogger.ui.restapi.v1;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.apache.roller.weblogger.WebloggerException;
import org.apache.roller.weblogger.pojos.Weblog;
import org.apache.roller.weblogger.pojos.WeblogEntry;
import org.apache.roller.weblogger.pojos.WeblogEntrySearchCriteria;
import org.apache.roller.weblogger.pojos.WeblogPermission;
import org.apache.roller.weblogger.ui.controllers.UISecurityEnforced;
import org.apache.roller.weblogger.ui.restapi.ApiException;
import org.apache.roller.weblogger.ui.restapi.Paging;
import org.apache.roller.weblogger.ui.restapi.dto.EntryDtos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Entry reads. {@code UISecurityEnforced} declares
 * {@code WeblogPermission.EDIT_DRAFT} -- reads are open to contributors, not
 * just editors -- so {@code RollerHandlerInterceptor} is what actually
 * authorizes every request here; this controller adds no permission
 * checking of its own. Writes live in a separate controller sharing the
 * same {@code /v1/weblogs/{handle}/entries} path, per the plan's Global
 * Constraint: {@code UISecurityEnforced} declares its required permission
 * per controller, not per method, so splitting EDIT_DRAFT reads from a
 * stricter write permission is what keeps that declaration honest.
 */
@RestController
@RequestMapping("/v1/weblogs/{handle}/entries")
public class EntriesApi extends BaseApiController implements UISecurityEnforced {

    private static final Logger log = LoggerFactory.getLogger(EntriesApi.class);

    /**
     * Requested limits above this are silently capped, not rejected --
     * unlike a limit below 1 or a negative offset, which are rejected with
     * 400 rather than clamped (see the validation at the top of {@link #list}).
     */
    // 200 is the documented cap for every list endpoint (docs/api/README.md, Pagination); the cap is
    // repeated in each controller, so change all of them together.
    private static final int MAX_LIMIT = 200;

    @GetMapping("")
    public EntryDtos.EntryPage list(
            HttpServletRequest request,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "category", required = false) String category,
            @RequestParam(value = "tags", required = false) List<String> tags,
            @RequestParam(value = "text", required = false) String text,
            @RequestParam(value = "locale", required = false) String locale,
            @RequestParam(value = "offset", defaultValue = "0") int offset,
            @RequestParam(value = "limit", defaultValue = "50") int limit) throws WebloggerException {

        // Reject rather than silently clamp: a negative offset reaches JPA's
        // setFirstResult and throws; limit=-1 reaches subList(0, -1) and
        // throws; limit=-2 is worse -- Math.min(-2, MAX_LIMIT) is still -2,
        // so maxResults becomes -1, and JPAWeblogEntryManagerImpl.setFirstMax
        // treats -1 as NO LIMIT, running an unbounded read over every entry
        // in the weblog before it would even reach the subList throw. All
        // three are one query-string character away from any authenticated
        // contributor, and the contract says client-supplied garbage is a
        // 400, not a stack trace.
        Paging.requireRange(limit, offset);

        Weblog weblog = requireActionWeblog(request);
        int boundedLimit = Math.min(limit, MAX_LIMIT);

        WeblogEntrySearchCriteria criteria = new WeblogEntrySearchCriteria();
        criteria.setWeblog(weblog);
        criteria.setCatName(category);
        criteria.setTags(tags);
        criteria.setText(text);
        criteria.setLocale(locale);
        criteria.setOffset(offset);
        // One extra row decides hasMore without a second count query.
        criteria.setMaxResults(boundedLimit + 1);
        if (status != null && !status.isBlank()) {
            criteria.setStatus(EntryDtos.parseFilterStatus(status));
            // The only way to see the trash, and only when asked for by
            // name -- WeblogEntrySearchCriteria.includeTrashed defaults to
            // false, which is the safety property: a caller naming no
            // status at all gets that default untouched.
            criteria.setIncludeTrashed(criteria.getStatus() == WeblogEntry.PubStatus.TRASHED);
        }

        List<WeblogEntry> found = weblogger.getWeblogEntryManager().getWeblogEntries(criteria);
        boolean hasMore = found.size() > boundedLimit;
        List<WeblogEntry> page = hasMore ? found.subList(0, boundedLimit) : found;

        List<EntryDtos.EntryView> items = page.stream()
                .map(entry -> EntryDtos.toView(entry, permalink(entry)))
                .toList();
        return new EntryDtos.EntryPage(items, offset, boundedLimit, hasMore);
    }

    @GetMapping("/{id}")
    public EntryDtos.EntryView get(HttpServletRequest request, @PathVariable("id") String id) {
        WeblogEntry entry = requireEntry(request, id);
        return EntryDtos.toView(entry, permalink(entry));
    }

    /**
     * The same entry, body and refusals as {@link #get}, found by its anchor.
     * An anchor names one entry per weblog, so the lookup is scoped to the
     * action weblog; a miss, another weblog's entry and a failed lookup are
     * all the one 404 an unknown id gets.
     */
    @GetMapping("/by-anchor/{anchor}")
    public EntryDtos.EntryView getByAnchor(HttpServletRequest request,
                                           @PathVariable("anchor") String anchor) {
        WeblogEntry entry = null;
        if (!anchor.isBlank()) {
            try {
                entry = weblogger.getWeblogEntryManager()
                        .getWeblogEntryByAnchor(requireActionWeblog(request), anchor);
            } catch (WebloggerException ex) {
                log.error("Error looking up an entry by anchor", ex);
            }
        }
        if (entry == null || !requireActionWeblog(request).equals(entry.getWebsite())) {
            throw ApiException.notFound("No such entry.");
        }
        return EntryDtos.toView(entry, permalink(entry));
    }

    private String permalink(WeblogEntry entry) {
        return weblogger.getUrlStrategy()
                .getWeblogEntryURL(entry.getWebsite(), null, entry.getAnchor(), true);
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
