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

package com.wikantik.pressroom.ui.controllers.editor;

import java.io.IOException;
import java.sql.Timestamp;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.TimeZone;

import jakarta.servlet.http.HttpServletRequest;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.commons.text.StringEscapeUtils;
import com.wikantik.pressroom.util.DateUtil;
import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.business.ListmonkClient;
import com.wikantik.pressroom.business.seo.SeoLimits;
import com.wikantik.pressroom.business.WeblogEntryManager;
import com.wikantik.pressroom.pojos.GlobalPermission;
import com.wikantik.pressroom.pojos.ReaderComment;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.pojos.WeblogPage;
import com.wikantik.pressroom.pojos.WeblogCategory;
import com.wikantik.pressroom.pojos.WeblogEntry;
import com.wikantik.pressroom.pojos.WeblogEntryRevision;
import com.wikantik.pressroom.pojos.WeblogEntry.PubStatus;
import com.wikantik.pressroom.pojos.WeblogPermission;
import com.wikantik.pressroom.ui.controllers.BaseController;
import com.wikantik.pressroom.ui.controllers.EntryFieldRules;
import com.wikantik.pressroom.ui.controllers.EntryPinRules;
import com.wikantik.pressroom.ui.controllers.RuleViolation;
import com.wikantik.pressroom.ui.controllers.StrandChoice;
import com.wikantik.pressroom.ui.controllers.WeblogOwnership;
import com.wikantik.pressroom.util.MailUtil;
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
@RequestMapping("/pressroom-ui/authoring")
public class EntryEditController extends BaseController {

