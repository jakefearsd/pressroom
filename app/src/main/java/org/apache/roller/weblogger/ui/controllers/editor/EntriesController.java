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

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.text.StringEscapeUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.roller.weblogger.WebloggerException;
import org.apache.roller.weblogger.business.WeblogEntryManager;
import org.apache.roller.weblogger.pojos.WeblogCategory;
import org.apache.roller.weblogger.pojos.WeblogEntry;
import org.apache.roller.weblogger.pojos.WeblogEntry.PubStatus;
import org.apache.roller.weblogger.pojos.WeblogEntrySearchCriteria;
import org.apache.roller.weblogger.pojos.WeblogPermission;
import org.apache.roller.weblogger.ui.controllers.BaseController;
import org.apache.roller.weblogger.util.URLUtilities;
import org.apache.roller.weblogger.util.cache.CacheManager;
import org.apache.roller.weblogger.ui.controllers.pagers.EntriesPager;
import org.apache.roller.weblogger.ui.controllers.util.KeyValueObject;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * A list view of entries in a weblog.
 */
@Controller
@RequestMapping("/roller-ui/authoring")
public class EntriesController extends BaseController {

    private static final Logger log = LoggerFactory.getLogger(EntriesController.class);
    private static final int COUNT = 30;

    /**
     * Context-RELATIVE, with no {@code getRelativeContextURL()} prefix: this
     * feeds a {@code redirect:} view name, which Spring resolves with
     * {@code redirectContextRelative=true} and so prefixes the context path
     * itself. See {@link #backToList}.
     */
    private static final String ENTRIES_PATH = "/roller-ui/authoring/entries.rol";

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
        return "entries";
    }

    @Override
    public String getPageTitle() {
        return "weblogEntryQuery.title";
    }

    @GetMapping("/entries.rol")
    public String execute(HttpServletRequest request, Model model,
                          @ModelAttribute("bean") EntriesBean bean) {
        populateCommonModel(request, model);

        List<WeblogEntry> entries = null;
        boolean hasMore = false;
        try {
            String status = bean.getStatus();
            WeblogEntryManager wmgr = weblogger.getWeblogEntryManager();
            WeblogEntrySearchCriteria wesc = new WeblogEntrySearchCriteria();
            wesc.setWeblog(getActionWeblog(request));
            wesc.setStartDate(bean.getStartDate());
            wesc.setEndDate(bean.getEndDate());
            wesc.setCatName(bean.getCategoryName());
            wesc.setTags(bean.getTags());
            // A blank status means the same thing as "ALL" (task B5/B6's
            // EntriesSidebar.jsp guards its hidden bean.status input against
            // posting one, but that is a JSP-side belt; a query string built
            // by hand, or a form whose guard regresses, still reaches here --
            // and PubStatus.valueOf("") is an uncaught IllegalArgumentException,
            // outside the catch below, i.e. a 500 rather than an unfiltered
            // list).
            wesc.setStatus(StringUtils.isBlank(status) || "ALL".equals(status)
                    ? null : WeblogEntry.PubStatus.valueOf(status));
            wesc.setText(bean.getText());
            wesc.setSortBy(bean.getSortBy());
            wesc.setOffset(bean.getPage() * COUNT);
            wesc.setMaxResults(COUNT + 1);
            List<WeblogEntry> rawEntries = wmgr.getWeblogEntries(wesc);
            entries = new ArrayList<>(rawEntries);
            if (!entries.isEmpty()) {
                if (rawEntries.size() > COUNT) {
                    entries.remove(entries.size() - 1);
                    hasMore = true;
                }
                model.addAttribute("firstEntry", entries.get(0));
                model.addAttribute("lastEntry", entries.get(entries.size() - 1));
            }
        } catch (WebloggerException ex) {
            log.error("Error looking up entries", ex);
            addError(model, "generic.error.check.logs", request);
        }

        String baseUrl = buildBaseUrl(request, bean);
        model.addAttribute("pager", new EntriesPager(baseUrl, bean.getPage(), entries, hasMore));
        model.addAttribute("entryTitles", plainTitles(entries));
        model.addAttribute("filtered", isFiltered(bean));
        model.addAttribute("categories", getCategories(request));
        model.addAttribute("sortByOptions", getSortByOptions(request));
        List<KeyValueObject> statusOptions = getStatusOptions(request);
        model.addAttribute("statusOptions", statusOptions);
        model.addAttribute("statusChipUrls", statusChipUrls(request, bean, statusOptions));

        return ".Entries";
    }

    /**
     * Copies an entry and opens the copy in the editor.
     *
     * <p>The copy is a draft with a fresh id and no anchor of its own (see
     * {@link WeblogEntry#copyAsDraft}), so nothing about the original changes
     * and nothing new appears on the blog. The redirect goes to the editor
     * rather than back to the list, because an author duplicates a post in
     * order to edit it, and a draft named "Copy of ..." sitting in the list is
     * not a finished outcome.
     */
    @PostMapping("/entries!duplicate.rol")
    public String duplicate(HttpServletRequest request,
                            @RequestParam(value = "duplicateId", required = false) String duplicateId,
                            RedirectAttributes redirectAttributes) {
        String handle = getActionWeblog(request).getHandle();
        String backToList = "redirect:/roller-ui/authoring/entries.rol?weblog=" + handle;

        // lookupNonTrashedEntry, not lookupEntry: a forged duplicateId
        // naming a trashed entry must not be usable to duplicate it back
        // into a live draft -- that is what restore is for.
        WeblogEntry original = lookupNonTrashedEntry(duplicateId, request);
        if (original == null) {
            addFlashError(redirectAttributes, "weblogEntry.notFound", request);
            return backToList;
        }

        try {
            WeblogEntry copy = original.copyAsDraft(getText("weblogEdit.duplicateTitle",
                    new Object[] {original.getTitle()}, request));
            weblogger.getWeblogEntryManager().saveWeblogEntry(copy);
            weblogger.flush();

            addFlashMessage(redirectAttributes, "weblogEdit.entryDuplicated",
                    copy.getTitle(), request);
            return "redirect:/roller-ui/authoring/entryEdit.rol?weblog=" + handle
                    + "&bean.id=" + copy.getId();
        } catch (Exception e) {
            log.error("Error duplicating entry {}", duplicateId, e);
            addFlashError(redirectAttributes, "generic.error.check.logs", request);
            return backToList;
        }
    }

    // ---------------------------------------------------------- bulk actions

    /**
     * Publishes every selected entry.
     *
     * <p>An author without POST permission can only submit for review, exactly
     * as the editor's own publish button does -- a bulk action must not be a
     * way around a permission the single-entry path enforces.
     */
    @PostMapping("/entries!bulkPublish.rol")
    public String bulkPublish(HttpServletRequest request,
                              @RequestParam(value = "selectedEntries", required = false)
                              List<String> selectedEntries,
                              @ModelAttribute("bean") EntriesBean bean,
                              RedirectAttributes redirectAttributes) {
        boolean mayPublish = hasWeblogAction(getActionWeblog(request),
                getAuthenticatedUser(request), WeblogPermission.POST);
        PubStatus target = mayPublish ? PubStatus.PUBLISHED : PubStatus.PENDING;

        return applyToSelection(request, selectedEntries, bean, redirectAttributes,
                mayPublish ? "weblogEntryQuery.bulkPublished" : "weblogEntryQuery.bulkSubmitted",
                entry -> {
                    entry.setStatus(target);
                    entry.setUpdateTime(new Timestamp(System.currentTimeMillis()));
                    if (entry.isPublished() && entry.getPubTime() == null) {
                        entry.setPubTime(entry.getUpdateTime());
                    }
                    weblogger.getWeblogEntryManager().saveWeblogEntry(entry);

                    if (entry.isPublished()) {
                        weblogger.getIndexManager().addEntryReIndexOperation(entry);
                    }
                    CacheManager.invalidate(entry);
                });
    }

    /** Adds one tag to every selected entry, leaving the tags they already carry. */
    @PostMapping("/entries!bulkTag.rol")
    public String bulkTag(HttpServletRequest request,
                          @RequestParam(value = "selectedEntries", required = false)
                          List<String> selectedEntries,
                          @RequestParam(value = "bulkTag", required = false) String bulkTag,
                          @ModelAttribute("bean") EntriesBean bean,
                          RedirectAttributes redirectAttributes) {
        String tag = StringUtils.trimToNull(bulkTag);
        if (tag == null) {
            addFlashError(redirectAttributes, "weblogEntryQuery.bulkTagMissing", request);
            return backToList(request, bean);
        }

        return applyToSelection(request, selectedEntries, bean, redirectAttributes,
                "weblogEntryQuery.bulkTagged",
                entry -> {
                    entry.addTag(tag);
                    weblogger.getWeblogEntryManager().saveWeblogEntry(entry);
                    CacheManager.invalidate(entry);
                });
    }

    /**
     * Trashes every selected entry, each through the same path the single-entry
     * delete uses -- a bulk JPQL delete would leave the search index holding
     * documents for entries that no longer exist.
     */
    @PostMapping("/entries!bulkDelete.rol")
    public String bulkDelete(HttpServletRequest request,
                             @RequestParam(value = "selectedEntries", required = false)
                             List<String> selectedEntries,
                             @ModelAttribute("bean") EntriesBean bean,
                             RedirectAttributes redirectAttributes) {
        return applyToSelection(request, selectedEntries, bean, redirectAttributes,
                "weblogEntryQuery.bulkDeleted", this::trashEntryWithIndex);
    }

    /** What one bulk action does to one entry, once it has been found and vetted. */
    @FunctionalInterface
    private interface EntryAction {
        void apply(WeblogEntry entry) throws WebloggerException;
    }

    /**
     * The shared body of every bulk action: resolve each selected id through
     * the ownership check, apply the action, and report honestly.
     *
     * <p>Two decisions worth stating. First, each id goes through
     * {@code lookupEntry} individually -- the ids are client input, and a
     * single foreign id slipped into the form would otherwise let an editor on
     * one weblog rewrite or delete another's posts. Second, an id that fails is
     * counted and reported rather than aborting the batch: rolling the whole
     * selection back because one entry was deleted in another tab would be a
     * worse answer than doing the other nineteen and saying so.
     */
    private String applyToSelection(HttpServletRequest request, List<String> selectedEntries,
                                    EntriesBean bean,
                                    RedirectAttributes redirectAttributes, String successKey,
                                    EntryAction action) {
        if (selectedEntries == null || selectedEntries.isEmpty()) {
            addFlashError(redirectAttributes, "weblogEntryQuery.bulkNothingSelected", request);
            return backToList(request, bean);
        }

        int applied = 0;
        List<String> failed = new ArrayList<>();
        for (String id : selectedEntries) {
            // lookupNonTrashedEntry, not lookupEntry: a trashed id slipped
            // into a bulk selection must not be actionable -- bulkPublish in
            // particular would otherwise resurrect a trashed entry straight
            // to PUBLISHED, bypassing restore entirely.
            WeblogEntry entry = lookupNonTrashedEntry(id, request);
            if (entry == null) {
                failed.add(id);
                continue;
            }
            try {
                action.apply(entry);
                applied++;
            } catch (Exception e) {
                log.error("Bulk action failed for entry {}", id, e);
                failed.add(id);
            }
        }

        try {
            weblogger.flush();
        } catch (WebloggerException e) {
            log.error("Error flushing bulk action", e);
            addFlashError(redirectAttributes, "generic.error.check.logs", request);
            return backToList(request, bean);
        }

        if (applied > 0) {
            addFlashMessage(redirectAttributes, successKey, String.valueOf(applied), request);
        }
        if (!failed.isEmpty()) {
            addFlashError(redirectAttributes, "weblogEntryQuery.bulkFailed",
                    String.valueOf(failed.size()), request);
        }
        return backToList(request, bean);
    }

    /**
     * Where a bulk action returns to: the same filtered list it was run from.
     * Without the filter, deleting three drafts out of a status=DRAFT view
     * dropped the author into the unfiltered list of everything, with nothing
     * on screen saying the filter had been discarded -- so the next bulk
     * selection is made against a different set of rows than the one just
     * acted on.
     *
     * <p><strong>It builds the path itself instead of reusing
     * {@link #buildBaseUrl}, and that is the point.</strong> {@code
     * buildBaseUrl} goes through {@code getActionURL(..., absolute=false)},
     * which prefixes {@code WebloggerRuntimeConfig.getRelativeContextURL()} --
     * correct for an href a browser resolves, wrong for a {@code redirect:}
     * view name, because Spring's {@code InternalResourceViewResolver} has
     * {@code redirectContextRelative=true} and prepends the context path
     * AGAIN. Under a servlet prefix that produced {@code
     * /roller/roller/roller-ui/authoring/entries.rol} and a 404 -- invisible
     * at the root context, where both prefixes are empty. The query string
     * still comes from the shared {@link #filterParams}, so the redirect and
     * the pager's base url cannot drift apart on which filters they carry.
     *
     * <p>A null bean is tolerated: a POST carrying no filter fields at all
     * binds an empty bean, and every field is optional anyway.
     */
    private String backToList(HttpServletRequest request, EntriesBean bean) {
        Map<String, String> params = new HashMap<>();
        params.put("weblog", getActionWeblog(request).getHandle());
        if (bean != null) {
            params.putAll(filterParams(bean));
            // Two of the bean's fields have non-null DEFAULTS. bean.sortBy is
            // still never posted by the bulk form (see Entries.jsp), so on
            // this path it is never an author's choice -- only the default
            // wearing one -- and echoing it would pin UPDATE_TIME on someone
            // who had been sorting by publication time. bean.status IS
            // posted by the bulk form now (unlike sortBy), so it cannot be
            // unconditionally dropped the same way; the value-conditional
            // remove below is what keeps only its default, "ALL", out of the
            // url -- an author's real filter choice still survives the
            // round trip. "ALL" is what this controller already treats as
            // "no status filter" at the query itself, so repeating it in the
            // url would say nothing anyway.
            params.remove("bean.sortBy");
            params.remove("bean.status", "ALL");
        }
        return "redirect:" + ENTRIES_PATH + URLUtilities.getQueryString(params);
    }

    /**
     * Whether the author narrowed the list, so an empty result means "nothing
     * matched" rather than "this weblog has no entries". The sort order and
     * the "ALL" status are the defaults wearing a value, not filters -- the
     * same two {@link #backToList} drops for the same reason.
     */
    private boolean isFiltered(EntriesBean bean) {
        Map<String, String> params = filterParams(bean);
        params.remove("bean.sortBy");
        params.remove("bean.status", "ALL");
        return !params.isEmpty();
    }

    /** Each entry's {@link #plainTitle}, by entry id; empty for a failed lookup. */
    public static Map<String, String> plainTitles(List<WeblogEntry> entries) {
        Map<String, String> titles = new HashMap<>();
        if (entries != null) {
            for (WeblogEntry entry : entries) {
                titles.put(entry.getId(), plainTitle(entry));
            }
        }
        return titles;
    }

    /**
     * An entry's display title as plain text, for a JSP to escape exactly once
     * with {@code fn:escapeXml} -- in element content and attributes alike.
     *
     * <p>Neither stored form is safe to print as it stands. A title is stored
     * HTML-escaped (see {@code EntryFieldRules.escapeTitle}), so escaping it
     * again shows "Tom &amp;amp; Jerry" and a screen reader reads "amp". An
     * UNTITLED entry's {@code getDisplayTitle()} is its body with tags removed
     * -- not escaped, and {@code Utilities.removeHTML} keeps an unterminated
     * tag verbatim -- so printing it bare is markup injection. Unescaping the
     * one and leaving the other as text gives both the same shape: raw text.
     */
    public static String plainTitle(WeblogEntry entry) {
        return StringEscapeUtils.unescapeHtml4(entry.getDisplayTitle());
    }

    // Package-private rather than public: widened only so the test can call it
    // directly, per this repo's existing precedent for testing a pager's base
    // url without going through the full servlet stack.
    String buildBaseUrl(HttpServletRequest request, EntriesBean bean) {
        return weblogger.getUrlStrategy().getActionURL("entries", "/roller-ui/authoring",
                getActionWeblog(request).getHandle(), filterParams(bean), false);
    }

    /**
     * The filter the author is looking at, as request parameters. One source
     * for the pager's base url, the bulk actions' return redirect, and (via
     * Entries.jsp's hidden inputs) the bulk form's own POST body -- a filter
     * that survives paging but not deleting, or the reverse, is the shape this
     * shares one method to avoid.
     */
    private Map<String, String> filterParams(EntriesBean bean) {
        Map<String, String> params = new HashMap<>();
        if (!StringUtils.isEmpty(bean.getCategoryName())) {
            params.put("bean.categoryName", bean.getCategoryName());
        }
        if (!StringUtils.isEmpty(bean.getTagsAsString())) {
            params.put("bean.tagsAsString", bean.getTagsAsString());
        }
        if (!StringUtils.isEmpty(bean.getText())) {
            params.put("bean.text", bean.getText());
        }
        if (!StringUtils.isEmpty(bean.getStartDateString())) {
            params.put("bean.startDateString", bean.getStartDateString());
        }
        if (!StringUtils.isEmpty(bean.getEndDateString())) {
            params.put("bean.endDateString", bean.getEndDateString());
        }
        if (!StringUtils.isEmpty(bean.getStatus())) {
            params.put("bean.status", bean.getStatus());
        }
        if (bean.getSortBy() != null) {
            params.put("bean.sortBy", bean.getSortBy().toString());
        }
        return params;
    }

    /**
     * One url per status chip, each carrying the author's whole filter with
     * only the status swapped.
     *
     * <p>The status filter used to be a radio set inside the sidebar's filter
     * form, where carrying the rest of the filter was free -- submitting the
     * form posted every other field along with it. A chip is a plain link with
     * no form behind it, so each url has to be built with the filter baked in
     * or clicking "Drafts" silently discards the text search, tag, category
     * and date range the author had narrowed to. That loss is invisible while
     * developing, because nobody clicks a chip on an unfiltered list.
     *
     * <p>It builds on {@link #filterParams}, the same source the pager's base
     * url and the bulk-action redirect use, so the three cannot disagree about
     * which filters survive a click. {@code bean.page} is deliberately not
     * among them: changing the status filter changes the result set, and page
     * 4 of the old one means nothing in the new one.
     *
     * <p>"ALL" carries no status parameter at all rather than the literal
     * sentinel -- {@link #execute} already reads "ALL" as no filter, so
     * spelling it out would lengthen a url the author can see while saying
     * nothing.
     */
    private Map<String, String> statusChipUrls(HttpServletRequest request, EntriesBean bean,
                                               List<KeyValueObject> statusOptions) {
        Map<String, String> shared = filterParams(bean);
        shared.remove("bean.status");
        String handle = getActionWeblog(request).getHandle();

        Map<String, String> urls = new LinkedHashMap<>();
        for (KeyValueObject option : statusOptions) {
            String key = String.valueOf(option.getKey());
            Map<String, String> params = new HashMap<>(shared);
            if (!"ALL".equals(key)) {
                params.put("bean.status", key);
            }
            urls.put(key, weblogger.getUrlStrategy().getActionURL(
                    "entries", "/roller-ui/authoring", handle, params, false));
        }
        return urls;
    }

    // No synthetic "Any" entry here -- that choice is an <option value="">
    // the JSP emits directly (EntriesSidebar.jsp), since a blank categoryName
    // already means "no filter" in WeblogEntrySearchCriteria.
    private List<WeblogCategory> getCategories(HttpServletRequest request) {
        List<WeblogCategory> cats = new ArrayList<>();
        try {
            WeblogEntryManager wmgr = weblogger.getWeblogEntryManager();
            cats.addAll(wmgr.getWeblogCategories(getActionWeblog(request)));
        } catch (WebloggerException ex) {
            log.error("Error getting category list", ex);
        }
        return cats;
    }

    private List<KeyValueObject> getSortByOptions(HttpServletRequest request) {
        List<KeyValueObject> opts = new ArrayList<>();
        opts.add(new KeyValueObject(WeblogEntrySearchCriteria.SortBy.PUBLICATION_TIME.name(),
                getText("weblogEntryQuery.label.pubTime", request)));
        opts.add(new KeyValueObject(WeblogEntrySearchCriteria.SortBy.UPDATE_TIME.name(),
                getText("weblogEntryQuery.label.updateTime", request)));
        return opts;
    }

    private List<KeyValueObject> getStatusOptions(HttpServletRequest request) {
        List<KeyValueObject> opts = new ArrayList<>();
        opts.add(new KeyValueObject("ALL", getText("weblogEntryQuery.label.allEntries", request)));
        opts.add(new KeyValueObject("DRAFT", getText("weblogEntryQuery.label.draftOnly", request)));
        opts.add(new KeyValueObject("PUBLISHED", getText("weblogEntryQuery.label.publishedOnly", request)));
        opts.add(new KeyValueObject("PENDING", getText("weblogEntryQuery.label.pendingOnly", request)));
        opts.add(new KeyValueObject("SCHEDULED", getText("weblogEntryQuery.label.scheduledOnly", request)));
        return opts;
    }

    @ModelAttribute("bean")
    public EntriesBean getBean() {
        return new EntriesBean();
    }
}
