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

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.pojos.WeblogCategory;
import com.wikantik.pressroom.pojos.WeblogEntry;
import com.wikantik.pressroom.pojos.WeblogEntrySearchCriteria;
import com.wikantik.pressroom.util.URLUtilities;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ui.Model;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link EntriesController}.
 *
 * <p>The controller asks the manager for one row more than a page ({@code
 * COUNT + 1}) purely to learn whether a next page exists, then has to trim
 * that lookahead row back off before the page ever sees it - get that
 * arithmetic wrong and the list either drops a real entry or shows a
 * "next page" link that leads nowhere. The status filter's "ALL" case is a
 * sentinel that must map to a null {@code PubStatus} rather than an
 * enum value. The category list is the weblog's own categories with no
 * synthetic "Any" entry -- that choice is an {@code <option value="">} the
 * JSP emits directly, since a blank {@code categoryName} already means "no
 * filter" in the search criteria.
 */
class EntriesControllerTest extends EditorControllerTestSupport {

    private static final int COUNT = 30;

    private EntriesController controller;
    private Model model;
    private EntriesBean bean;

    @BeforeEach
    void setUp() throws Exception {
        controller = prepare(new EntriesController());
        model = newModel();
        bean = new EntriesBean();

        // Every test exercises getCategories() and the entries query
        // unconditionally, so both need a non-null default: an unstubbed mock
        // returns null, and this controller does not guard against that -
        // only against the checked WebloggerException.
        when(weblogger.getWeblogEntryManager().getWeblogEntries(any())).thenReturn(new ArrayList<>());
        when(weblogger.getWeblogEntryManager().getWeblogCategories(weblog)).thenReturn(new ArrayList<>());
    }

    @Test
    void aNormalQueryBuildsAPagerWithTheReturnedEntriesAndNoMorePage() throws Exception {
        List<WeblogEntry> entries = List.of(entryNamed("entry-1"), entryNamed("entry-2"));
        when(weblogger.getWeblogEntryManager().getWeblogEntries(any())).thenReturn(entries);

        String view = controller.execute(request, model, bean);

        assertEquals(".Entries", view);
        EntriesPagerLike pager = pager(model);
        assertEquals(entries, pager.items);
        assertFalse(pager.moreItems, "Two results out of a 30-row page must not claim a next page");
    }

    @Test
    void aFullPageWithOneExtraRowIsTrimmedAndFlaggedAsHavingMore() throws Exception {
        // COUNT+1 rows is exactly what the controller asks the manager for; the
        // 31st row is the lookahead and must never reach the page.
        List<WeblogEntry> overflowing = new ArrayList<>();
        for (int i = 0; i < COUNT + 1; i++) {
            overflowing.add(entryNamed("entry-" + i));
        }
        when(weblogger.getWeblogEntryManager().getWeblogEntries(any())).thenReturn(overflowing);

        controller.execute(request, model, bean);

        EntriesPagerLike pager = pager(model);
        assertEquals(COUNT, pager.items.size(), "The lookahead row must be trimmed off before display");
        assertEquals(overflowing.subList(0, COUNT), pager.items);
        assertTrue(pager.moreItems, "A trimmed lookahead row means a next page exists");
    }

    @Test
    void statusAllMapsToANullPubStatusFilter() throws Exception {
        bean.setStatus("ALL");

        controller.execute(request, model, bean);

        ArgumentCaptor<WeblogEntrySearchCriteria> captor =
                ArgumentCaptor.forClass(WeblogEntrySearchCriteria.class);
        org.mockito.Mockito.verify(weblogger.getWeblogEntryManager()).getWeblogEntries(captor.capture());
        assertNull(captor.getValue().getStatus(), "\"ALL\" must not be passed through as a literal enum value");
    }

    /**
     * Folded in from B5's round-1 fix (task-B5-report.md, "Held back"):
     * {@code EntriesBean.status} defaults to {@code "ALL"}, but a request
     * that posts {@code bean.status=} (empty, not absent) binds an empty
     * string over that default. Before this fix, only the literal "ALL" was
     * treated as "no filter" and {@code PubStatus.valueOf("")} threw an
     * uncaught {@code IllegalArgumentException} -- outside the
     * {@code catch (WebloggerException)} below, i.e. a 500. A blank status
     * must mean the same thing as "ALL". This is a second line of defence
     * behind EntriesSidebar.jsp's own {@code <c:if test="${not empty
     * bean.status}">} guard on its hidden field, for any other caller
     * (a hand-built query string, the API) that reaches this controller with
     * an explicitly empty status.
     */
    @Test
    void aBlankStatusIsTreatedTheSameAsAllRatherThanThrowing() throws Exception {
        bean.setStatus("");

        controller.execute(request, model, bean);

        ArgumentCaptor<WeblogEntrySearchCriteria> captor =
                ArgumentCaptor.forClass(WeblogEntrySearchCriteria.class);
        org.mockito.Mockito.verify(weblogger.getWeblogEntryManager()).getWeblogEntries(captor.capture());
        assertNull(captor.getValue().getStatus(), "a blank status must not be passed through to PubStatus.valueOf");
    }

