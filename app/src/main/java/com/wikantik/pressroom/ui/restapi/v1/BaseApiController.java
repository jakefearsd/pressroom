package com.wikantik.pressroom.ui.restapi.v1;

import org.apache.commons.lang3.StringUtils;
import jakarta.servlet.http.HttpServletRequest;
import com.wikantik.pressroom.business.Weblogger;
import com.wikantik.pressroom.pojos.User;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.pojos.WeblogEntry;
import com.wikantik.pressroom.pojos.WeblogEntryRevision;
import com.wikantik.pressroom.pojos.WeblogPage;
import com.wikantik.pressroom.pojos.WeblogPageRevision;
import com.wikantik.pressroom.ui.controllers.WeblogOwnership;
import com.wikantik.pressroom.ui.restapi.ApiException;
import com.wikantik.pressroom.ui.restapi.dto.AdminDtos;
import com.wikantik.pressroom.ui.restapi.FieldErrors;
import com.wikantik.pressroom.ui.restapi.ProblemCode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import com.wikantik.pressroom.business.RevisionKeepLimitException;
import com.wikantik.pressroom.WebloggerException;

/**
 * Shared plumbing for every {@code /v1/weblogs/{handle}/...} controller.
 *
 * <p>{@code PressroomHandlerInterceptor} has already resolved the {@code
 * {handle}} path variable to a {@link Weblog} and enforced the controller's
 * declared {@code UISecurityEnforced} permission before any handler method
 * here is ever invoked -- see {@link #requireActionWeblog} -- so nothing in
 * this class or its subclasses does permission checking of its own.
 */
public abstract class BaseApiController {

    @Autowired
    @Lazy
    protected Weblogger weblogger;

    /**
     * The weblog this request acts on, resolved by
     * {@code PressroomHandlerInterceptor} from the {@code {handle}} URI
     * template variable and stashed as the {@code actionWeblog} request
     * attribute. Missing only when the handle named no weblog at all --
     * everything downstream of that (does the caller have permission on it)
     * is already enforced by the interceptor before this method runs.
     */
    protected Weblog requireActionWeblog(HttpServletRequest request) {
        Object weblog = request.getAttribute("actionWeblog");
        if (weblog instanceof Weblog w) {
            return w;
        }
        throw ApiException.problem(ProblemCode.WEBLOG_NOT_FOUND);
    }

    /** The weblog as the settings routes write it, with the address readers reach it at. */
    protected AdminDtos.WeblogView weblogView(Weblog weblog) {
        return AdminDtos.toView(weblog, weblogger.getUrlStrategy().getWeblogURL(weblog, null, true));
    }

    /**
     * The value of a parameter that also answers to an older, deprecated
     * name: whichever was sent, and the same value under both is fine. Two
     * different values are a 400 naming the alias, never a silent choice.
     * An empty value counts as not sent ({@code ?directoryId=X&dir=} is
     * X), and when neither name carries a value the answer is null.
     */
    protected static String eitherName(String name, String value, String alias, String aliasValue) {
        if (StringUtils.isBlank(value)) {
            return StringUtils.isBlank(aliasValue) ? null : aliasValue;
        }
        if (StringUtils.isNotBlank(aliasValue) && !aliasValue.equals(value)) {
            throw ApiException.problemOnField(alias, ProblemCode.PARAMETER_ALIAS_CONFLICT, alias, name);
        }
        return value;
    }

    /**
     * An image id field (an entry's featuredImageId and ogImageId, a page's
     * ogImageId) must name an image readers can load: a media file of this
     * weblog, an image, outside every private directory
     * ({@link WeblogOwnership#publicImage}, the JSP editors' rule too).
     * They were only length-checked, so a typo or a foreign id was stored and
     * the post silently had no featured image. A value equal to the stored
     * one is not re-checked, so a resource sent back exactly as it was read
     * never fails on a file deleted since.
     */
    protected void checkImageId(FieldErrors errors, Weblog weblog, String field, String id, String stored) {
        if (id == null || id.equals(stored)) {
            return;
        }
        if (WeblogOwnership.publicImage(weblogger, id, weblog) == null) {
            errors.add(field, ProblemCode.IMAGE_NOT_PUBLIC, field);
        }
    }

    /** The signed-in user the handler interceptor stored, or a 401. */
    protected User requireAuthenticatedUser(HttpServletRequest request) {
        Object user = request.getAttribute("authenticatedUser");
        if (user instanceof User u) {
            return u;
        }
        throw ApiException.problem(ProblemCode.AUTH_NOT_AUTHENTICATED);
    }

    /**
     * The entry with this id, but only when it belongs to the action
     * weblog. Delegates to {@link WeblogOwnership#entry} -- this codebase's
     * one IDOR defense for by-id entry lookups -- and throws 404 rather
     * than returning null, since every caller here wants exactly that.
     */
    protected WeblogEntry requireEntry(HttpServletRequest request, String id) {
        WeblogEntry entry = WeblogOwnership.entry(weblogger, id, requireActionWeblog(request));
        if (entry == null) {
            throw ApiException.problem(ProblemCode.ENTRY_NOT_FOUND);
        }
        return entry;
    }

    /**
     * The entry with this id, but refusing one that is currently TRASHED --
     * the converse of the not-in-trash check the Trash routes make.
     *
     * <p>{@link #requireEntry} carries no status filter, which is exactly
     * right for the three trash-aware endpoints ({@code trash}, {@code
     * restore}, {@code delete-forever}) that must be able to see a TRASHED
     * entry to operate on it -- but wrong for every other write, where it is
     * a side door: a PATCH (or a preview) against a trashed entry's id would
     * otherwise reach {@code saveWeblogEntry} directly, bypassing {@code
     * restore}'s DRAFT-only rule entirely and able to publish a trashed
     * entry in one call while {@code trashedAt} stays populated on the row.
     * Every write or preview endpoint that is not itself trash-aware must
     * call this instead of {@link #requireEntry}.
     */
    protected WeblogEntry requireLiveEntry(HttpServletRequest request, String id) {
        WeblogEntry entry = requireEntry(request, id);
        if (entry.getStatus() == WeblogEntry.PubStatus.TRASHED) {
            throw ApiException.problem(ProblemCode.ENTRY_TRASHED);
        }
        return entry;
    }

    /** A keep or a release of one revision, as its manager does it. */
    @FunctionalInterface
    protected interface KeepChange {
        void apply() throws WebloggerException;
    }

    /**
     * Applies a keep or release and commits it. The one refusal a caller can
     * do something about, an item that already has its ten kept revisions,
     * is a 409 saying so.
     */
    protected void applyKeep(KeepChange change) throws WebloggerException {
        try {
            change.apply();
        } catch (RevisionKeepLimitException e) {
            throw ApiException.problem(ProblemCode.REVISION_TOO_MANY, e, Integer.toString(e.max()));
        }
        weblogger.flush();
    }

    /** An entry's text and revisions: the shared revision guard, diff, restore and keep. */
    protected RevisionedContent<WeblogEntry, WeblogEntryRevision> entryContent() {
        return new EntryContent(weblogger);
    }

    /** A page's text and revisions; see {@link #entryContent}. */
    protected RevisionedContent<WeblogPage, WeblogPageRevision> pageContent() {
        return new PageContent(weblogger);
    }
}
