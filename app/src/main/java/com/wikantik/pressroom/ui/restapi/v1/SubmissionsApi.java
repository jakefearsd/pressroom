package com.wikantik.pressroom.ui.restapi.v1;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.business.FormSubmissionManager;
import com.wikantik.pressroom.pojos.FormSubmission;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.pojos.WeblogPermission;
import com.wikantik.pressroom.ui.controllers.UISecurityEnforced;
import com.wikantik.pressroom.ui.controllers.WeblogOwnership;
import com.wikantik.pressroom.ui.restapi.ApiException;
import com.wikantik.pressroom.ui.restapi.Paging;
import com.wikantik.pressroom.ui.restapi.ProblemCode;
import com.wikantik.pressroom.ui.restapi.auth.SessionOnly;
import com.wikantik.pressroom.ui.restapi.dto.SubmissionDtos;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The weblog's contact-form inbox, as its Submissions page shows it: newest first, and one message at a
 * time removed (a bulk removal is the client looping). Session-only (SA-18): readers' personal details and
 * text written by strangers, which must not be handed to a token or an agent. The permission is the
 * page's, {@code POST}.
 */
@Tag(name = "Contact messages", description = "The weblog's contact-form inbox (session only: the admin app, "
        + "never a token).")
@RestController
@RequestMapping("/v1/weblogs/{handle}/submissions")
@SessionOnly
public class SubmissionsApi extends BaseApiController implements UISecurityEnforced {

    /** The Submissions page's own page size, the default here. */
    static final int PAGE_SIZE = 30;

    @Operation(operationId = "submissionsList", summary = "The contact-form messages, newest first.")
    @GetMapping("")
    public SubmissionDtos.SubmissionPage list(HttpServletRequest request,
            @Parameter(description = "How many messages to skip. Default 0.")
            @RequestParam(value = "offset", defaultValue = "0") int offset,
            @Parameter(description = "The page size: default 30, at most 200.")
            @RequestParam(value = "limit", defaultValue = "" + PAGE_SIZE) int limit) throws WebloggerException {
        Paging.requireRange(limit, offset);
        Weblog weblog = requireActionWeblog(request);
        int bounded = Math.min(limit, Paging.MAX_LIMIT);
        FormSubmissionManager submissions = weblogger.getFormSubmissionManager();
        // One row past the page decides hasMore.
        List<FormSubmission> rows = submissions.getSubmissions(weblog, offset, bounded + 1);
        int total = submissions.getCount(weblog);
        List<SubmissionDtos.SubmissionView> items = rows.subList(0, Math.min(rows.size(), bounded)).stream()
                .map(SubmissionDtos::toView).toList();
        return new SubmissionDtos.SubmissionPage(items, offset, bounded, rows.size() > bounded, total);
    }

    @Operation(operationId = "submissionsMarkRead",
            summary = "Mark contact-form messages read, for every member of the weblog.")
    @ApiResponse(responseCode = "204", description = "Done; no body. Ids that are not this weblog's are ignored.")
    @PostMapping("/read")
    public ResponseEntity<Void> markRead(HttpServletRequest request, @RequestBody SubmissionDtos.MarkRead body)
            throws WebloggerException {
        Weblog weblog = requireActionWeblog(request);
        if (body == null || body.ids() == null) {
            throw ApiException.problemOnField("ids", ProblemCode.FIELD_REQUIRED, "ids");
        }
        // The inbox marks one page (30) at a time; a longer list is cut rather than refused.
        List<String> ids = body.ids().subList(0, Math.min(body.ids().size(), Paging.MAX_LIMIT));
        weblogger.getFormSubmissionManager().markRead(weblog, ids);
        weblogger.flush();
        return ResponseEntity.noContent().build();
    }

    @Operation(operationId = "submissionsDelete", summary = "Delete one contact-form message.")
    @ApiResponse(responseCode = "204", description = "Done; no body.")
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(HttpServletRequest request, @PathVariable("id") String id)
            throws WebloggerException {
        Weblog weblog = requireActionWeblog(request);
        // A by-id lookup is global: a message sent to another weblog reads as absent.
        FormSubmission submission = WeblogOwnership.formSubmission(weblogger, id, weblog);
        if (submission == null) {
            throw ApiException.problem(ProblemCode.SUBMISSION_NOT_FOUND);
        }
        weblogger.getFormSubmissionManager().remove(submission);
        weblogger.flush();
        return ResponseEntity.noContent().build();
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
        return List.of(com.wikantik.pressroom.pojos.GlobalPermission.LOGIN);
    }
}