    @Test
    void aSpecificStatusIsPassedThroughAsItsPubStatusEnumValue() throws Exception {
        bean.setStatus("DRAFT");

        controller.execute(request, model, bean);

        ArgumentCaptor<WeblogEntrySearchCriteria> captor =
                ArgumentCaptor.forClass(WeblogEntrySearchCriteria.class);
        org.mockito.Mockito.verify(weblogger.getWeblogEntryManager()).getWeblogEntries(captor.capture());
        assertEquals(WeblogEntry.PubStatus.DRAFT, captor.getValue().getStatus());
    }

    @Test
    void categoriesAreTheWeblogsOwnWithNoSyntheticAnyEntry() throws Exception {
        // The "Any" choice is now an <option value=""> the JSP emits directly,
        // not a synthetic WeblogCategory the controller invents -- blank
        // categoryName already means "no filter" in the search criteria.
        WeblogCategory travel = new WeblogCategory();
        travel.setName("Travel");
        when(weblogger.getWeblogEntryManager().getWeblogCategories(weblog)).thenReturn(List.of(travel));

        controller.execute(request, model, bean);

        @SuppressWarnings("unchecked")
        List<WeblogCategory> categories = (List<WeblogCategory>) model.getAttribute("categories");
        assertEquals(1, categories.size());
        assertEquals("Travel", categories.get(0).getName());
    }

    @Test
    void categoriesIsEmptyRatherThanNullWhenTheCategoryLookupFails() throws Exception {
        when(weblogger.getWeblogEntryManager().getWeblogCategories(weblog))
                .thenThrow(new WebloggerException("database down"));

        controller.execute(request, model, bean);

        @SuppressWarnings("unchecked")
        List<WeblogCategory> categories = (List<WeblogCategory>) model.getAttribute("categories");
        assertTrue(categories.isEmpty(),
                "A failed category lookup must still leave the form something to bind to");
    }

    @Test
    void sortByOptionsOffersExactlyTheTwoSupportedSortFields() throws Exception {
        controller.execute(request, model, bean);

        assertEquals(2, ((List<?>) model.getAttribute("sortByOptions")).size());
    }

    @Test
    void statusOptionsOffersExactlyTheFiveSupportedStatuses() throws Exception {
        controller.execute(request, model, bean);

        assertEquals(5, ((List<?>) model.getAttribute("statusOptions")).size());
    }

    /**
     * A status chip changes exactly ONE thing about the query and leaves every
     * other filter where the author put it.
     *
     * <p>The chips replaced a radio set that lived inside the sidebar's filter
     * form, where "carry the rest of the filter" was free -- the form posted
     * all of its own fields. A link has no such form behind it, so each chip
     * url has to be built with the whole filter baked in, or clicking "Drafts"
     * silently discards the text search, the tag, the category and the date
     * range the author had narrowed to. That is the failure this test exists
     * for: it is invisible on an unfiltered list, which is the only state
     * anyone clicks a chip in while developing.
     *
     * <p>The urls come from the same {@code filterParams} the pager's base url
     * and the bulk-action redirect are built from, so the three cannot drift
     * on which filters they carry.
     */
    @Test
    void aStatusChipUrlCarriesEveryOtherFilterTheAuthorHasSet() throws Exception {
        bean.setStatus("PUBLISHED");
        bean.setText("cinque terre");
        bean.setTagsAsString("liguria");
        bean.setCategoryName("Travel");
        bean.setStartDateString("2026-01-01");
        bean.setEndDateString("2026-12-31");
        stubActionUrlWithContextPath("");

        controller.execute(request, model, bean);

        @SuppressWarnings("unchecked")
        Map<String, String> chipUrls = (Map<String, String>) model.getAttribute("statusChipUrls");
        assertNotNull(chipUrls, "the JSP has no chip urls to render");
        String draftChip = chipUrls.get("DRAFT");
        assertNotNull(draftChip, "no chip url for DRAFT");

        Map<String, String> params = queryParams(draftChip);
        assertEquals("cinque terre", params.get("bean.text"), draftChip);
        assertEquals("liguria", params.get("bean.tagsAsString"), draftChip);
        assertEquals("Travel", params.get("bean.categoryName"), draftChip);
        assertEquals("2026-01-01", params.get("bean.startDateString"), draftChip);
        assertEquals("2026-12-31", params.get("bean.endDateString"), draftChip);
        assertEquals(WEBLOG_HANDLE, params.get("weblog"), draftChip);

        // The one thing the chip does change.
        assertEquals("DRAFT", params.get("bean.status"), draftChip);
    }

