/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  The ASF licenses this file to You
 * under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.  For additional information regarding
 * copyright in this work, please see the NOTICE file in the top level
 * directory of this distribution.
 */

package org.apache.roller.weblogger.ui.controllers.editor;

import java.io.IOException;
import java.sql.Timestamp;
import java.time.format.DateTimeParseException;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.TimeZone;

import jakarta.servlet.http.HttpServletRequest;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.commons.text.StringEscapeUtils;
import org.apache.commons.validator.routines.UrlValidator;
import org.apache.roller.util.DateUtil;
import org.apache.roller.util.RollerConstants;
import org.apache.roller.weblogger.WebloggerException;
import org.apache.roller.weblogger.business.ListmonkClient;
import org.apache.roller.weblogger.business.seo.SeoLimits;
import org.apache.roller.weblogger.business.WeblogEntryManager;
import org.apache.roller.weblogger.business.search.IndexManager;
import org.apache.roller.weblogger.pojos.GlobalPermission;
import org.apache.roller.weblogger.pojos.MediaFile;
import org.apache.roller.weblogger.pojos.ReaderComment;
import org.apache.roller.weblogger.pojos.Weblog;
import org.apache.roller.weblogger.pojos.WeblogCategory;
import org.apache.roller.weblogger.pojos.WeblogEntry;
import org.apache.roller.weblogger.pojos.WeblogEntryRevision;
import org.apache.roller.weblogger.pojos.WeblogEntry.PubStatus;
import org.apache.roller.weblogger.pojos.WeblogPermission;
import org.apache.roller.weblogger.ui.controllers.BaseController;
import org.apache.roller.weblogger.ui.controllers.EntryFieldRules;
import org.apache.roller.weblogger.ui.controllers.WeblogOwnership;
import org.apache.roller.weblogger.util.HTMLSanitizer;
import org.apache.roller.weblogger.util.TextDiff;
import org.apache.roller.weblogger.util.cache.CacheManager;
import org.apache.roller.weblogger.util.MailUtil;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * Edit a new or existing entry.
 */
@Controller
@RequestMapping("/roller-ui/authoring")
public class EntryEditController extends BaseController {

    private static final Logger log = LoggerFactory.getLogger(EntryEditController.class);

    /**
     * Same allowlist as {@code CtaShortcode}'s href check: the SEO card's
     * canonical-URL override is emitted straight into
     * {@code <link rel="canonical">}, {@code og:url} and JSON-LD
     * {@code mainEntityOfPage}, so it must be an absolute http(s) URL or
     * blank -- never a {@code javascript:}/{@code data:}/{@code file:} value.
     */
    private static final UrlValidator CANONICAL_URL_VALIDATOR =
            new UrlValidator(new String[] {"http", "https"});

    /**
     * The Listmonk client used by {@link #entryEditSendNewsletter}, built
     * lazily on first use -- {@code WebloggerConfig} is not necessarily
     * loaded when Spring instantiates controllers, exactly as
     * {@code NewsletterController.listmonkClient} is deferred for the same
     * reason.
     */
    private volatile ListmonkClient listmonkClient;

    @Override
    public List<String> requiredWeblogPermissionActions() {
        return Collections.singletonList(WeblogPermission.EDIT_DRAFT);
    }

    @Override
    public String getDesiredMenu() {
        return "editor";
    }

    // --- entryAdd ---

    @GetMapping("/entryAdd.rol")
    public String entryAddExecute(HttpServletRequest request, Model model,
                                  @ModelAttribute("bean") EntryBean bean) {
        populateCommonModel(request, model);
        model.addAttribute("actionName", "entryAdd");
        model.addAttribute("pageTitle", getText("weblogEdit.title.newEntry", request));

        WeblogEntry entry = new WeblogEntry();
        entry.setCreatorUserName(getAuthenticatedUser(request).getUserName());
        entry.setWebsite(getActionWeblog(request));

        // set weblog defaults
        bean.setLocale(getActionWeblog(request).getLocale());

        model.addAttribute("entry", entry);
        addEntryModelAttributes(request, model, entry, bean);
        return ".EntryEdit";
    }

    @PostMapping("/entryAdd!saveDraft.rol")
    public String entryAddSaveDraft(HttpServletRequest request, Model model,
                                    @ModelAttribute("bean") EntryBean bean) {
        return doEntryAddSave(request, model, bean, "saveDraft");
    }

    @PostMapping("/entryAdd!publish.rol")
    public String entryAddPublish(HttpServletRequest request, Model model,
                                  @ModelAttribute("bean") EntryBean bean) {
        return doEntryAddSave(request, model, bean, "publish");
    }

