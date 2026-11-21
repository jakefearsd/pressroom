package com.wikantik.pressroom.ui.restapi.v1;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.pojos.WeblogEntry;
import com.wikantik.pressroom.pojos.WeblogPermission;
import com.wikantik.pressroom.ui.controllers.EntryListQuery;
import com.wikantik.pressroom.ui.controllers.UISecurityEnforced;
import com.wikantik.pressroom.ui.restapi.ApiException;
import com.wikantik.pressroom.ui.restapi.ProblemCode;
import com.wikantik.pressroom.ui.restapi.Paging;
import com.wikantik.pressroom.ui.restapi.dto.EntryDtos;
import com.wikantik.pressroom.ui.restapi.dto.RevisionDtos;
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
 * just editors -- so {@code PressroomHandlerInterceptor} is what actually
 * authorizes every request here; this controller adds no permission
 * checking of its own. Writes live in a separate controller sharing the
 * same {@code /v1/weblogs/{handle}/entries} path, per the plan's Global
 * Constraint: {@code UISecurityEnforced} declares its required permission
 * per controller, not per method, so splitting EDIT_DRAFT reads from a
 * stricter write permission is what keeps that declaration honest.
 *
 * <p>Each route takes {@code fields} ({@link EntryFields}), so its Java return
 * type is {@code Object}; the {@code @ApiResponse} keeps openapi.json naming
 * the view (or page) a caller gets without it.
 */
@Tag(name = "Entries", description = "Reading a weblog's entries and their revisions.")
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

    @Operation(operationId = "entriesList")
    @GetMapping("")
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = EntryDtos.EntryPage.class)))
    public Object list(
            HttpServletRequest request,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "category", required = false) String category,
            @RequestParam(value = "tags", required = false) List<String> tags,
            @RequestParam(value = "text", required = false) String text,
            @RequestParam(value = "locale", required = false) String locale,
            @RequestParam(value = "offset", defaultValue = "0") int offset,
            @RequestParam(value = "limit", defaultValue = "50") int limit,
            @Parameter(description = EntryFields.DESCRIPTION)
            @RequestParam(value = "fields", required = false) List<String> fields) throws WebloggerException {

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
        EntryFields shown = EntryFields.of(fields);

        Weblog weblog = requireActionWeblog(request);
        int boundedLimit = Math.min(limit, MAX_LIMIT);

        // The strict parse: an unknown status is a 400 here, where the JSP list
        // treats one as no filter. A blank one is no filter on both.
        EntryListQuery.EntryListPage found = EntryListQuery.run(weblogger.getWeblogEntryManager(), weblog,
                new EntryListQuery.Filters(status == null || status.isBlank() ? null
                        : EntryDtos.parseFilterStatus(status), category, tags, text, locale, null, null, null),
                offset, boundedLimit);

        List<EntryDtos.EntryView> items = found.items().stream()
                .map(entry -> EntryDtos.toView(entry, permalink(entry)))
                .toList();
        return shown.project(new EntryDtos.EntryPage(items, found.offset(), found.limit(), found.hasMore()));
    }

    @Operation(operationId = "entriesGet")
    @GetMapping("/{id}")
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = EntryDtos.EntryView.class)))
    public Object get(HttpServletRequest request, @PathVariable("id") String id,
                      @Parameter(description = EntryFields.DESCRIPTION)
                      @RequestParam(value = "fields", required = false) List<String> fields) {
        EntryFields shown = EntryFields.of(fields);
        WeblogEntry entry = requireEntry(request, id);
        return shown.project(EntryDtos.toView(entry, permalink(entry)));
    }

    /**
     * The same entry, body and refusals as {@link #get}, found by its anchor.
     * An anchor names one entry per weblog, so the lookup is scoped to the
     * action weblog; a miss, another weblog's entry and a failed lookup are
     * all the one 404 an unknown id gets.
     */
    @Operation(operationId = "entriesGetByAnchor")
    @GetMapping("/by-anchor/{anchor}")
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = EntryDtos.EntryView.class)))
    public Object getByAnchor(HttpServletRequest request,
                              @PathVariable("anchor") String anchor,
                              @Parameter(description = EntryFields.DESCRIPTION)
                              @RequestParam(value = "fields", required = false) List<String> fields) {
        EntryFields shown = EntryFields.of(fields);
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
            throw ApiException.problem(ProblemCode.ENTRY_NOT_FOUND);
        }
        return shown.project(EntryDtos.toView(entry, permalink(entry)));
    }

    /**
     * The entry's revisions, newest first (spec 2026-10-24, section 5): each
     * holds the content one save replaced, so the first is what undoing the
     * latest save would bring back. No text, so a long history stays a short
     * answer; {@link #revision} has it. As readable as the entry itself, a
     * trashed one included, as {@link #get} is.
     */
    @Operation(operationId = "entriesListRevisions")
    @GetMapping("/{id}/revisions")
    public List<RevisionDtos.RevisionSummary> revisions(HttpServletRequest request, @PathVariable("id") String id)
            throws WebloggerException {
        WeblogEntry entry = requireEntry(request, id);
        return weblogger.getWeblogEntryManager().getRevisions(entry).stream()
                .map(RevisionDtos::toSummary)
                .toList();
    }

    /**
     * One revision with its content and a unified diff from its text to the
     * entry's current text, or to another revision of the same entry named
     * by {@code against}. Both ids are checked against the entry: another
     * entry's revision is the 404 an unknown id gets.
     */
    @Operation(operationId = "entriesGetRevision")
    @GetMapping("/{id}/revisions/{revisionId}")
    public RevisionDtos.RevisionView revision(
            HttpServletRequest request, @PathVariable("id") String id,
            @PathVariable("revisionId") String revisionId,
            @Parameter(description = "Another revision of the same entry to diff against, in place of the "
                    + "entry's current text.")
            @RequestParam(value = "against", required = false) String against) {
        return entryContent().diff(requireEntry(request, id), revisionId, against);
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