    /**
     * A filter value full of query punctuation reaches the chip intact.
     *
     * <p>This is the case that used to be corrupted three different ways at
     * once, none of them visible on the page: {@code &} started a new
     * parameter (so the search became "R"), {@code #} started the fragment and
     * took every parameter after it -- {@code bean.status} included -- out of
     * the request entirely, and {@code +} came back as a space. Escaping the
     * href with {@code fn:escapeXml} does not touch any of it, because the
     * browser decodes {@code &amp;} back to {@code &} before building the
     * request; the encoding has to happen when the url is built.
     *
     * <p>Note this asserts on the DECODED parameters, so it is a statement
     * about what the next request will carry rather than about a particular
     * spelling of the escape.
     */
    @Test
    void aStatusChipUrlKeepsAFilterValueThatIsFullOfQueryPunctuation() throws Exception {
        bean.setStatus("ALL");
        bean.setText("R&D #1");
        bean.setCategoryName("C# / R+D");
        stubActionUrlWithContextPath("");

        controller.execute(request, model, bean);

        @SuppressWarnings("unchecked")
        Map<String, String> chipUrls = (Map<String, String>) model.getAttribute("statusChipUrls");
        String draftChip = chipUrls.get("DRAFT");

        Map<String, String> params = queryParams(draftChip);
        assertEquals("R&D #1", params.get("bean.text"), draftChip);
        assertEquals("C# / R+D", params.get("bean.categoryName"), draftChip);
        // The status filter survives a '#' earlier in the query -- unencoded it
        // would have been swallowed by the fragment along with everything else.
        assertEquals("DRAFT", params.get("bean.status"), draftChip);
    }

    /**
     * "All" means no status filter, so its chip carries no status parameter at
     * all rather than the sentinel string -- {@code execute} already treats a
     * literal "ALL" as no filter, so putting it in the url would say nothing
     * while making the url the author sees longer.
     */
    @Test
    void theAllChipClearsTheStatusParameterRatherThanSpellingOutTheSentinel() throws Exception {
        bean.setStatus("DRAFT");
        stubActionUrlWithContextPath("");

        controller.execute(request, model, bean);

        @SuppressWarnings("unchecked")
        Map<String, String> chipUrls = (Map<String, String>) model.getAttribute("statusChipUrls");
        String allChip = chipUrls.get("ALL");
        assertNotNull(allChip, "no chip url for ALL");
        assertFalse(allChip.contains("bean.status="), allChip);
    }

    @Test
    void aFailedEntryLookupIsReportedAndStillProducesAPager() throws Exception {
        when(weblogger.getWeblogEntryManager().getWeblogEntries(any()))
                .thenThrow(new WebloggerException("database down"));

        String view = controller.execute(request, model, bean);

        assertEquals(".Entries", view, "The list page must still render after a failed query");
        assertTrue(errors(model).contains("generic.error.check.logs"),
                "Expected the lookup failure to be reported, got: " + errors(model));

        // The try block throws before `entries` or `hasMore` are ever assigned,
        // so the pager gets built with the pre-try defaults: a null list and
        // hasMore=false. EntriesPager's own getItems() returns exactly what it
        // was constructed with, without defensively substituting an empty list.
        EntriesPagerLike pager = pager(model);
        assertNull(pager.items, "A failed lookup leaves the pager's entry list null rather than empty");
        assertFalse(pager.moreItems);
    }

    // ------------------------------------------------------------ sidebar filter

    /**
     * Source-scan: the sidebar form must be method="get" with no CSRF input --
     * a POST here answers 405 because {@code execute()} is {@code @GetMapping}
     * only.
     */
    @Test
    void theFilterFormSubmitsByGet() throws Exception {
        String jsp = Files.readString(Path.of(
                "src/main/webapp/WEB-INF/jsps/editor/EntriesSidebar.jsp"));
        Matcher form = Pattern.compile("<form[^>]*action=\"[^\"]*entries\\.rol[^>]*>")
                .matcher(jsp);
        assertTrue(form.find(), "filter form not found");
        assertTrue(form.group().contains("method=\"get\""), form.group());
        assertFalse(jsp.contains("csrfInput"), "GET form must not carry a CSRF token");
    }

    /**
     * {@code bean.categoryPath} does not exist on {@link EntriesBean}; emitting
     * it in the pager's base url means paging silently drops the category
     * filter.
     */
    @Test
    void thePagerBaseUrlUsesTheBeanCategoryNameProperty() {
        bean.setCategoryName("Travel");
        when(weblogger.getUrlStrategy().getActionURL(any(), any(), any(), any(), org.mockito.ArgumentMatchers.anyBoolean()))
                .thenAnswer(invocation -> {
                    @SuppressWarnings("unchecked")
                    Map<String, String> params = (Map<String, String>) invocation.getArgument(3);
                    return "/roller-ui/authoring/entries.rol?" + new LinkedHashMap<>(params).entrySet().stream()
                            .map(e -> e.getKey() + "=" + e.getValue())
                            .collect(Collectors.joining("&"));
                });

        String url = controller.buildBaseUrl(request, bean);

        assertTrue(url.contains("bean.categoryName=Travel"), url);
        assertFalse(url.contains("bean.categoryPath="), url);
    }