    private String doEntryAddSave(HttpServletRequest request, Model model, EntryBean bean, String action) {
        populateCommonModel(request, model);
        model.addAttribute("actionName", "entryAdd");
        model.addAttribute("pageTitle", getText("weblogEdit.title.newEntry", request));

        WeblogEntry entry = new WeblogEntry();
        entry.setCreatorUserName(getAuthenticatedUser(request).getUserName());
        entry.setWebsite(getActionWeblog(request));

        Timestamp pubTime = resolvePubTime(bean, request, model);
        resolveEventTimes(bean, request, model);

        if ("saveDraft".equals(action)) {
            bean.setStatus(PubStatus.DRAFT.name());
        } else if ("publish".equals(action)) {
            setPublishStatus(bean, pubTime, request);
        }

        String result = doSave(request, model, bean, entry, "entryAdd", pubTime);
        model.addAttribute("entry", entry);
        addEntryModelAttributes(request, model, entry, bean);
        return result;
    }

    // --- entryEdit ---

    @GetMapping("/entryEdit.rol")
    public String entryEditExecute(HttpServletRequest request, Model model,
                                   @ModelAttribute("bean") EntryBean bean,
                                   RedirectAttributes redirectAttributes) {
        populateCommonModel(request, model);
        model.addAttribute("actionName", "entryEdit");
        model.addAttribute("pageTitle", getText("weblogEdit.title.editEntry", request));

        // A bookmarked editor URL can name an entry that has since been
        // trashed. lookupEntry alone has no status filter and would render
        // the full editor for it -- Save would then resurrect the entry to
        // DRAFT (or straight to PUBLISHED) by a side door that bypasses
        // restore entirely, with trashedAt still populated on the row. Send
        // the author to the trash screen instead, where restoring is the
        // actual, intentional action -- rather than 404ing silently the way
        // an unknown or foreign id does.
        WeblogEntry lookedUp = lookupEntry(bean.getId(), request);
        if (lookedUp != null && lookedUp.getStatus() == PubStatus.TRASHED) {
            addFlashError(redirectAttributes, "entryEdit.entryIsTrashed", request);
            return "redirect:/roller-ui/authoring/trash.rol?weblog="
                    + getActionWeblog(request).getHandle();
        }
        WeblogEntry entry = lookupNonTrashedEntry(bean.getId(), request);
        if (entry == null) {
            return "redirect:/roller-ui/menu.rol";
        }

        bean.copyFrom(entry, request.getLocale());
        model.addAttribute("entry", entry);
        addEntryModelAttributes(request, model, entry, bean);
        return ".EntryEdit";
    }

    /**
     * Renders unsaved editor text exactly as the published page will, and
     * returns the HTML fragment the editor shows in its preview pane.
     *
     * <p>The point is that it runs the <em>real</em> pipeline -- shortcode
     * expansion, then Markdown, then the sanitizer -- rather than a Markdown
     * library in the browser. Only the server can expand {@code [gallery]} or
     * {@code [map]}, and a preview that disagreed with the published page
     * about those would mislead precisely where an author most needs to trust
     * it.
     *
     * <p>The text comes from the request; the <em>entry</em> comes from
     * {@code lookupEntry}, so the weblog-ownership check applies here as it
     * does to every other entry action. A brand-new entry has no id yet and
     * previews against a scratch entry owned by the action weblog, so its
     * shortcodes resolve that weblog's media and nothing is persisted.
     */
    @PostMapping("/entryEdit!preview.rol")
    @ResponseBody
    public ResponseEntity<String> entryEditPreview(HttpServletRequest request,
                                                   @RequestParam(value = "id", required = false) String id,
                                                   @RequestParam(value = "text", required = false) String text) {
        WeblogEntry entry;
        if (id != null && !id.isBlank()) {
            entry = lookupEntry(id, request);
            if (entry == null) {
                return ResponseEntity.notFound().build();
            }
        } else {
            Weblog weblog = getActionWeblog(request);
            if (weblog == null) {
                return ResponseEntity.notFound().build();
            }
            entry = new WeblogEntry();
            entry.setWebsite(weblog);
            entry.setCreatorUserName(getAuthenticatedUser(request).getUserName());
        }

        entry.setText(text == null ? "" : text);
        return ResponseEntity.ok()
                .contentType(MediaType.valueOf("text/html;charset=UTF-8"))
                .body(weblogger.getEntryRenderer().transformedText(entry));
    }

