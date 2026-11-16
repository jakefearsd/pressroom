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

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import jakarta.servlet.http.HttpServletRequest;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.commons.text.StringEscapeUtils;
import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.business.SiteSlugClashException;
import com.wikantik.pressroom.business.StrandRules;
import com.wikantik.pressroom.config.WebloggerRuntimeConfig;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.pojos.WeblogEntry;
import com.wikantik.pressroom.pojos.WeblogEntrySearchCriteria;
import com.wikantik.pressroom.pojos.WeblogPage;
import com.wikantik.pressroom.pojos.WeblogPermission;
import com.wikantik.pressroom.ui.controllers.BaseController;
import com.wikantik.pressroom.ui.controllers.EntryFieldRules;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

/**
 * Add or edit a single static page.
 *
 * <p>One route handles both: {@code id} absent (or blank) means "new page",
 * present means "edit the page it names" -- modelled on
 * {@code TemplateEditController}, which does the same for templates, rather
 * than the entry editor's separate add/edit action pair.
 */
@Controller
@RequestMapping("/pressroom-ui/authoring")
public class PageEditController extends BaseController {

    private static final Logger log = LoggerFactory.getLogger(PageEditController.class);

    /** The site's pages are written here, on the site space as on a blog. */
    @Override
    public boolean allowsSiteSpace() {
        return true;
    }

    @Override
    public List<String> requiredWeblogPermissionActions() {
        return Collections.singletonList(WeblogPermission.POST);
    }

    @Override
    public String getDesiredMenu() {
        return "editor";
    }

    @Override
    public String getActionName() {
        return "pageEdit";
    }

    @Override
    public String getPageTitle() {
        return "pageEdit.title";
    }

    @GetMapping("/pageEdit.rol")
    public String edit(@RequestParam(name = "id", required = false) String id,
                       HttpServletRequest request, Model model) {
        populateCommonModel(request, model);
        model.addAttribute("actionName", "pageEdit");
        // getPageTitle() (via populateCommonModel above) always answers
        // "pageEdit.title" ("Edit page") -- this one route handles both add
        // and edit (id absent vs present), unlike the entry editor's separate
        // entryAdd.rol/entryEdit.rol pair, so only the request has enough
        // information to pick the right title. Same override-after-populate
        // shape as EntryEditController's actionName.
        if (StringUtils.isBlank(id)) {
            model.addAttribute("pageTitle", "pageEdit.title.new");
        }

        PageBean bean = new PageBean();
        WeblogPage page = null;
        if (!StringUtils.isBlank(id)) {
            // Ownership-checked: id is client input and getPage is a global
            // by-id lookup, so without this an editor on one weblog could
            // open (and, on save, overwrite) another weblog's page.
            page = lookupPage(id, request);
            if (page == null) {
                addError(model, "pageEdit.notFound", request);
                return DENIED_VIEW;
            }
            bean.copyFrom(page);
            model.addAttribute("page", page);
        }

        addPageEditModelAttributes(request, model, bean, page, null);
        model.addAttribute("bean", bean);
        return ".PageEdit";
    }