    private static final Logger log = LoggerFactory.getLogger(EntryEditController.class);

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
        populateCommonModel(request, model, screen(EditorMode.ADD, request));

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
        return doEntryAddSave(request, model, bean, SaveIntent.DRAFT);
    }

    @PostMapping("/entryAdd!publish.rol")
    public String entryAddPublish(HttpServletRequest request, Model model,
                                  @ModelAttribute("bean") EntryBean bean) {
        return doEntryAddSave(request, model, bean, SaveIntent.PUBLISH);
    }

    private String doEntryAddSave(HttpServletRequest request, Model model, EntryBean bean, SaveIntent intent) {
        populateCommonModel(request, model, screen(EditorMode.ADD, request));

        WeblogEntry entry = new WeblogEntry();
        entry.setCreatorUserName(getAuthenticatedUser(request).getUserName());
        entry.setWebsite(getActionWeblog(request));

        Timestamp pubTime = resolvePubTime(bean, request, model);
        resolveEventTimes(bean, request, model);

        bean.setStatus(intent.status(pubTime, mayPost(request), System.currentTimeMillis()).name());

        String result = doSave(request, model, bean, entry, EditorMode.ADD, pubTime);
        model.addAttribute("entry", entry);
        addEntryModelAttributes(request, model, entry, bean);
        return result;
    }

    // --- entryEdit ---

    @GetMapping("/entryEdit.rol")
    public String entryEditExecute(HttpServletRequest request, Model model,
                                   @ModelAttribute("bean") EntryBean bean,
                                   RedirectAttributes redirectAttributes) {
        populateCommonModel(request, model, screen(EditorMode.EDIT, request));

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
            return "redirect:/pressroom-ui/authoring/trash.rol?weblog="
                    + getActionWeblog(request).getHandle();
        }
        WeblogEntry entry = lookupNonTrashedEntry(bean.getId(), request);
        if (entry == null) {
            return "redirect:/pressroom-ui/menu.rol";
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

        // Right after a Restore the newest revision often matches the entry
        // exactly; the fragment then says so instead of opening blank.
        String html = RevisionDiffHtml.of(getText("weblogEdit.revisionSame", request),
                new RevisionDiffHtml.Section(getText("weblogEdit.title", request),
                        revision.getTitle(), entry.getTitle()),
                new RevisionDiffHtml.Section(getText("weblogEdit.summary", request),
                        revision.getSummary(), entry.getSummary()),
                new RevisionDiffHtml.Section(getText("weblogEdit.content", request),
                        revision.getText(), entry.getText()));

        return ResponseEntity.ok()
                .contentType(MediaType.valueOf("text/html;charset=UTF-8"))
                .body(html);
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
        WeblogEntry entry = lookupChangeableEntry(id, request);
        if (!revisionActions(entry, request, redirectAttributes).restore(revisionId, getAuthenticatedUser(request))) {
            return redirectToEntryList(request);
        }
        return redirectToEntryEdit(request, entry);
    }

    /**
     * Marks one of the entry's revisions as kept, or releases it. A kept
     * revision is exempt from every retention limit; an entry may have ten.
     */
    @PostMapping("/entryEdit!keepRevision.rol")
    public String entryEditKeepRevision(HttpServletRequest request,
                                        @RequestParam(value = "bean.id", required = false) String id,
                                        @RequestParam(value = "revisionId", required = false) String revisionId,
                                        @RequestParam(value = "kept", required = false) boolean kept,
                                        RedirectAttributes redirectAttributes) {
        WeblogEntry entry = lookupChangeableEntry(id, request);
        if (!revisionActions(entry, request, redirectAttributes).keep(revisionId, kept)) {
            return redirectToEntryList(request);
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

    private RevisionActions<WeblogEntryRevision> revisionActions(WeblogEntry entry, HttpServletRequest request,
                                                                 RedirectAttributes redirectAttributes) {
        return new RevisionActions<>(new EntryRevisions(weblogger, entry), log, new RevisionActions.FlashSink() {
            @Override
            public void message(String key) {
                addFlashMessage(redirectAttributes, key, request);
            }

            @Override
            public void error(String key) {
                addFlashError(redirectAttributes, key, request);
            }

            @Override
            public void error(String key, String param) {
                addFlashError(redirectAttributes, key, param, request);
            }
        });
    }

    private String redirectToEntryList(HttpServletRequest request) {
        return "redirect:/pressroom-ui/authoring/entries.rol?weblog=" + getActionWeblog(request).getHandle();
    }

    @GetMapping("/entryEdit!firstSave.rol")
    public String entryEditFirstSave(HttpServletRequest request, Model model,
                                     @ModelAttribute("bean") EntryBean bean,
                                     RedirectAttributes redirectAttributes) {
        populateCommonModel(request, model, screen(EditorMode.EDIT, request));

        // This landing page is just as bookmarkable/reloadable as
        // entryEdit.rol, and resolves the same way via lookupEntry (no status
        // filter) -- so it carries the exact same trashed-entry hazard that
        // guard exists for above: send the author to the trash screen rather
        // than quietly rendering an editable form for an entry that has
        // since been trashed.
        WeblogEntry lookedUp = lookupEntry(bean.getId(), request);
        if (lookedUp != null && lookedUp.getStatus() == PubStatus.TRASHED) {
            addFlashError(redirectAttributes, "entryEdit.entryIsTrashed", request);
            return "redirect:/pressroom-ui/authoring/trash.rol?weblog="
                    + getActionWeblog(request).getHandle();
        }
        WeblogEntry entry = lookedUp;
        if (entry == null) {
            return "redirect:/pressroom-ui/menu.rol";
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
        return doEntryEditSave(request, model, bean, SaveIntent.DRAFT);
    }

    @PostMapping("/entryEdit!publish.rol")
    public String entryEditPublish(HttpServletRequest request, Model model,
                                   @ModelAttribute("bean") EntryBean bean) {
        return doEntryEditSave(request, model, bean, SaveIntent.PUBLISH);
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
     * {@link SaveIntent#status} draws for publishing itself. Denied the same
     * way {@code PressroomHandlerInterceptor} would have denied it at the class
     * level, had this action needed a stricter class-wide gate.
     */
    @PostMapping("/entryEdit!sendNewsletter.rol")
    public String entryEditSendNewsletter(
            @RequestParam(name = "bean.id") String entryId,
            HttpServletRequest request, Model model) {
        if (!hasWeblogAction(getActionWeblog(request),
                getAuthenticatedUser(request), WeblogPermission.POST)) {
            return "redirect:/pressroom-ui/access-denied.rol";
        }

        populateCommonModel(request, model, screen(EditorMode.EDIT, request));

        WeblogEntry entry = lookupEntry(entryId, request);
        if (entry == null) {
            return "redirect:/pressroom-ui/menu.rol";
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
        return "redirect:/pressroom-ui/authoring/entryEdit.rol?weblog="
                + getActionWeblog(request).getHandle() + "&bean.id=" + entry.getId();
    }

    private String doEntryEditSave(HttpServletRequest request, Model model, EntryBean bean, SaveIntent intent) {
        populateCommonModel(request, model, screen(EditorMode.EDIT, request));

        WeblogEntry entry = lookupChangeableEntry(bean.getId(), request);
        if (entry == null) {
            return "redirect:/pressroom-ui/menu.rol";
        }

        Timestamp pubTime = resolvePubTime(bean, request, model);
        resolveEventTimes(bean, request, model);

        bean.setStatus(intent.status(pubTime, mayPost(request), System.currentTimeMillis()).name());

        String result = doSave(request, model, bean, entry, EditorMode.EDIT, pubTime);
        model.addAttribute("entry", entry);
        addEntryModelAttributes(request, model, entry, bean);
        return result;
    }

    /**
     * {@code bean.pubTimeLocal} parsed in the action weblog's timezone, once
     * per save so {@link SaveIntent#status} and {@link #doSave} agree on the
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
    private boolean mayPost(HttpServletRequest request) {
        return hasWeblogAction(getActionWeblog(request), getAuthenticatedUser(request), WeblogPermission.POST);
    }

    private Screen screen(EditorMode mode, HttpServletRequest request) {
        return new Screen(mode.actionName(), getText(mode.titleKey(), request));
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
                          WeblogEntry entry, EditorMode mode, Timestamp pubTime) {
        if (StringUtils.isNotBlank(bean.getCanonicalUrl())
                && !EntryFieldRules.isValidCanonicalUrl(bean.getCanonicalUrl())) {
            addError(model, "entryEdit.canonicalUrlInvalid", request);
        }
        // Checked against the stored values before copyTo overwrites them.
        checkPickedImage(model, "weblogEdit.error.featuredImageUnusable",
                bean.getFeaturedImageId(), entry.getFeaturedImageId(), request);
        checkPickedImage(model, "weblogEdit.error.ogImageUnusable",
                bean.getOgImageId(), entry.getOgImageId(), request);
        boolean mayPin = hasWeblogAction(getActionWeblog(request),
                getAuthenticatedUser(request), WeblogPermission.POST);
        if (mayPin) {
            checkPinLimit(model, entry, bean.getPinned(), request);
        }
        // Resolved and checked before anything is applied or saved; null when
        // the strand is left as it is or the post is taken out of its strand.
        WeblogPage strand = mayPin ? checkedStrand(bean, entry, model, request) : null;
        if (!hasErrors(model)) {
            try {
                applyForm(request, bean, entry, pubTime);
                // Only a user with POST sees the checkbox; for anyone else
                // the absent field keeps the stored pin (Review Focus 3).
                if (mayPin) {
                    entry.setPinned(bean.getPinned());
                    applyStrand(bean, entry, strand);
                }
                weblogger.getWeblogEntryManager().saveWeblogEntry(entry, getAuthenticatedUser(request));
                weblogger.flush();
                afterSave(entry);

                if (mode.rendersAfterSave()) {
                    addStatusMessage(entry.getStatus(), model, entry, request);
                    return ".EntryEdit";
                } else {
                    bean.setId(entry.getId());
                    return "redirect:/pressroom-ui/authoring/entryEdit!firstSave.rol?weblog="
                            + getActionWeblog(request).getHandle() + "&bean.id=" + entry.getId();
                }

            } catch (Exception e) {
                log.error("Error saving new entry", e);
                addError(model, "generic.error.check.logs", request);
            }
        }
        if (mode.clearsStatusOnFailure()) {
            bean.setStatus(null);
        }
        return ".EntryEdit";
    }

    /**
     * The strand the form chose, or null when there is nothing to append to:
     * the field is absent (keep), empty (none) or names the strand the post
     * is already in. Anything else must be one of this blog's strand pages --
     * a page of another blog, an unknown id and a plain page are one field
     * error, so nothing is saved ({@link StrandChoice} is the rule, and its ownership check).
     */
    private WeblogPage checkedStrand(EntryBean bean, WeblogEntry entry, Model model,
                                     HttpServletRequest request) {
        String id = bean.getStrandPageId();
        if (StringUtils.isBlank(id)) {
            return null;
        }
        StrandChoice.Choice choice = StrandChoice.resolve(weblogger, getActionWeblog(request), entry, id);
        if (choice.violation() != null) {
            addFieldError(model, "entry_bean_strandPageId", choice.violation(), request);
        }
        return choice.page();
    }

    /** Applies the checked choice; an absent field (null) keeps the stored strand. */
    private void applyStrand(EntryBean bean, WeblogEntry entry, WeblogPage strand)
            throws WebloggerException {
        if (bean.getStrandPageId() == null) {
            return;
        }
        if (strand != null) {
            weblogger.getWeblogEntryManager().appendToStrand(entry, strand);
        } else if (bean.getStrandPageId().isBlank()) {
            weblogger.getWeblogEntryManager().leaveStrand(entry);
        }
    }

    /**
     * Refuses a save that would pin one post more than the blog allows
     * ({@link EntryPinRules}), as a field error on the checkbox, before anything
     * is applied. A count that cannot be read refuses the pin too: it cannot
     * vouch for the limit.
     */
    private void checkPinLimit(Model model, WeblogEntry entry, boolean requested, HttpServletRequest request) {
        try {
            RuleViolation problem = EntryPinRules.roomProblem(weblogger.getWeblogEntryManager(), entry.getWebsite(),
                    entry.getPinned(), requested);
            if (problem != null) {
                addFieldError(model, "entry_bean_pinned", problem, request);
            }
        } catch (WebloggerException e) {
            log.error("Error counting the pinned posts of weblog {}", entry.getWebsite().getHandle(), e);
            addError(model, "generic.error.check.logs", request);
        }
    }

    /** Copies the form onto the entry, with the defaults a save fills in. */
    private void applyForm(HttpServletRequest request, EntryBean bean, WeblogEntry entry, Timestamp pubTime)
            throws WebloggerException {
        entry.setUpdateTime(new Timestamp(new Date().getTime()));
        entry.setPubTime(pubTime);

        bean.copyTo(entry, lookupSubmittedCategory(bean));
        // None picked: the first picture in the text, as Blogger does
        // and as the API does (spec 2026-11-07, pictures by default).
        if (StringUtils.isBlank(entry.getFeaturedImageId())) {
            entry.setFeaturedImageId(WeblogOwnership.firstPublicImage(
                    weblogger, entry.getWebsite(), entry.getText(), Set.of()));
        }

        entry.stampPubTimeIfPublished(entry.getUpdateTime());

        // only a site admin pins an entry to the front page
        GlobalPermission adminPerm = new GlobalPermission(
                Collections.singletonList(GlobalPermission.ADMIN));
        if (weblogger.getUserManager()
                .checkPermission(adminPerm, getAuthenticatedUser(request))) {
            entry.setPinnedToMain(bean.getPinnedToMain());
        }
    }

    /**
     * What follows a committed save: the reviewers' notice for an entry
     * submitted for review. The search index and the rendered pages are kept
     * by the entry manager's change listeners.
     */
    private void afterSave(WeblogEntry entry) throws WebloggerException {
        MailUtil.sendPendingEntryNoticeIfDue(weblogger, entry);
    }

    /**
     * This blog's strand pages, drafts included, for the select, and the
     * post's current strand even when that is in the trash. A display
     * path (the save validates the chosen id again), so a failed read shows
     * an empty list rather than failing the editor.
     */
    private List<WeblogPage> strandPages(Weblog weblog, WeblogEntry entry) {
        try {
            List<WeblogPage> strands = new ArrayList<>(weblogger.getWeblogPageManager().getPages(weblog).stream()
                    .filter(p -> Boolean.TRUE.equals(p.getStrand()))
                    .toList());
            // The post's own strand, when it is in the trash: the page list
            // no longer holds it, and without it the select would read "no
            // strand" for a post that is still a part. Shown as in the trash;
            // re-sending it keeps the membership, and no other post can
            // choose it.
            WeblogPage current = entry == null ? null
                    : WeblogOwnership.trashedPage(weblogger, entry.getStrandPageId(), weblog);
            if (current != null) {
                strands.add(current);
            }
            return strands;
        } catch (WebloggerException ex) {
            log.error("Error listing the strand pages of weblog {}", weblog.getHandle(), ex);
            return List.of();
        }
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
        // <h2 class="pressroom-page-title"> -- so appending here used to print
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
        model.addAttribute("strandPages", strandPages(getActionWeblog(request), entry));
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
     * and belongs to this weblog ({@code BaseController.pickedImageThumbnailURL}).
     * A dangling id simply renders no preview rather than a broken editor
     * page, and an id belonging to another weblog is treated the same way.
     */
    private void addImagePreviewAttribute(HttpServletRequest request, Model model,
                                          String attributeName, String mediaFileId) {
        String url = pickedImageThumbnailURL(mediaFileId, request);
        if (url != null) {
            model.addAttribute(attributeName, url);
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