    /**
     * The diff between one revision and the entry as it stands now.
     *
     * <p>Returns a fragment for the editor's revision modal rather than a
     * page. Both ids are client input, so both are checked: the entry through
     * the usual ownership lookup, and the revision by confirming it actually
     * belongs to that entry -- otherwise a revision id from another weblog
     * would render that weblog's unpublished text here.
     */
    @PostMapping("/entryEdit!revisionDiff.rol")
    @ResponseBody
    public ResponseEntity<String> entryEditRevisionDiff(HttpServletRequest request,
                                                        @RequestParam(value = "id", required = false) String id,
                                                        @RequestParam(value = "revisionId",
                                                                required = false)
                                                        String revisionId) {
        WeblogEntry entry = lookupEntry(id, request);
        WeblogEntryRevision revision = lookupRevision(revisionId, entry);
        if (revision == null) {
            return ResponseEntity.notFound().build();
        }

        StringBuilder html = new StringBuilder("<div class=\"revision-diff\">");
        int empty = html.length();
        appendDiff(html, getText("weblogEdit.title", request),
                revision.getTitle(), entry.getTitle());
        appendDiff(html, getText("weblogEdit.summary", request),
                revision.getSummary(), entry.getSummary());
        appendDiff(html, getText("weblogEdit.content", request),
                revision.getText(), entry.getText());
        // Right after a Restore the newest revision often matches the entry
        // exactly; with every section omitted the modal would open blank.
        if (html.length() == empty) {
            appendNoChanges(html, getText("weblogEdit.revisionSame", request));
        }
        html.append("</div>");

        return ResponseEntity.ok()
                .contentType(MediaType.valueOf("text/html;charset=UTF-8"))
                .body(html.toString());
    }

    /** The empty state: a revision identical to the entry as it stands. */
    @SuppressFBWarnings(
            value = "POTENTIAL_XML_INJECTION",
            justification = "The message comes from the bundle and goes through HTMLSanitizer.htmlEncodeTag "
                    + "into element content: with < and > encoded no tag can open there.")
    private static void appendNoChanges(StringBuilder html, String message) {
        html.append("<p class=\"revision-diff-same\">").append(HTMLSanitizer.htmlEncodeTag(message)).append("</p>");
    }

    /**
     * Appends one labelled section of the diff, or nothing at all when that
     * field did not change -- three "no changes" headings would bury the one
     * section the author opened the modal to read.
     */
    @SuppressFBWarnings(
            value = "POTENTIAL_XML_INJECTION",
            justification = "The label and every diff line go through HTMLSanitizer.htmlEncodeTag into "
                    + "element content (p, span inside pre): with < and > encoded no tag can open there. "
                    + "The diff is shown only to the entry's editors.")
    private void appendDiff(StringBuilder html, String label, String before, String after) {
        List<TextDiff.Line> lines = TextDiff.diff(before, after);
        if (lines.isEmpty()) {
            return;
        }
        // A caps label, not a heading: the modal's own title is a <p>, so an
        // <h5> here would be a heading with nothing above it.
        html.append("<p class=\"rail-group-label\">").append(HTMLSanitizer.htmlEncodeTag(label))
                .append("</p><pre>");
        for (TextDiff.Line line : lines) {
            String cssClass = switch (line.kind()) {
                case ADDED -> "diff-added";
                case REMOVED -> "diff-removed";
                case SAME -> "diff-same";
            };
            String marker = switch (line.kind()) {
                case ADDED -> "+ ";
                case REMOVED -> "- ";
                case SAME -> "  ";
            };
            // The revision holds the author's raw Markdown, which may contain
            // any amount of HTML. It is being shown as source, so every
            // character of it is escaped rather than rendered.
            html.append("<span class=\"").append(cssClass).append("\">").append(marker)
                    .append(HTMLSanitizer.htmlEncodeTag(line.text())).append("</span>\n");
        }
        html.append("</pre>");
    }

    /**
     * Puts a revision's content back, through the ordinary save path so the
     * state it replaces becomes a revision of its own. Restoring is therefore
     * undoable by restoring again, and never loses work.
     */
    @PostMapping("/entryEdit!restoreRevision.rol")
    public String entryEditRestoreRevision(HttpServletRequest request,
                                           @RequestParam(value = "bean.id", required = false)
                                           String id,
                                           @RequestParam(value = "revisionId", required = false) String revisionId,
                                           RedirectAttributes redirectAttributes) {
        WeblogEntry entry = lookupEntry(id, request);
        WeblogEntryRevision revision = lookupRevision(revisionId, entry);
        if (revision == null) {
            addFlashError(redirectAttributes, "weblogEntry.notFound", request);
            return "redirect:/roller-ui/authoring/entries.rol?weblog="
                    + getActionWeblog(request).getHandle();
        }

        try {
            entry.setTitle(revision.getTitle());
            entry.setText(revision.getText());
            entry.setSummary(revision.getSummary());
            weblogger.getWeblogEntryManager().saveWeblogEntry(entry, getAuthenticatedUser(request));
            weblogger.flush();

            if (entry.isPublished()) {
                weblogger.getIndexManager().addEntryReIndexOperation(entry);
            }
            CacheManager.invalidate(entry);

            addFlashMessage(redirectAttributes, "weblogEdit.revisionRestored", request);
        } catch (Exception e) {
            log.error("Error restoring revision {}", revisionId, e);
            addFlashError(redirectAttributes, "generic.error.check.logs", request);
        }
        return redirectToEntryEdit(request, entry);
    }