    @PostMapping("/pageEdit!save.rol")
    public String save(HttpServletRequest request, Model model,
                       @ModelAttribute("bean") PageBean bean) {
        populateCommonModel(request, model);
        model.addAttribute("actionName", "pageEdit");

        boolean isNew = StringUtils.isBlank(bean.getId());
        WeblogPage page;
        if (isNew) {
            page = new WeblogPage();
            page.setWeblog(getActionWeblog(request));
        } else {
            // Same ownership check as the GET side: bean.id is client input,
            // and a global by-id lookup here would let a posted form
            // overwrite another weblog's page.
            page = lookupPage(bean.getId(), request);
            if (page == null) {
                addError(model, "pageEdit.notFound", request);
                return DENIED_VIEW;
            }
        }

        // The validator EntryEditController#doSave and the API use: the SEO
        // card's canonical-URL override is emitted straight into
        // <link rel="canonical">, og:url and JSON-LD mainEntityOfPage, so it
        // must be an absolute http(s) URL, checked before anything is
        // persisted -- a field error the author can fix, not a 500 and not a
        // silently-stored javascript:/data:/file: value.
        if (StringUtils.isNotBlank(bean.getCanonicalUrl())
                && !EntryFieldRules.isValidCanonicalUrl(bean.getCanonicalUrl())) {
            addFieldError(model, "seo_canonicalUrl", "entryEdit.canonicalUrlInvalid", request);
        }
        // The renderer resolves ogImageId with a global lookup, so it must name
        // an image of this weblog that readers can load (the entry editor's and
        // the API's rule), checked against the stored value before copyTo.
        checkPickedImage(model, "weblogEdit.error.ogImageUnusable", bean.getOgImageId(),
                page.getOgImageId(), request);
        // The strand switch and the Parts panel, validated like the fields
        // above: a refused id or a refused untick saves nothing at all.
        StrandSave strand = checkedStrandParts(bean, page, model, request);
        boolean saved = !hasErrors(model) && saved(bean, page, Optional.ofNullable(strand.store), model, request);
        if (saved || !isNew) {
            model.addAttribute("page", page);
        }

        // See the matching comment in edit(): getPageTitle() (via
        // populateCommonModel above) always answers "pageEdit.title" ("Edit
        // page"), and this route re-renders the same view whether the save
        // succeeded or failed validation. Decided here, at the end, off the
        // bean's FINAL id rather than the isNew flag captured on entry: a
        // successful create leaves this POST on the same URL but bean.copyFrom
        // (above) has since populated bean.id from the newly-persisted page,
        // so the title should flip to "Edit page" along with it -- a failed
        // create leaves bean.id blank and the title stays "New page".
        if (StringUtils.isBlank(bean.getId())) {
            model.addAttribute("pageTitle", "pageEdit.title.new");
        }

        // A refused save shows the author's unsaved rows again, not the
        // stored ones; a saved one shows what is now stored.
        addPageEditModelAttributes(request, model, bean, StringUtils.isBlank(bean.getId()) ? null : page,
                saved ? null : strand.rows);
        model.addAttribute("bean", bean);
        return ".PageEdit";
    }

    /** What a save does with a strand's parts, and what a refused save shows again. */
    private static final class StrandSave {
        /** The parts to store, in order; null leaves them as they are. */
        private List<WeblogEntry> store;
        /** The posted rows that can be parts, for a refused save to redisplay; null shows the stored parts. */
        private List<WeblogEntry> rows;
    }

    /**
     * The parts to store for this save, or null to leave them as they are
     * (unlike an empty list, which removes every part): the page is not (or
     * stays not) a strand, the form carried no Parts panel, or the author
     * left the panel as it was shown. Every refusal is reported on the model
     * before anything is saved.
     *
     * <p>The panel posts its rows ({@code partIds}) and the members it was
     * built with ({@code shownPartIds}). The parts stored are the posted
     * rows, then every current member in neither list -- one appended from
     * the entry editor or the API while this editor was open -- after them
     * in stored order: only what the author removed leaves the strand.
     *
     * <p>A site page is never a strand. Unticking a strand that still has
     * parts (any status, the trash included) is refused, and the form keeps
     * showing it as a strand -- unless the author removed every row it showed
     * and nothing was added elsewhere, which clears the parts in the same
     * save. Each requested id is resolved with the global lookup rather than
     * the ownership-checked one, so {@link StrandRules} can tell "another
     * blog's post" from "no such post"; a failed lookup is an unknown post,
     * never a pass, and members that cannot be read refuse the save.
     */
    private StrandSave checkedStrandParts(PageBean bean, WeblogPage page, Model model,
                                          HttpServletRequest request) {
        StrandSave result = new StrandSave();
        if (getActionWeblog(request).isSiteSpace()) {
            bean.setStrand(false);
        }
        if (!bean.getStrand()) {
            if (Boolean.TRUE.equals(page.getStrand())) {
                result.store = checkedUntick(bean, page, model, request).orElse(null);
                if (bean.getPartIds() != null && bean.getPartIds().isEmpty()) {
                    result.rows = List.of();
                }
            }
            return result;
        }
        List<String> ids = bean.getPartIds();
        if (ids == null) {
            return result;
        }
        List<WeblogEntry> requested = new ArrayList<>(ids.size());
        for (String id : ids) {
            requested.add(postById(id));
        }
        Map<String, String> refused = StrandRules.refusals(page, requested, ids);
        result.rows = acceptable(requested, refused);
        List<String> shown = bean.getShownPartIds() == null ? List.of() : bean.getShownPartIds();
        if (ids.equals(shown)) {
            return result;
        }
        // One line per reason: two foreign ids are one thing to fix.
        for (String key : new LinkedHashSet<>(refused.values())) {
            addFieldError(model, "page_strand_parts", key, request);
        }
        if (!refused.isEmpty()) {
            return result;
        }
        result.store = members(page, model, request)
                .map(members -> withPartsAddedElsewhere(requested, ids, shown, members)).orElse(null);
        return result;
    }

