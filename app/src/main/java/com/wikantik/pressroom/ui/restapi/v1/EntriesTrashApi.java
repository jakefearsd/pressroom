package com.wikantik.pressroom.ui.restapi.v1;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.pojos.WeblogEntry;
import com.wikantik.pressroom.pojos.WeblogPermission;
import com.wikantik.pressroom.ui.controllers.EntryDeletion;
import com.wikantik.pressroom.ui.controllers.UISecurityEnforced;
import com.wikantik.pressroom.ui.restapi.ApiException;
import com.wikantik.pressroom.ui.restapi.ProblemCode;
import com.wikantik.pressroom.ui.restapi.dto.EntryDtos;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * An entry in the trash: bring it back, or delete it forever. These take
 * {@code POST} on the weblog, as the Trash screen
 * does, while every other entry write ({@link EntriesWriteApi}) takes {@code
 * EDIT_DRAFT}, as the entry editor does, and holds a contributor to the
 * editor's rules in the handler (spec 2026-11-21 owner decisions, Task 3).
 * {@code UISecurityEnforced} declares a permission per controller, not per
 * method, so the two levels are two controllers on one path, and {@code
 * PressroomHandlerInterceptor} refuses a contributor here as it does on the
 * Trash page.
 */
@Tag(name = "Entry trash", description = "An entry in the trash: restore it as a draft, or delete it forever. "
        + "Needs publish rights, as the Trash page does.")
@RestController
@RequestMapping("/v1/weblogs/{handle}/entries")
public class EntriesTrashApi extends BaseApiController implements UISecurityEnforced {

    /**
     * Brings a trashed entry back -- always as a DRAFT, never back to
     * PUBLISHED. {@code WeblogEntryManager.restoreWeblogEntry} enforces that
     * itself and remembers no pre-trash status to restore instead, precisely
     * so an undelete can never silently republish to feeds, the sitemap and
     * every subscriber.
     *
     * <p>Restoring an entry that is not currently trashed is refused with
     * 409: {@code restoreWeblogEntry} has no status guard of its own, and
     * calling it on a live PUBLISHED entry would silently unpublish it to
     * DRAFT -- exactly the side-door resurrection/demotion hazard
     * the not-in-trash check exists to close off.
     */
    @Operation(operationId = "entriesRestore")
    @PostMapping("/{id}/restore")
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = EntryDtos.EntryView.class)))
    public Object restore(HttpServletRequest request,
                          @PathVariable("id") String id,
                          @Parameter(description = EntryFields.DESCRIPTION)
                          @RequestParam(value = "fields", required = false) List<String> fields)
            throws WebloggerException {
        EntryFields shown = EntryFields.of(fields);
        WeblogEntry entry = requireEntry(request, id);
        if (entry.getStatus() != WeblogEntry.PubStatus.TRASHED) {
            throw ApiException.problem(ProblemCode.ENTRY_NOT_IN_TRASH);
        }
        weblogger.getWeblogEntryManager().restoreWeblogEntry(entry);
        weblogger.flush();
        return shown.project(EntryDtos.toView(entry, permalink(entry)));
    }

    /**
     * Permanently deletes an already-trashed entry. Only reachable from the
     * trash, the same way the Trash screen's row action is --
     * deleting forever is a second, deliberate step after trashing, never a
     * shortcut around it, so a live entry's id here is a 409 rather than an
     * undocumented hard-delete endpoint.
     */
    @Operation(operationId = "entriesDeleteForever")
    @ApiResponse(responseCode = "204", description = "Done; no body.")
    @PostMapping("/{id}/delete-forever")
    public ResponseEntity<Void> deleteForever(HttpServletRequest request,
                                              @PathVariable("id") String id) throws WebloggerException {
        WeblogEntry entry = requireEntry(request, id);
        if (entry.getStatus() != WeblogEntry.PubStatus.TRASHED) {
            throw ApiException.problem(ProblemCode.ENTRY_DELETE_NEEDS_TRASH);
        }
        EntryDeletion.deleteEntryForever(weblogger, entry);
        weblogger.flush();
        return ResponseEntity.noContent().build();
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
        return List.of(WeblogPermission.POST);
    }

    @Override
    public List<String> requiredGlobalPermissionActions() {
        return List.of();
    }
}