    /**
     * The revision with this id, but only when it belongs to {@code entry}
     * (which the caller has already established they may edit). Null for an
     * unknown id, a foreign one, or a null entry -- indistinguishable to the
     * caller, exactly like {@code lookupEntry}.
     */
    private WeblogEntryRevision lookupRevision(String revisionId, WeblogEntry entry) {
        // The one ownership check, shared with the API's revision routes.
        return WeblogOwnership.revision(weblogger, revisionId, entry);
    }

    @GetMapping("/entryEdit!firstSave.rol")
    public String entryEditFirstSave(HttpServletRequest request, Model model,
                                     @ModelAttribute("bean") EntryBean bean,
                                     RedirectAttributes redirectAttributes) {
        populateCommonModel(request, model);
        model.addAttribute("actionName", "entryEdit");
        model.addAttribute("pageTitle", getText("weblogEdit.title.editEntry", request));

        // This landing page is just as bookmarkable/reloadable as
        // entryEdit.rol, and resolves the same way via lookupEntry (no status
        // filter) -- so it carries the exact same trashed-entry hazard that
        // guard exists for above: send the author to the trash screen rather
        // than quietly rendering an editable form for an entry that has
        // since been trashed.
        WeblogEntry lookedUp = lookupEntry(bean.getId(), request);
        if (lookedUp != null && lookedUp.getStatus() == PubStatus.TRASHED) {
            addFlashError(redirectAttributes, "entryEdit.entryIsTrashed", request);
            return "redirect:/roller-ui/authoring/trash.rol?weblog="
                    + getActionWeblog(request).getHandle();
        }
        WeblogEntry entry = lookedUp;
        if (entry == null) {
            return "redirect:/roller-ui/menu.rol";
        }

        addStatusMessage(entry.getStatus(), model, entry, request);
        bean.copyFrom(entry, request.getLocale());
        model.addAttribute("entry", entry);
        addEntryModelAttributes(request, model, entry, bean);
        return ".EntryEdit";
    }

    @PostMapping("/entryEdit!saveDraft.rol")
    public String entryEditSaveDraft(HttpServletRequest request, Model model,
                                     @ModelAttribute("bean") EntryBean bean) {
        return doEntryEditSave(request, model, bean, "saveDraft");
    }

    @PostMapping("/entryEdit!publish.rol")
    public String entryEditPublish(HttpServletRequest request, Model model,
                                   @ModelAttribute("bean") EntryBean bean) {
        return doEntryEditSave(request, model, bean, "publish");
    }

    /**
     * Sends the entry as a Listmonk newsletter campaign to the weblog's
     * configured list -- SYNCHRONOUSLY, in the POST itself. This is a
     * deliberate spec deviation from a background job: there is no retry
     * queue, so the human who clicked "Send as newsletter" IS the retry
     * mechanism. {@code newsletterSentAt} is stamped ONLY after
     * {@link ListmonkClient#sendCampaign} returns successfully, so a failed
     * attempt leaves the button showing and nothing gets stamped -- and,
     * conversely, an already-stamped entry is refused before the client is
     * ever touched. Together those two guarantee an entry cannot be sent
     * twice.
     *
     * <p>Re-renders the edit view either way (never a redirect), so the
     * author sees the result -- success, "already sent", or the failure
     * message -- on the same page they clicked from.
     *
     * <p><b>Requires {@code WeblogPermission.POST}, not just the class-level
     * {@code EDIT_DRAFT} gate.</b> Mailing every subscriber is a much bigger
     * blast radius than editing a draft, so a contributor who can only draft
     * (not publish) must not be able to trigger a send -- the same distinction
     * {@link #setPublishStatus} draws for publishing itself. Denied the same
     * way {@code RollerHandlerInterceptor} would have denied it at the class
     * level, had this action needed a stricter class-wide gate.
     */
    @PostMapping("/entryEdit!sendNewsletter.rol")
    public String entryEditSendNewsletter(
            @RequestParam(name = "bean.id") String entryId,
            HttpServletRequest request, Model model) {
        if (!hasWeblogAction(getActionWeblog(request),
                getAuthenticatedUser(request), WeblogPermission.POST)) {
            return "redirect:/roller-ui/access-denied.rol";
        }

        populateCommonModel(request, model);
        model.addAttribute("actionName", "entryEdit");
        model.addAttribute("pageTitle", getText("weblogEdit.title.editEntry", request));

        WeblogEntry entry = lookupEntry(entryId, request);
        if (entry == null) {
            return "redirect:/roller-ui/menu.rol";
        }

        if (!entry.isPublished()) {
            addError(model, "newsletter.notPublished", request);
        } else if (entry.getNewsletterSentAt() != null) {
            addError(model, "newsletter.alreadySent", request);
        } else {
            String listUuid = getActionWeblog(request).getNewsletterListUuid();
            ListmonkClient client = listmonkClient();
            if (StringUtils.isBlank(listUuid)) {
                addError(model, "newsletter.noList", request);
            } else if (!client.isCampaignConfigured()) {
                addError(model, "newsletter.notConfigured", request);
            } else {
                sendNewsletterCampaign(entry, listUuid, client, model, request);
            }
        }

        EntryBean bean = new EntryBean();
        bean.copyFrom(entry, request.getLocale());
        model.addAttribute("entry", entry);
        addEntryModelAttributes(request, model, entry, bean);
        return ".EntryEdit";
    }