    /**
     * The posted parts, then every member that is in neither the posted nor
     * the shown list (appended elsewhere while the editor was open), in
     * stored order.
     */
    private static List<WeblogEntry> withPartsAddedElsewhere(List<WeblogEntry> posted, List<String> postedIds,
                                                             List<String> shownIds, List<WeblogEntry> members) {
        Set<String> known = new HashSet<>(postedIds);
        known.addAll(shownIds);
        List<WeblogEntry> parts = new ArrayList<>(posted);
        for (WeblogEntry member : members) {
            if (!known.contains(member.getId())) {
                parts.add(member);
            }
        }
        return parts;
    }

    /** The posts that can be parts, in posted order: unknown and refused ids dropped, a repeat shown once. */
    private static List<WeblogEntry> acceptable(List<WeblogEntry> requested, Map<String, String> refused) {
        List<WeblogEntry> rows = new ArrayList<>(requested.size());
        Set<String> seen = new HashSet<>();
        for (WeblogEntry entry : requested) {
            if (entry == null) {
                continue;
            }
            String reason = refused.get(entry.getId());
            if ((reason == null || "strand.error.duplicate".equals(reason)) && seen.add(entry.getId())) {
                rows.add(entry);
            }
        }
        return rows;
    }

    /**
     * Every current member of a stored strand, by position; none for a page
     * that is not one yet. Empty, with an error on the model, when they
     * cannot be read: a decision path, so it fails closed.
     */
    private Optional<List<WeblogEntry>> members(WeblogPage page, Model model, HttpServletRequest request) {
        if (!Boolean.TRUE.equals(page.getStrand())) {
            return Optional.of(List.of());
        }
        try {
            return Optional.of(weblogger.getWeblogEntryManager().getStrandParts(page, false));
        } catch (WebloggerException ex) {
            log.error("Error loading the parts of strand {}", page.getId(), ex);
            addError(model, "generic.error.check.logs", request);
            return Optional.empty();
        }
    }

    /**
     * Unticking a stored strand: allowed, with its parts cleared (an empty
     * list), when the author removed every row the panel showed and every
     * current member was among them; otherwise refused while any part is
     * left (empty, nothing to store).
     */
    private Optional<List<WeblogEntry>> checkedUntick(PageBean bean, WeblogPage page, Model model,
                                                      HttpServletRequest request) {
        List<String> shown = bean.getShownPartIds();
        if (bean.getPartIds() != null && bean.getPartIds().isEmpty() && shown != null) {
            Optional<List<WeblogEntry>> members = members(page, model, request);
            if (members.isEmpty()) {
                bean.setStrand(true);
                return Optional.empty();
            }
            List<WeblogEntry> current = members.get();
            if (!current.isEmpty() && current.stream().allMatch(m -> shown.contains(m.getId()))) {
                return Optional.of(List.of());
            }
        }
        checkUntick(bean, page, model, request);
        return Optional.empty();
    }

    private void checkUntick(PageBean bean, WeblogPage page, Model model, HttpServletRequest request) {
        try {
            long count = weblogger.getWeblogEntryManager().getStrandPartCount(page);
            if (count > 0) {
                addFieldError(model, "page_bean_strand", "pageEdit.error.strandHasParts",
                        new Object[] {count}, request);
                bean.setStrand(true);
            }
        } catch (WebloggerException ex) {
            // A count that could not be read is not a count of zero.
            log.error("Error counting the parts of strand {}", page.getId(), ex);
            addError(model, "generic.error.check.logs", request);
            bean.setStrand(true);
        }
    }

    private WeblogEntry postById(String id) {
        if (StringUtils.isBlank(id)) {
            return null;
        }
        try {
            return weblogger.getWeblogEntryManager().getWeblogEntry(id);
        } catch (WebloggerException ex) {
            log.error("Error looking up post {} for a strand", id, ex);
            return null;
        }
    }