    /**
     * A bulk action must come back to the list the author was LOOKING at.
     * Before this, deleting three drafts out of a status=DRAFT view returned
     * to the unfiltered list of everything, with nothing on screen to say the
     * filter had been discarded -- so the next bulk selection was made against
     * a different set of rows than the one just acted on.
     */
    @Test
    void aBulkActionReturnsToTheFilteredListItWasRunFrom() throws Exception {
        bean.setStatus("DRAFT");
        bean.setCategoryName("Travel");
        stubActionUrlWithContextPath("/roller");

        String view = controller.bulkDelete(request, null, bean, newRedirectAttributes());

        assertTrue(view.startsWith("redirect:"), view);
        assertTrue(view.contains("bean.status=DRAFT"), view);
        assertTrue(view.contains("bean.categoryName=Travel"), view);
        assertTrue(view.contains("weblog=" + WEBLOG_HANDLE), view);
    }

    /**
     * The redirect must be CONTEXT-RELATIVE, and this test is written so that
     * it fails under a prefixed deployment rather than passing at the root
     * where every prefix is empty.
     *
     * <p>{@code buildBaseUrl} goes through {@code getActionURL(...,
     * absolute=false)}, which prepends {@code getRelativeContextURL()}. That
     * is right for an href but wrong for a {@code redirect:} view name:
     * Spring's {@code InternalResourceViewResolver} runs with {@code
     * redirectContextRelative=true} and prepends the context path again, so
     * reusing the pager's base url produced {@code
     * /roller/roller/roller-ui/...} and a 404 under a servlet prefix.
     *
     * <p>The expectation is DERIVED from the stubbed context path rather than
     * hardcoded -- the {@code SeoController.robots()} lesson, where a fixture
     * that pinned one url shape had baked the defect in as its expected value.
     */
    @Test
    void theBulkRedirectDoesNotInheritTheContextPathTwice() throws Exception {
        String contextPath = "/roller";
        stubActionUrlWithContextPath(contextPath);
        bean.setStatus("DRAFT");

        String view = controller.bulkDelete(request, null, bean, newRedirectAttributes());
        String target = view.substring("redirect:".length());

        // The pager's base url, which legitimately carries the prefix, is what
        // the redirect must NOT be -- derived, so a different prefix still fails.
        String pagerUrl = controller.buildBaseUrl(request, bean);
        assertTrue(pagerUrl.startsWith(contextPath + "/roller-ui/authoring/entries.rol"),
                "precondition: the pager url carries the context path, was " + pagerUrl);

        assertTrue(target.startsWith("/roller-ui/authoring/entries.rol"),
                "the redirect must be context-relative; Spring prepends the context path "
                        + "itself, so a prefixed target becomes " + contextPath + contextPath
                        + "/... and 404s. Was: " + target);
        assertFalse(target.startsWith(contextPath + "/"),
                "the redirect target carries the context path, which Spring will prepend "
                        + "again: " + target);
    }

    /**
     * The server half of the filter-preserving redirect is worthless on its own:
     * {@code #entriesBulkForm} is a DIFFERENT form from the sidebar's GET filter
     * form, so unless it carries the filter as hidden inputs the POST binds an
     * empty bean and the redirect lands on the unfiltered list -- green unit
     * test, no behaviour in a browser. This scans the JSP for the inputs.
     *
     * <p>bean.sortBy and bean.page are deliberately NOT required: sortBy has a
     * non-null default that would pin whatever happened to be showing, and
     * returning to page 4 of a list that just got shorter is worse than
     * returning to page 1.
     */
    @Test
    void theBulkFormCarriesTheFilterItMustPostBack() throws Exception {
        String jsp = Files.readString(
                Path.of("src/main/webapp/WEB-INF/jsps/editor/Entries.jsp"));
        String form = jsp.substring(jsp.indexOf("<form id=\"entriesBulkForm\""));

        List<String> missing = new ArrayList<>();
        for (String field : List.of("bean.status", "bean.categoryName", "bean.tagsAsString",
                                    "bean.text", "bean.startDateString", "bean.endDateString")) {
            if (!form.contains("<input type=\"hidden\" name=\"" + field + "\"")) {
                missing.add(field);
            }
        }
        assertTrue(missing.isEmpty(),
                "#entriesBulkForm must post the current filter back, or backToList has "
                        + "nothing to rebuild it from. Missing: " + missing);
    }