    /**
     * The actual send, isolated so {@link #entryEditSendNewsletter} reads as
     * the guard sequence it is. Builds the campaign HTML from the same
     * theme-independent seam feeds use ({@code EntryRenderer.transformedText()}) so the
     * newsletter body matches what the entry renders as, escapes only the
     * title (the body is already-sanitized HTML by the time it reaches
     * here), and stamps {@code newsletterSentAt} strictly after
     * {@code sendCampaign} returns without throwing.
     *
     * <p>The two exception scopes below are deliberately separate, not
     * merged into one try/catch. Once {@code sendCampaign} returns
     * successfully the email is irreversibly gone to every subscriber; a
     * failure recording that -- {@code saveWeblogEntry} or {@code flush}
     * throwing -- is a completely different situation from the send itself
     * failing, and must not be reported with the same generic message. That
     * would leave the button showing (nothing looks stamped) and invite the
     * author to click it again, which really would send the campaign twice.
     * {@code newsletter.sentButNotRecorded} says explicitly not to retry.
     */
    private void sendNewsletterCampaign(WeblogEntry entry, String listUuid, ListmonkClient client,
                                        Model model, HttpServletRequest request) {
        String html = "<h1>" + StringEscapeUtils.escapeHtml4(entry.getTitle()) + "</h1>\n"
                + weblogger.getEntryRenderer().transformedText(entry)
                + "\n<p><a href=\"" + permalinkOf(entry) + "\">Read on the site</a></p>";
        try {
            client.sendCampaign(listUuid, entry.getTitle(), html);
        } catch (IOException ex) {
            log.error("Error sending newsletter campaign for entry {}", entry.getId(), ex);
            addError(model, "newsletter.sendFailed",
                    StringEscapeUtils.escapeHtml4(ex.getMessage()), request);
            return;
        }

        // The campaign is already sent at this point. Nothing from here on
        // may be reported as an ordinary, retry-inviting failure.
        try {
            entry.setNewsletterSentAt(new Timestamp(System.currentTimeMillis()));
            weblogger.getWeblogEntryManager().saveWeblogEntry(entry);
            weblogger.flush();
            addMessage(model, "newsletter.sent", request);
        } catch (WebloggerException ex) {
            log.error("Newsletter campaign for entry {} was sent, but recording newsletterSentAt failed",
                    entry.getId(), ex);
            addError(model, "newsletter.sentButNotRecorded",
                    StringEscapeUtils.escapeHtml4(ex.getMessage()), request);
        }
    }

    /**
     * Package-private so {@code EntryEditNewsletterTest} can inject a mock
     * without a real Listmonk instance -- the same seam shape
     * {@code NewsletterController.setListmonkClient} gives its collaborator.
     */
    void setListmonkClient(ListmonkClient client) {
        this.listmonkClient = client;
    }