    /**
     * Applies the form to the page and saves it. Every failure is reported
     * on the model and answers false: nothing here may turn a bad POST into
     * a 500.
     */
    private boolean saved(PageBean bean, WeblogPage page, Optional<List<WeblogEntry>> parts, Model model,
                          HttpServletRequest request) {
        // copyTo is inside the try, not before it, the same shape as
        // EntryEditController#doSave -- it is defensive against a garbage
        // status (see PageBean#parseStatus) but is still ordinary application
        // code reachable from a crafted POST.
        try {
            bean.copyTo(page);
            boolean isNew = StringUtils.isBlank(bean.getId());
            if (isNew && StringUtils.isBlank(page.getSlug())) {
                // A new page left without a slug is addressed by its title;
                // an existing page's emptied slug stays a mistake to report.
                page.setSlug(WeblogPage.slugFromTitle(page.getTitle()));
            }
            weblogger.getWeblogPageManager().savePage(page);
            if (parts.isPresent()) {
                // The parts' strand_page_id references the page row through a
                // plain id column, not a mapped relationship, so nothing
                // orders a new page's INSERT before their UPDATEs: commit it
                // first.
                if (isNew && !parts.get().isEmpty()) {
                    weblogger.flush();
                }
                weblogger.getWeblogEntryManager().setStrandParts(page, parts.get());
            }
            weblogger.flush();
            bean.copyFrom(page);
            addMessage(model, "pageEdit.saved", request);
            return true;
        } catch (SiteSlugClashException ex) {
            // The name is another author's blog handle, and
            // <spring:message arguments> does not escape.
            addFieldError(model, "page_bean_slug", ex.getMessageKey(),
                    new Object[] {StringEscapeUtils.escapeHtml4(ex.getArgument())}, request);
        } catch (WebloggerException ex) {
            // A reserved or malformed slug is the one expected failure mode
            // here (see WeblogPageManager#savePage), and it must surface as a
            // field error the author can fix, not a 500.
            String message = ex.getMessage() == null ? "" : ex.getMessage();
            if (message.contains("reserved")) {
                addFieldError(model, "page_bean_slug", "pageEdit.error.slugReserved", request);
            } else if (message.contains("slug")) {
                addFieldError(model, "page_bean_slug", "pageEdit.error.slugInvalid", request);
            } else {
                log.error("Error saving page {}", bean.getId(), ex);
                addError(model, "generic.error.check.logs", request);
            }
        } catch (Exception ex) {
            // Catch-all, matching EntryEditController#doSave's shape.
            log.error("Error saving page {}", bean.getId(), ex);
            addError(model, "generic.error.check.logs", request);
        }
        return false;
    }

    /**
     * Renders unsaved editor text exactly as the published page will, and
     * returns the HTML fragment the editor shows in its preview pane.
     *
     * <p>The twin of {@code EntryEditController#entryEditPreview}, and for the
     * same reason: only the server can expand {@code [gallery]}, {@code [map]}
     * or {@code [cta]}, so a browser-side Markdown library would disagree with
     * the published page precisely where an author needs to trust the preview.
     *
     * <p>It renders through {@code EntryRenderer.pageContent}, the page's own
     * production path, on a scratch {@link WeblogPage} rather than on the
     * looked-up row. The scratch page carries the weblog and -- when an id was
     * supplied -- that page's slug, which is all a {@code ShortcodeContext}
     * exposes; building one instead of mutating the managed entity keeps a
     * preview from ever dirtying the persistence context.
     *
     * <p>An id is client input, so it is ownership-checked the same way
     * {@code edit} and {@code save} check theirs. Without that, previewing
     * would be a way to ask what another weblog's shortcodes resolve to --
     * its media directories, its geo, its slug.
     */
    @PostMapping("/pageEdit!preview.rol")
    @ResponseBody
    public ResponseEntity<String> pageEditPreview(HttpServletRequest request,
                                                  @RequestParam(name = "id", required = false) String id,
                                                  @RequestParam(name = "text", required = false) String text) {
        Weblog weblog = getActionWeblog(request);
        if (weblog == null) {
            return ResponseEntity.notFound().build();
        }

        WeblogPage scratch = new WeblogPage();
        scratch.setWeblog(weblog);
        if (!StringUtils.isBlank(id)) {
            WeblogPage page = lookupPage(id, request);
            if (page == null) {
                return ResponseEntity.notFound().build();
            }
            scratch.setSlug(page.getSlug());
        }
        scratch.setContent(text == null ? "" : text);

        return ResponseEntity.ok()
                .contentType(MediaType.valueOf("text/html;charset=UTF-8"))
                .body(weblogger.getEntryRenderer().pageContent(scratch));
    }

