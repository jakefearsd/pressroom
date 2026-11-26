package com.wikantik.pressroom.ui.restapi.v1;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.List;
import java.util.Locale;
import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.business.ListmonkClient;
import com.wikantik.pressroom.pojos.WeblogEntry;
import com.wikantik.pressroom.pojos.WeblogPermission;
import com.wikantik.pressroom.ui.controllers.EntryDuplication;
import com.wikantik.pressroom.ui.controllers.EntryNewsletter;
import com.wikantik.pressroom.ui.controllers.UISecurityEnforced;
import com.wikantik.pressroom.ui.restapi.ApiException;
import com.wikantik.pressroom.ui.restapi.ProblemCode;
import com.wikantik.pressroom.ui.restapi.ProblemMessages;
import com.wikantik.pressroom.ui.restapi.dto.EntryDtos;
import org.springframework.context.MessageSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/**
 * Two things done to an existing entry that take {@code POST} on the weblog, as the JSP screens that do them
 * do: the entries list's duplicate action ({@code EntriesController}) and the editor's "Send as newsletter"
 * ({@code EntryEditController}, whose own guard is {@code POST} on top of the editor's {@code EDIT_DRAFT}: mailing
 * every subscriber is a bigger blast radius than editing a draft). {@code UISecurityEnforced} declares a permission
 * per controller, so, like {@link EntriesTrashApi}, these are a controller of their own beside {@link
 * EntriesWriteApi}'s {@code EDIT_DRAFT} writes. Each does exactly what its screen does, through the code both
 * call ({@link EntryDuplication}, {@link EntryNewsletter}).
 */
@Tag(name = "Entry actions", description = "Duplicate an entry into a new draft, or send it to the weblog's "
        + "newsletter list. Needs publish rights, as the screens that do them do.")
@RestController
@RequestMapping("/v1/weblogs/{handle}/entries")
public class EntryActionsApi extends BaseApiController implements UISecurityEnforced {

    private static final MessageSource MESSAGES = ProblemMessages.applicationResources();

    /** Built on first use: {@code WebloggerConfig} is not necessarily loaded when Spring makes controllers. */
    private volatile ListmonkClient listmonkClient;

    /**
     * Copies an entry into a new draft titled "Copy of ...": a fresh id, no anchor of its own, none of the
     * original's publication, pin, strand or newsletter state, so nothing about the original changes and nothing
     * new appears on the blog. A trashed entry is a 409 ({@code entry.trashed}), not a way to bring it back as a
     * draft: that is what restore is for.
     */
    @Operation(operationId = "entriesDuplicate")
    @PostMapping("/{id}/duplicate")
    @ApiResponse(responseCode = "201", content = @Content(schema = @Schema(implementation = EntryDtos.EntryView.class)))
    public ResponseEntity<Object> duplicate(
            HttpServletRequest request, @PathVariable("id") String id,
            @Parameter(description = EntryFields.DESCRIPTION)
            @RequestParam(value = "fields", required = false) List<String> fields) throws WebloggerException {
        EntryFields shown = EntryFields.of(fields);
        WeblogEntry original = requireLiveEntry(request, id);
        Locale locale = ProblemMessages.localeFor(request.getHeader(HttpHeaders.ACCEPT_LANGUAGE));
        String title = MESSAGES.getMessage("weblogEdit.duplicateTitle", new Object[] {original.getTitle()}, locale);
        WeblogEntry copy = EntryDuplication.duplicate(weblogger, original, title);

        URI location = ServletUriComponentsBuilder.fromRequestUri(request)
                .replacePath(request.getRequestURI().replaceFirst("/[^/]+/duplicate$", "/{id}"))
                .buildAndExpand(copy.getId())
                .toUri();
        return ResponseEntity.created(location).body(shown.project(EntryDtos.toView(copy, permalink(copy))));
    }

    /**
     * Mails the entry to the weblog's newsletter list as a Listmonk campaign, synchronously, and stamps {@code
     * newsletterSentAt}. Refused, as the editor refuses it, when the entry is not published, has been sent
     * already, the weblog has no list, or the site has no Listmonk credentials (each a 409). A send Listmonk
     * refuses is a 502 with nothing stamped, so a retry is safe; a send that went but could not be recorded is a
     * 409 saying not to send again.
     */
    @Operation(operationId = "entriesSendNewsletter")
    @PostMapping("/{id}/send-newsletter")
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = EntryDtos.EntryView.class)))
    public Object sendNewsletter(
            HttpServletRequest request, @PathVariable("id") String id,
            @Parameter(description = EntryFields.DESCRIPTION)
            @RequestParam(value = "fields", required = false) List<String> fields) {
        EntryFields shown = EntryFields.of(fields);
        WeblogEntry entry = requireEntry(request, id);
        EntryNewsletter.Result result = EntryNewsletter.send(weblogger, this::listmonkClient, entry, permalink(entry));
        switch (result.outcome()) {
            case SENT -> {
                return shown.project(EntryDtos.toView(entry, permalink(entry)));
            }
            case NOT_PUBLISHED -> throw ApiException.problem(ProblemCode.NEWSLETTER_NOT_PUBLISHED);
            case ALREADY_SENT -> throw ApiException.problem(ProblemCode.NEWSLETTER_ALREADY_SENT);
            case NO_LIST -> throw ApiException.problem(ProblemCode.NEWSLETTER_NO_LIST);
            case NOT_CONFIGURED -> throw ApiException.problem(ProblemCode.NEWSLETTER_NOT_CONFIGURED);
            case SEND_FAILED -> throw ApiException.problem(ProblemCode.NEWSLETTER_SEND_FAILED, detail(result));
            case SENT_NOT_RECORDED ->
                    throw ApiException.problem(ProblemCode.NEWSLETTER_SENT_NOT_RECORDED, detail(result));
        }
        throw new IllegalStateException("unreachable: every newsletter outcome is handled");
    }

    /** The failure's own words; a failure with none still has to fill its message's placeholder. */
    private static String detail(EntryNewsletter.Result result) {
        return result.detail() == null ? "" : result.detail();
    }

    /** Package-private so a test can inject a mock without a real Listmonk instance. */
    void setListmonkClient(ListmonkClient client) {
        this.listmonkClient = client;
    }

    ListmonkClient listmonkClient() {
        ListmonkClient client = listmonkClient;
        if (client == null) {
            synchronized (this) {
                client = listmonkClient;
                if (client == null) {
                    client = ListmonkClient.fromConfig();
                    listmonkClient = client;
                }
            }
        }
        return client;
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