    private ListmonkClient listmonkClient() {
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

    private String redirectToEntryEdit(HttpServletRequest request, WeblogEntry entry) {
        return "redirect:/roller-ui/authoring/entryEdit.rol?weblog="
                + getActionWeblog(request).getHandle() + "&bean.id=" + entry.getId();
    }

    private String doEntryEditSave(HttpServletRequest request, Model model, EntryBean bean, String action) {
        populateCommonModel(request, model);
        model.addAttribute("actionName", "entryEdit");
        model.addAttribute("pageTitle", getText("weblogEdit.title.editEntry", request));

        WeblogEntry entry = lookupEntry(bean.getId(), request);
        if (entry == null) {
            return "redirect:/roller-ui/menu.rol";
        }

        Timestamp pubTime = resolvePubTime(bean, request, model);
        resolveEventTimes(bean, request, model);

        if ("saveDraft".equals(action)) {
            bean.setStatus(PubStatus.DRAFT.name());
        } else if ("publish".equals(action)) {
            setPublishStatus(bean, pubTime, request);
        }

        String result = doSave(request, model, bean, entry, "entryEdit", pubTime);
        model.addAttribute("entry", entry);
        addEntryModelAttributes(request, model, entry, bean);
        return result;
    }

    /**
     * {@code bean.pubTimeLocal} parsed in the action weblog's timezone, once
     * per save so {@link #setPublishStatus} and {@link #doSave} agree on the
     * same instant -- the old code called {@code bean.getPubTime(...)} twice,
     * which was harmless when a parse failure just returned null, but is not
     * once a bad value has to be reported.
     *
     * <p>A value that will not parse adds a validation error to the model
     * and resolves to null here, the same as a blank field. That is
     * deliberate: this method runs before {@link #doSave}'s own {@code
     * hasErrors} gate is checked, and that gate -- not this method -- is
     * what actually blocks the save. Reporting the error here just means a
     * mistyped pubtime is never silently read as "publish now" the way the
     * old dateString parser swallowed it.
     */
    private Timestamp resolvePubTime(EntryBean bean, HttpServletRequest request, Model model) {
        try {
            return bean.getPubTime(getActionWeblog(request).getTimeZoneInstance());
        } catch (DateTimeParseException e) {
            addFieldError(model, "entry_bean_pubTimeLocal", "entryEdit.pubTimeInvalid", request);
            return null;
        }
    }

    /**
     * The event times the form submitted, read in the action weblog's zone as a
     * pubtime is, and set on the bean for copyTo. A value that will not parse,
     * or an end before the start, is a field error; doSave's hasErrors gate
     * then blocks the save.
     */
    private void resolveEventTimes(EntryBean bean, HttpServletRequest request, Model model) {
        TimeZone zone = getActionWeblog(request).getTimeZoneInstance();
        Timestamp start = eventTime(bean.getEventStartLocal(), zone, "seo_eventStart", request, model);
        Timestamp end = eventTime(bean.getEventEndLocal(), zone, "seo_eventEnd", request, model);
        if (EntryFieldRules.endsBeforeStart(start, end)) {
            addFieldError(model, "seo_eventEnd", "entryEdit.eventEndBeforeStart", request);
        }
        bean.setEventStart(start);
        bean.setEventEnd(end);
    }

    private Timestamp eventTime(String text, TimeZone zone, String fieldId,
                                HttpServletRequest request, Model model) {
        try {
            return EntryFieldRules.parseEventTime(text, zone);
        } catch (DateTimeParseException e) {
            addFieldError(model, fieldId, "entryEdit.eventTimeInvalid", request);
            return null;
        }
    }

    // Tag counts need nothing here: saveWeblogEntry derives them from the
    // entry's status as loaded against its status as saved.
    private void setPublishStatus(EntryBean bean, Timestamp pubTime, HttpServletRequest request) {
        if (hasWeblogAction(getActionWeblog(request),
                getAuthenticatedUser(request), WeblogPermission.POST)) {
            if (pubTime != null && pubTime.after(
                    new Date(System.currentTimeMillis() + RollerConstants.MIN_IN_MS))) {
                bean.setStatus(PubStatus.SCHEDULED.name());
            } else {
                bean.setStatus(PubStatus.PUBLISHED.name());
            }
        } else {
            bean.setStatus(PubStatus.PENDING.name());
        }
    }

    /**
     * Resolves the category the form posted, for {@code EntryBean.copyTo} to
     * file the entry under. The bean is pure and cannot look the id up itself;
     * this keeps the semantics it used to have -- a lookup that fails is logged
     * and reads as "not found", which {@code copyTo} then refuses, so a broken
     * store never files an entry under no category.
     */
    private WeblogCategory lookupSubmittedCategory(EntryBean bean) {
        if (bean.getCategoryId() == null) {
            return null;
        }
        try {
            return weblogger.getWeblogEntryManager().getWeblogCategory(bean.getCategoryId());
        } catch (WebloggerException ex) {
            log.error("Error getting category by id", ex);
            return null;
        }
    }

    private String doSave(HttpServletRequest request, Model model, EntryBean bean,
                          WeblogEntry entry, String actionName, Timestamp pubTime) {
        if (StringUtils.isNotBlank(bean.getCanonicalUrl())
                && !CANONICAL_URL_VALIDATOR.isValid(bean.getCanonicalUrl())) {
            addError(model, "entryEdit.canonicalUrlInvalid", request);
        }
        if (!hasErrors(model)) {
            try {
                WeblogEntryManager weblogEntryManager = weblogger.getWeblogEntryManager();
                IndexManager indexMgr = weblogger.getIndexManager();

                entry.setUpdateTime(new Timestamp(new Date().getTime()));
                entry.setPubTime(pubTime);

                bean.copyTo(entry, lookupSubmittedCategory(bean));

                if (entry.isPublished() && entry.getPubTime() == null) {
                    entry.setPubTime(entry.getUpdateTime());
                }

                GlobalPermission adminPerm = new GlobalPermission(
                        Collections.singletonList(GlobalPermission.ADMIN));
                if (weblogger.getUserManager()
                        .checkPermission(adminPerm, getAuthenticatedUser(request))) {
                    entry.setPinnedToMain(bean.getPinnedToMain());
                }

                weblogEntryManager.saveWeblogEntry(entry, getAuthenticatedUser(request));
                weblogger.flush();

                if (entry.isPublished()) {
                    indexMgr.addEntryReIndexOperation(entry);
                } else if ("entryEdit".equals(actionName)) {
                    indexMgr.removeEntryIndexOperation(entry);
                }

                CacheManager.invalidate(entry);

                if (entry.isPending() && MailUtil.isMailConfigured()) {
                    MailUtil.sendPendingEntryNotice(weblogger, entry);
                }

                if ("entryEdit".equals(actionName)) {
                    addStatusMessage(entry.getStatus(), model, entry, request);
                    return ".EntryEdit";
                } else {
                    bean.setId(entry.getId());
                    return "redirect:/roller-ui/authoring/entryEdit!firstSave.rol?weblog="
                            + getActionWeblog(request).getHandle() + "&bean.id=" + entry.getId();
                }

            } catch (Exception e) {
                log.error("Error saving new entry", e);
                addError(model, "generic.error.check.logs", request);
            }
        }
        if ("entryAdd".equals(actionName)) {
            bean.setStatus(null);
        }
        return ".EntryEdit";
    }

    // Package-private rather than private for EntryEditControllerTest --
    // both real callers guard TRASHED away before reaching this method (see
    // the case's own comment below), so the only way to exercise that
    // defensive branch is to call this directly.
    void addStatusMessage(PubStatus pubStatus, Model model, WeblogEntry entry, HttpServletRequest request) {
        // An expression, so the compiler rejects a new PubStatus constant here.
        // The arms are lambdas rather than values so each addMessage call stays a plain
        // call site that MessageArgumentEscapingTest can scan.
        Runnable toast = switch (pubStatus) {
            case DRAFT -> () -> addMessage(model, "weblogEdit.draftSaved", request);
            // The href is built here, from the entry, and never from anything a
            // reader supplied: messages.jsp renders a message unescaped.
            case PUBLISHED -> () -> addMessage(model, "weblogEdit.publishedEntry", permalinkOf(entry), request);
            case SCHEDULED -> () -> addMessage(model, "weblogEdit.scheduledEntry",
                    DateUtil.fullDate(entry.getPubTime()), request);
            case PENDING -> () -> addMessage(model, "weblogEdit.submittedForReview", request);
            // Both callers (entryEditExecute and entryEditFirstSave)
            // already redirect a trashed entry to the trash screen
            // before reaching this method, so this should be
            // unreachable in practice; it is handled explicitly rather
            // than silently, in case a future call site forgets that
            // guard. No status toast makes sense for it, so this is a
            // documented no-op rather than a thrown exception -- this
            // method's only job is a confirmation message, and both
            // current callers would misreport an otherwise-successful
            // request as a failure (the save-flow caller sits inside a
            // try/catch that shows "generic.error.check.logs" on any
            // exception; the firstSave caller has no surrounding
            // try/catch at all and would simply 500 a page view) if
            // this threw instead.
            case TRASHED -> null;
        };
        if (toast != null) {
            toast.run();
        }
    }

    private void addEntryModelAttributes(HttpServletRequest request, Model model, WeblogEntry entry,
                                         EntryBean bean) {
        // Name the browser tab after the entry. With a dozen editor tabs open
        // -- the normal way this screen gets used -- every one of them read
        // "Edit entry" and the only way to find the right one was to click
        // through them.
        //
        // This is a SEPARATE attribute rather than an overwrite of pageTitle,
        // and the separation is the whole point: the layout renders pageTitle
        // twice -- once in <title>, once as the visible
        // <h2 class="roller-page-title"> -- so appending here used to print
        // "Stored title <em dash> Edit entry" into the page's own heading.
        // tiles-tabbedpage.jsp prefers tabTitle in <title> and falls back to
        // pageTitle; the h2 only ever reads pageTitle.
        //
        // Set only once the entry has a title: an unsaved entry has none.
        // getDisplayTitle() strips HTML and falls back to the entry's opening
        // text, so it is blank only on the add screen's scratch entry -- which
        // an id check alone does NOT exclude (a unit test caught the add
        // screen rendering a leading em dash with nothing before it).
        if (StringUtils.isNotBlank(entry.getDisplayTitle())) {
            // Plain text: the layouts print it once through fn:escapeXml.
            model.addAttribute("tabTitle", EntriesController.plainTitle(entry)
                    + " \u2014 " + model.getAttribute("pageTitle"));
        }

        model.addAttribute("categories", getCategories(request));
        // The SEO drawer's character counts read the same limits the audits use.
        model.addAttribute("seoTitleMax", SeoLimits.TITLE_MAX);
        model.addAttribute("seoDescriptionMax", SeoLimits.DESCRIPTION_MAX);
        model.addAttribute("seoDescriptionMin", SeoLimits.DESCRIPTION_MIN);
        boolean mayPost = hasWeblogAction(getActionWeblog(request),
                getAuthenticatedUser(request), WeblogPermission.POST);
        model.addAttribute("userAnAuthor", mayPost);
        // EntryEdit.jsp used to ask ${authenticatedUser.hasGlobalPermission('admin')}
        // for the pin-to-front-page box; the entity no longer answers that.
        model.addAttribute("isGlobalAdmin", isGlobalAdmin(getAuthenticatedUser(request)));

        // The editor's insert menu, generated from the shortcode registry
        // itself so it can never advertise a shortcode that does not render,
        // or omit one that does.
        model.addAttribute("shortcodeCards", weblogger.getEntryRenderer().shortcodeCards());

        if (entry.getId() != null) {
            try {
                model.addAttribute("entryRevisions",
                        weblogger.getWeblogEntryManager().getRevisions(entry));
            } catch (WebloggerException e) {
                log.error("Error loading revisions for entry {}", entry.getId(), e);
            }
        }

        // Comment counts for the "N published, M awaiting" line: only for a saved
        // entry on a weblog with comments on, and only for a member who may
        // moderate (weblog POST), since the line links to the Comments page. The
        // counts are of top-level comments, the number the entry page's heading
        // shows. A count that cannot load leaves the line off (display path),
        // never blocks the editor.
        if (mayPost && bean.getId() != null
                && Boolean.TRUE.equals(getActionWeblog(request).getCommentsEnabled())) {
            try {
                model.addAttribute("commentsPublished", weblogger.getCommentManager()
                        .countForEntry(entry, ReaderComment.Status.PUBLISHED));
                model.addAttribute("commentsPending", weblogger.getCommentManager()
                        .countForEntry(entry, ReaderComment.Status.PENDING));
            } catch (WebloggerException e) {
                log.error("Error counting comments for entry {}", entry.getId(), e);
            }
        }

        if (entry.getId() != null) {
            model.addAttribute("previewURL", weblogger.getUrlStrategy()
                    .getPreviewURLStrategy(null)
                    .getWeblogEntryURL(getActionWeblog(request), null, entry.getAnchor(), true));
        }

        // The document the editor's split/preview panes frame. Built by
        // BaseController rather than here, because the page editor frames the
        // same shell and two copies of the string is how the two screens'
        // previews would come to differ.
        model.addAttribute("previewShellURL", previewShellURL(getActionWeblog(request)));

        // Thumbnail previews for the SEO panel's featured/social image pickers.
        // Read off the bean rather than the entry so a save that failed
        // validation still shows the image the author picked in the form.
        addImagePreviewAttribute(request, model, "featuredImageThumbnailUrl", bean.getFeaturedImageId());
        addImagePreviewAttribute(request, model, "ogImageThumbnailUrl", bean.getOgImageId());
    }

    /**
     * Adds the thumbnail URL for a referenced media file, if it still exists
     * and belongs to this weblog. A dangling id (image deleted after being
     * picked) simply renders no preview rather than a broken editor page, and
     * an id belonging to another weblog is treated exactly the same way --
     * {@code getMediaFile} is a global by-id lookup and these ids come off the
     * submitted form.
     */
    private void addImagePreviewAttribute(HttpServletRequest request, Model model,
                                          String attributeName, String mediaFileId) {
        if (StringUtils.isEmpty(mediaFileId)) {
            return;
        }
        try {
            MediaFile mediaFile = weblogger.getMediaFileManager().getMediaFile(mediaFileId);
            if (mediaFile != null && getActionWeblog(request).equals(mediaFile.getWeblog())) {
                model.addAttribute(attributeName, weblogger.getUrlStrategy()
                        .getMediaFileThumbnailURL(mediaFile.getWeblog(), mediaFile.getId(), true));
            }
        } catch (WebloggerException ex) {
            log.error("Error looking up media file - {}", mediaFileId, ex);
        }
    }

    private List<WeblogCategory> getCategories(HttpServletRequest request) {
        try {
            WeblogEntryManager wmgr = weblogger.getWeblogEntryManager();
            return wmgr.getWeblogCategories(getActionWeblog(request));
        } catch (WebloggerException ex) {
            log.error("Error getting category list", ex);
            return Collections.emptyList();
        }
    }

    @ModelAttribute("bean")
    public EntryBean getBean() {
        return new EntryBean();
    }

    /**
     * The entry's absolute permalink, built here from the injected strategy --
     * what {@code WeblogEntry.getPermalink()} used to build through the static
     * service locator. Same arguments as {@code AdminUrls.entry}, which is the
     * JSP-side twin.
     */
    private String permalinkOf(WeblogEntry entry) {
        return weblogger.getUrlStrategy().getWeblogEntryURL(entry.getWebsite(), null, entry.getAnchor(), true);
    }
}