    /**
     * Answers the way {@code AbstractURLStrategy} does under a servlet prefix:
     * a context-relative url that already begins with the context path.
     *
     * <p>It joins the parameters through the REAL {@link URLUtilities#
     * getQueryString}, not a hand-rolled {@code key=value} concatenation.
     * That matters: the encoding of a filter value is the behaviour under
     * test in {@link #aStatusChipUrlKeepsAFilterValueThatIsFullOfQueryPunctuation},
     * and a stub that joined the pairs itself would assert against a url
     * shape the application never produces.
     */
    private void stubActionUrlWithContextPath(String contextPath) {
        when(weblogger.getUrlStrategy().getActionURL(any(), any(), any(), any(), org.mockito.ArgumentMatchers.anyBoolean()))
                .thenAnswer(invocation -> {
                    @SuppressWarnings("unchecked")
                    Map<String, String> params = new LinkedHashMap<>(
                            (Map<String, String>) invocation.getArgument(3));
                    params.put("weblog", invocation.getArgument(2));
                    return contextPath + "/roller-ui/authoring/entries.rol"
                            + URLUtilities.getQueryString(params);
                });
    }

    /** The parameters of a built url, percent-decoded back to what they mean. */
    private static Map<String, String> queryParams(String url) {
        Map<String, String> params = new LinkedHashMap<>();
        int q = url.indexOf('?');
        if (q < 0) {
            return params;
        }
        for (String pair : url.substring(q + 1).split("&")) {
            int eq = pair.indexOf('=');
            params.put(URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
                    URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
        }
        return params;
    }

    /**
     * Every {@code $("#id")} selector in the sidebar's script block must
     * reference an {@code id=} present in the file, or the datepicker never
     * binds and the readonly-by-history date fields stay unfillable.
     */
    @Test
    void theDatePickerBindingMatchesRealIds() throws Exception {
        String jsp = Files.readString(Path.of(
                "src/main/webapp/WEB-INF/jsps/editor/EntriesSidebar.jsp"));
        Matcher sel = Pattern.compile("\\$\\(\"#([A-Za-z0-9_]+)\"\\)").matcher(jsp);
        while (sel.find()) {
            assertTrue(jsp.contains("id=\"" + sel.group(1) + "\""),
                    "script binds #" + sel.group(1) + " but no element has that id");
        }
    }

    // --- helpers ---

    // ------------------------------------------------------------ duplication

    /**
     * The ownership check is the whole security story for this endpoint: the
     * id is client input and the interceptor only establishes that the caller
     * may edit the <em>action</em> weblog, so without it an editor on one blog
     * could copy another blog's unpublished drafts into their own.
     */
    @Test
    void anEntryBelongingToAnotherWeblogIsNotDuplicated() throws Exception {
        WeblogEntry foreign = entryNamed("entry-1");
        Weblog otherWeblog = new Weblog();
        otherWeblog.setId("weblog-2");
        otherWeblog.setHandle("someoneelse");
        foreign.setWebsite(otherWeblog);
        when(weblogger.getWeblogEntryManager().getWeblogEntry("entry-1")).thenReturn(foreign);

        RedirectAttributes redirect = newRedirectAttributes();
        String view = controller.duplicate(request, "entry-1", redirect);

        assertEquals("redirect:/roller-ui/authoring/entries.rol?weblog=" + WEBLOG_HANDLE, view);
        assertEquals(List.of("weblogEntry.notFound"), flashErrors(redirect));
        verify(weblogger.getWeblogEntryManager(), never()).saveWeblogEntry(any());
    }

    @Test
    void anUnknownEntryIdIsRefusedTheSameWayAsAForeignOne() throws Exception {
        when(weblogger.getWeblogEntryManager().getWeblogEntry("no-such-entry")).thenReturn(null);

        RedirectAttributes redirect = newRedirectAttributes();
        String view = controller.duplicate(request, "no-such-entry", redirect);

        assertEquals("redirect:/roller-ui/authoring/entries.rol?weblog=" + WEBLOG_HANDLE, view);
        assertEquals(List.of("weblogEntry.notFound"), flashErrors(redirect));
        verify(weblogger.getWeblogEntryManager(), never()).saveWeblogEntry(any());
    }

    /**
     * A forged {@code duplicateId} naming a trashed entry must not be usable
     * to duplicate it back into a live draft -- that would resurrect the
     * entry by a side door that bypasses restore entirely.
     */
    @Test
    void aTrashedEntryIsRefusedTheSameWayAsAForeignOne() throws Exception {
        WeblogEntry trashed = ownedEntry("entry-1", "Walking the Cinque Terre");
        trashed.setStatus(WeblogEntry.PubStatus.TRASHED);

        RedirectAttributes redirect = newRedirectAttributes();
        String view = controller.duplicate(request, "entry-1", redirect);

        assertEquals("redirect:/roller-ui/authoring/entries.rol?weblog=" + WEBLOG_HANDLE, view);
        assertEquals(List.of("weblogEntry.notFound"), flashErrors(redirect));
        verify(weblogger.getWeblogEntryManager(), never()).saveWeblogEntry(any());
    }

    @Test
    void duplicatingSavesADraftCopyAndOpensItInTheEditor() throws Exception {
        registerMessage("weblogEdit.duplicateTitle", "Copy of {0}");
        WeblogEntry original = ownedEntry("entry-1", "Walking the Cinque Terre");

        RedirectAttributes redirect = newRedirectAttributes();
        String view = controller.duplicate(request, "entry-1", redirect);

        ArgumentCaptor<WeblogEntry> saved = ArgumentCaptor.forClass(WeblogEntry.class);
        verify(weblogger.getWeblogEntryManager()).saveWeblogEntry(saved.capture());
        WeblogEntry copy = saved.getValue();

        assertEquals("Copy of Walking the Cinque Terre", copy.getTitle());
        assertEquals(WeblogEntry.PubStatus.DRAFT, copy.getStatus());
        assertNotEquals(original.getId(), copy.getId());
        assertEquals("redirect:/roller-ui/authoring/entryEdit.rol?weblog=" + WEBLOG_HANDLE
                + "&bean.id=" + copy.getId(), view);
    }

    /**
     * A save that blows up must not strand the author on a blank page or, worse,
     * redirect them to an editor for an entry that was never written.
     */
    @Test
    void aFailedSaveReturnsToTheListWithAnError() throws Exception {
        registerMessage("weblogEdit.duplicateTitle", "Copy of {0}");
        ownedEntry("entry-1", "Walking the Cinque Terre");
        doThrow(new WebloggerException("database down"))
                .when(weblogger.getWeblogEntryManager()).saveWeblogEntry(any());

        RedirectAttributes redirect = newRedirectAttributes();
        String view = controller.duplicate(request, "entry-1", redirect);

        assertEquals("redirect:/roller-ui/authoring/entries.rol?weblog=" + WEBLOG_HANDLE, view);
        assertEquals(List.of("generic.error.check.logs"), flashErrors(redirect));
    }

    // ----------------------------------------------------------- bulk actions

    @Test
    void bulkPublishFlipsOnlyTheSelectedEntries() throws Exception {
        givenPostPermission();
        WeblogEntry selected = ownedDraft("entry-1");
        WeblogEntry notSelected = ownedDraft("entry-2");

        RedirectAttributes redirect = newRedirectAttributes();
        controller.bulkPublish(request, List.of("entry-1"), bean, redirect);

        assertEquals(WeblogEntry.PubStatus.PUBLISHED, selected.getStatus());
        assertEquals(WeblogEntry.PubStatus.DRAFT, notSelected.getStatus(),
                "an entry that was not selected must not be touched");
        verify(weblogger.getWeblogEntryManager()).saveWeblogEntry(selected);
        verify(weblogger.getWeblogEntryManager(), never()).saveWeblogEntry(notSelected);
        assertTrue(flashErrors(redirect).isEmpty());
    }

    /**
     * A newly published entry needs a pubTime or it sorts to the top of the
     * blog as if it had never been dated.
     */
    @Test
    void bulkPublishDatesAnEntryThatHasNeverBeenPublished() throws Exception {
        givenPostPermission();
        WeblogEntry entry = ownedDraft("entry-1");
        assertNull(entry.getPubTime());

        controller.bulkPublish(request, List.of("entry-1"), bean, newRedirectAttributes());

        assertNotNull(entry.getPubTime(), "a published entry must carry a publication time");
    }

    /**
     * The bulk path must not become a way around a permission the single-entry
     * publish button enforces.
     */
    @Test
    void anAuthorWithoutPostPermissionCanOnlySubmitForReview() throws Exception {
        WeblogEntry entry = ownedDraft("entry-1");

        controller.bulkPublish(request, List.of("entry-1"), bean, newRedirectAttributes());

        assertEquals(WeblogEntry.PubStatus.PENDING, entry.getStatus());
    }

    /**
     * A trashed id slipped into a bulk selection must not be actionable --
     * bulkPublish in particular would otherwise resurrect a trashed entry
     * straight to PUBLISHED, bypassing restore entirely.
     */
    @Test
    void bulkPublishRefusesATrashedEntry() throws Exception {
        givenPostPermission();
        WeblogEntry trashed = ownedDraft("entry-1");
        trashed.setStatus(WeblogEntry.PubStatus.TRASHED);

        RedirectAttributes redirect = newRedirectAttributes();
        controller.bulkPublish(request, List.of("entry-1"), bean, redirect);

        assertEquals(WeblogEntry.PubStatus.TRASHED, trashed.getStatus(),
                "a trashed entry must not be flipped to PUBLISHED by a bulk action");
        verify(weblogger.getWeblogEntryManager(), never()).saveWeblogEntry(trashed);
        assertEquals(List.of("weblogEntryQuery.bulkFailed"), flashErrors(redirect));
    }

    @Test
    void bulkDeleteRemovesEachEntryFromTheSearchIndex() throws Exception {
        WeblogEntry first = ownedDraft("entry-1");
        first.setStatus(WeblogEntry.PubStatus.PUBLISHED);
        WeblogEntry second = ownedDraft("entry-2");
        second.setStatus(WeblogEntry.PubStatus.PUBLISHED);

        controller.bulkDelete(request, List.of("entry-1", "entry-2"), bean, newRedirectAttributes());

        // A bulk JPQL delete would skip exactly this, leaving the index holding
        // documents that link to pages which now 404. Bulk delete trashes
        // rather than removes (see BaseController#trashEntryWithIndex), but
        // the de-indexing must still happen.
        verify(weblogger.getIndexManager()).removeEntryIndexOperation(first);
        verify(weblogger.getIndexManager()).removeEntryIndexOperation(second);
        verify(weblogger.getWeblogEntryManager()).trashWeblogEntry(first);
        verify(weblogger.getWeblogEntryManager()).trashWeblogEntry(second);
    }

    /**
     * The security case: one foreign id in a selection of otherwise-valid ones.
     * It must be refused without taking the rest of the batch down with it.
     */
    @Test
    void aForeignEntryInTheSelectionIsRefusedWithoutAffectingTheOthers() throws Exception {
        givenPostPermission();
        WeblogEntry mine = ownedDraft("entry-1");
        WeblogEntry foreign = entryNamed("entry-2");
        Weblog otherWeblog = new Weblog();
        otherWeblog.setId("weblog-2");
        foreign.setAnchor("anchor-entry-2");
        foreign.setWebsite(otherWeblog);
        foreign.setStatus(WeblogEntry.PubStatus.DRAFT);
        when(weblogger.getWeblogEntryManager().getWeblogEntry("entry-2")).thenReturn(foreign);

        RedirectAttributes redirect = newRedirectAttributes();
        controller.bulkPublish(request, List.of("entry-1", "entry-2"), bean, redirect);

        assertEquals(WeblogEntry.PubStatus.PUBLISHED, mine.getStatus());
        assertEquals(WeblogEntry.PubStatus.DRAFT, foreign.getStatus(),
                "another weblog's entry was published through the bulk endpoint");
        verify(weblogger.getWeblogEntryManager(), never()).saveWeblogEntry(foreign);
        assertEquals(List.of("weblogEntryQuery.bulkFailed"), flashErrors(redirect));
    }

    /**
     * One entry failing must not roll back the ones that worked -- and the
     * author has to be told, or a silent partial success reads as a full one.
     */
    @Test
    void oneFailureIsReportedAndTheRestStillApply() throws Exception {
        givenPostPermission();
        WeblogEntry good = ownedDraft("entry-1");
        WeblogEntry bad = ownedDraft("entry-2");
        doThrow(new WebloggerException("constraint violation"))
                .when(weblogger.getWeblogEntryManager()).saveWeblogEntry(bad);

        RedirectAttributes redirect = newRedirectAttributes();
        controller.bulkPublish(request, List.of("entry-1", "entry-2"), bean, redirect);

        verify(weblogger.getWeblogEntryManager()).saveWeblogEntry(good);
        assertEquals(List.of("weblogEntryQuery.bulkFailed"), flashErrors(redirect));
        assertEquals(List.of("weblogEntryQuery.bulkPublished"), flashMessages(redirect));
    }

    @Test
    void bulkTagAddsTheTagToEverySelectedEntry() throws Exception {
        WeblogEntry entry = ownedDraft("entry-1");

        controller.bulkTag(request, List.of("entry-1"), "cycling", bean, newRedirectAttributes());

        assertTrue(entry.getTagsAsString().contains("cycling"));
        verify(weblogger.getWeblogEntryManager()).saveWeblogEntry(entry);
    }

    @Test
    void bulkTagWithNoTagTypedChangesNothing() throws Exception {
        WeblogEntry entry = ownedDraft("entry-1");

        RedirectAttributes redirect = newRedirectAttributes();
        controller.bulkTag(request, List.of("entry-1"), "   ", bean, redirect);

        assertTrue(entry.getTags().isEmpty());
        verify(weblogger.getWeblogEntryManager(), never()).saveWeblogEntry(any());
        assertEquals(List.of("weblogEntryQuery.bulkTagMissing"), flashErrors(redirect));
    }

    @Test
    void anEmptySelectionSaysSoInsteadOfClaimingSuccess() throws Exception {
        RedirectAttributes redirect = newRedirectAttributes();
        String view = controller.bulkDelete(request, null, bean, redirect);

        assertEquals("redirect:/roller-ui/authoring/entries.rol?weblog=" + WEBLOG_HANDLE, view);
        assertEquals(List.of("weblogEntryQuery.bulkNothingSelected"), flashErrors(redirect));
        assertTrue(flashMessages(redirect).isEmpty());
        verify(weblogger.getWeblogEntryManager(), never()).trashWeblogEntry(any());
    }

    /**
     * Grant POST on the action weblog, which is what separates publishing from
     * submitting for review. The check funnels through
     * {@code UserManager.checkPermission}, so that is where the grant goes.
     */
    private void givenPostPermission() throws Exception {
        when(weblogger.getUserManager().checkPermission(any(), any())).thenReturn(true);
    }

    /**
     * A DRAFT entry of the action weblog, stubbed into the by-id lookup.
     *
     * <p>The anchor matters here even though nothing reads it:
     * {@code WeblogEntry.equals} is anchor plus weblog, so two fixtures with
     * null anchors on one weblog are the same argument as far as Mockito is
     * concerned, and every {@code verify} would then pass or fail for the
     * wrong reason.
     */
    private WeblogEntry ownedDraft(String id) throws Exception {
        WeblogEntry entry = entryNamed(id);
        entry.setTitle("Entry " + id);
        entry.setAnchor("anchor-" + id);
        entry.setWebsite(weblog);
        entry.setStatus(WeblogEntry.PubStatus.DRAFT);
        when(weblogger.getWeblogEntryManager().getWeblogEntry(id)).thenReturn(entry);
        return entry;
    }

    /** An entry of the action weblog, stubbed into the manager's by-id lookup. */
    private WeblogEntry ownedEntry(String id, String title) throws Exception {
        WeblogEntry entry = entryNamed(id);
        entry.setTitle(title);
        entry.setWebsite(weblog);
        entry.setStatus(WeblogEntry.PubStatus.PUBLISHED);
        when(weblogger.getWeblogEntryManager().getWeblogEntry(id)).thenReturn(entry);
        return entry;
    }

    // ------------------------------------------------ fit and finish (2026-10)

    /**
     * An entry title is stored HTML-escaped, so printing it through
     * fn:escapeXml shows "Tom &amp;amp; Jerry" and a screen reader reads "amp";
     * printing displayTitle bare is no answer either, because an UNTITLED
     * entry's displayTitle is its body with tags stripped, and removeHTML keeps
     * an unterminated tag verbatim. The controller hands the list one plain
     * text label per entry, which the page escapes exactly once.
     */
    @Test
    void eachEntryGetsAPlainTextTitleTheListEscapesOnce() throws Exception {
        WeblogEntry titled = entryNamed("titled");
        titled.setTitle("Tom &amp; Jerry");
        WeblogEntry untitled = entryNamed("untitled");
        untitled.setText("<p>a</p><img src=x onerror=alert(1)//");
        when(weblogger.getWeblogEntryManager().getWeblogEntries(any())).thenReturn(List.of(titled, untitled));

        controller.execute(request, model, bean);

        @SuppressWarnings("unchecked")
        Map<String, String> titles = (Map<String, String>) model.getAttribute("entryTitles");
        assertNotNull(titles, "the list has no entryTitles map to label its rows with");
        assertEquals("Tom & Jerry", titles.get("titled"), "the stored escaping must be undone exactly once");
        String fallback = titles.get("untitled");
        assertTrue(fallback.startsWith("a") && fallback.endsWith("<img src=x onerror=alert(1)//"),
                "an untitled entry falls back to its text, still raw for the page to escape: " + fallback);
    }

    @Test
    void anUnfilteredListIsNotReportedAsFiltered() throws Exception {
        controller.execute(request, model, bean);

        assertEquals(Boolean.FALSE, model.getAttribute("filtered"),
                "the default list (status ALL, default sort) is the weblog's whole archive");
    }

    @Test
    void aStatusChipOrATextSearchMarksTheListFiltered() throws Exception {
        bean.setStatus("DRAFT");
        controller.execute(request, model, bean);
        assertEquals(Boolean.TRUE, model.getAttribute("filtered"), "a status chip narrows the list");

        Model searched = newModel();
        EntriesBean textBean = new EntriesBean();
        textBean.setText("nothing like this");
        controller.execute(request, searched, textBean);
        assertEquals(Boolean.TRUE, searched.getAttribute("filtered"), "a text search narrows the list");
    }

    @Test
    void choosingASortOrderIsNotAFilter() throws Exception {
        bean.setSortBy(WeblogEntrySearchCriteria.SortBy.PUBLICATION_TIME);
        bean.setStatus("ALL");

        controller.execute(request, model, bean);

        assertEquals(Boolean.FALSE, model.getAttribute("filtered"),
                "sorting reorders the archive, it does not narrow it");
    }

    private WeblogEntry entryNamed(String id) {
        WeblogEntry entry = new WeblogEntry();
        entry.setId(id);
        return entry;
    }

    /**
     * Reads the {@code pager} model attribute through the fields that matter to
     * these tests, without spreading {@code EntriesPager} casts across every
     * test method.
     */
    private EntriesPagerLike pager(Model model) {
        com.wikantik.pressroom.ui.controllers.pagers.EntriesPager pager =
                (com.wikantik.pressroom.ui.controllers.pagers.EntriesPager) model.getAttribute("pager");
        EntriesPagerLike result = new EntriesPagerLike();
        result.items = pager.getItems();
        result.moreItems = pager.isMoreItems();
        return result;
    }

    private static final class EntriesPagerLike {
        List<WeblogEntry> items;
        boolean moreItems;
    }
}