    /**
     * @param postedRows the Parts panel rows a refused save redisplays, or
     *                   null to show the stored parts
     */
    private void addPageEditModelAttributes(HttpServletRequest request, Model model, PageBean bean,
                                            WeblogPage page, List<WeblogEntry> postedRows) {
        // The editor's insert menu, generated from the shortcode registry
        // itself so it can never advertise a shortcode that does not render,
        // or omit one that does. Pages go through the same shortcode-expanding
        // render pipeline as entries (WeblogPage implements ShortcodeContext).
        model.addAttribute("shortcodeCards", weblogger.getEntryRenderer().shortcodeCards());

        // The front-door page is served at the site root, not at its slug; the
        // editor says so rather than leaving the slug line to suggest otherwise.
        model.addAttribute("frontDoorPage", getActionWeblog(request).isSiteSpace()
                && WebloggerRuntimeConfig.isFrontDoorPage(bean.getSlug()));

        // The document the editor's split/preview panes frame -- the same
        // shell the entry editor uses, through the same BaseController helper,
        // so the two screens cannot come to preview against different
        // documents.
        model.addAttribute("previewShellURL", previewShellURL(getActionWeblog(request)));

        // Thumbnail preview for the SEO panel's social-image picker. Read off
        // the bean rather than the page so a save that failed validation
        // still shows the image the author picked in the form.
        String ogImageThumbnailUrl = pickedImageThumbnailURL(bean.getOgImageId(), request);
        if (ogImageThumbnailUrl != null) {
            model.addAttribute("ogImageThumbnailUrl", ogImageThumbnailUrl);
        }

        addStrandModelAttributes(getActionWeblog(request), page, bean, postedRows, model);
    }

    /**
     * What the Parts panel renders from: its rows (every member of a stored
     * strand, any status, by position -- or, after a refused save, the rows
     * the author posted), the ids it was built with ({@code strandShownIds},
     * posted back for the save to tell a removed row from a part added
     * elsewhere), and this blog's posts that are in no strand (or were
     * removed from this one), not in the trash and not already a row, for its
     * add control. A failed lookup here is a display path (the save
     * validates every id again), so it shows an empty list rather than
     * failing the editor. Site pages have no panel.
     */
    private void addStrandModelAttributes(Weblog weblog, WeblogPage page, PageBean bean,
                                          List<WeblogEntry> postedRows, Model model) {
        if (weblog.isSiteSpace()) {
            return;
        }
        List<WeblogEntry> parts = List.of();
        List<String> shownIds;
        if (postedRows != null) {
            // The list the panel was first built with, kept across the
            // redisplay so the next save still tells removed from added.
            parts = postedRows;
            shownIds = bean.getShownPartIds() == null ? List.of() : bean.getShownPartIds();
        } else {
            if (page != null && Boolean.TRUE.equals(page.getStrand())) {
                try {
                    parts = weblogger.getWeblogEntryManager().getStrandParts(page, false);
                } catch (WebloggerException ex) {
                    log.error("Error loading the parts of strand {}", page.getId(), ex);
                }
            }
            shownIds = parts.stream().map(WeblogEntry::getId).toList();
        }
        Set<String> rowIds = new HashSet<>();
        parts.forEach(part -> rowIds.add(part.getId()));
        String strandId = page == null ? null : page.getId();
        List<WeblogEntry> candidates = List.of();
        try {
            WeblogEntrySearchCriteria criteria = new WeblogEntrySearchCriteria();
            criteria.setWeblog(weblog);
            candidates = weblogger.getWeblogEntryManager().getWeblogEntries(criteria).stream()
                    .filter(e -> (e.getStrandPageId() == null || e.getStrandPageId().equals(strandId))
                            && e.getStatus() != WeblogEntry.PubStatus.TRASHED && !rowIds.contains(e.getId()))
                    .toList();
        } catch (WebloggerException ex) {
            log.error("Error listing posts for the strand panel of weblog {}", weblog.getHandle(), ex);
        }
        List<WeblogEntry> titled = new ArrayList<>(parts);
        titled.addAll(candidates);
        model.addAttribute("strandParts", parts);
        model.addAttribute("strandShownIds", shownIds);
        model.addAttribute("strandCandidates", candidates);
        model.addAttribute("entryTitles", EntriesController.plainTitles(titled));
    }

    /**
     * Where a denied lookup goes: back to the list, same as
     * {@code TemplateEditController} falling back to {@code .Templates}. An
     * unknown id and a foreign one are deliberately indistinguishable to the
     * caller.
     */
    private static final String DENIED_VIEW = ".Pages";

    @ModelAttribute("bean")
    public PageBean getBean() {
        return new PageBean();
    }
}
