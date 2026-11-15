/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  The ASF licenses this file to You
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
import java.util.List;
import java.util.Locale;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;

import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.business.WeblogEntryManager;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.pojos.WeblogEntry;
import com.wikantik.pressroom.pojos.WeblogEntry.PubStatus;
import com.wikantik.pressroom.pojos.WeblogEntrySearchCriteria;
import com.wikantik.pressroom.pojos.WeblogPage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.ui.Model;
import org.springframework.web.bind.ServletRequestDataBinder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The page editor's strand switch and Parts panel: {@link PageEditController}
 * saving {@code bean.strand} and the ordered {@code bean.partIds}, and the
 * model the panel renders from.
 *
 * <p>The rules are {@code StrandRules}' and the persistence
 * {@code setStrandParts}' (both tested in the business tier, the
 * {@code lastModified} bump included); what is pinned here is that the
 * editor validates every id before it saves anything, hands the parts over in
 * the order the form posted them, and refuses to untick a strand that still
 * has parts.
 */
class PageEditControllerStrandTest extends EditorControllerTestSupport {

    private Weblog weblogA;
    private Weblog weblogB;
    private PageEditController controller;
    private Model model;
    private WeblogEntryManager entries;

    @BeforeEach
    void setUp() {
        controller = prepare(new PageEditController());
        model = newModel();
        weblogA = weblog;
        weblogB = new Weblog();
        weblogB.setId("weblog-2");
        weblogB.setHandle("someoneelse");
        entries = weblogger.getWeblogEntryManager();
    }

    // --- saving the switch and the parts ---

    @Test
    void savingAStrandWithPartsStoresTheFlagAndThosePartsInThePostedOrder() throws Exception {
        WeblogPage page = pageOn(weblogA, "trip");
        WeblogEntry a = postOn(weblogA, "a");
        WeblogEntry c = postOn(weblogA, "c");

        controller.save(requestFor(weblogA), model, strandBean(page, "post-c", "post-a"));

        assertTrue(errors(model).isEmpty(), "no refusal expected: " + errors(model));
        assertTrue(page.getStrand(), "the switch is stored on the page");
        var order = inOrder(weblogger.getWeblogPageManager(), entries);
        order.verify(weblogger.getWeblogPageManager()).savePage(page);
        order.verify(entries).setStrandParts(page, List.of(c, a));
        assertEquals(1, weblogger.flushCount());
    }

    @Test
    void reorderingThePartsHandsTheNewOrderToTheBusinessTier() throws Exception {
        // setStrandParts renumbers 1..n in list order and bumps
        // weblog.lastModified (StrandMembershipTest); the editor's
        // job is to trust nothing but the posted order.
        WeblogPage page = pageOn(weblogA, "trip");
        page.setStrand(true);
        WeblogEntry a = postOn(weblogA, "a");
        WeblogEntry c = postOn(weblogA, "c");
        a.setStrandPageId(page.getId());
        c.setStrandPageId(page.getId());

        controller.save(requestFor(weblogA), model, strandBean(page, "post-c", "post-a"));

        verify(entries).setStrandParts(page, List.of(c, a));
    }

    @Test
    void anEmptyPartsListRemovesEveryPart() throws Exception {
        WeblogPage page = pageOn(weblogA, "trip");
        page.setStrand(true);

        controller.save(requestFor(weblogA), model, strandBean(page));

        verify(entries).setStrandParts(page, List.of());
    }

    @Test
    void aFormWithoutThePartsFieldLeavesThePartsAlone() throws Exception {
        WeblogPage page = pageOn(weblogA, "trip");
        page.setStrand(true);
        PageBean bean = strandBean(page);
        bean.setPartIds(null);

        controller.save(requestFor(weblogA), model, bean);

        verify(weblogger.getWeblogPageManager()).savePage(page);
        verify(entries, never()).setStrandParts(any(), any());
    }

    @Test
    void aNewPageTickedAsAStrandIsSavedBeforeItsPartsPointAtIt() throws Exception {
        // The parts' strand_page_id references the page row, and the column is
        // a plain id rather than a mapped relationship, so the page is
        // committed first: nothing else orders the INSERT before the UPDATEs.
        WeblogEntry a = postOn(weblogA, "a");
        PageBean bean = new PageBean();
        bean.setSlug("trip");
        bean.setTitle("Trip");
        bean.setStrand(true);
        bean.setPartIds(new ArrayList<>(List.of("post-a")));

        controller.save(requestFor(weblogA), model, bean);

        var saved = org.mockito.ArgumentCaptor.forClass(WeblogPage.class);
        verify(weblogger.getWeblogPageManager()).savePage(saved.capture());
        assertTrue(saved.getValue().getStrand());
        verify(entries).setStrandParts(saved.getValue(), List.of(a));
        assertEquals(2, weblogger.flushCount());
    }

    @Test
    void aPageThatIsNotAStrandIgnoresPartIds() throws Exception {
        WeblogPage page = pageOn(weblogA, "about");
        PageBean bean = strandBean(page, "post-a");
        bean.setStrand(false);

        controller.save(requestFor(weblogA), model, bean);

        verify(weblogger.getWeblogPageManager()).savePage(page);
        verify(entries, never()).getWeblogEntry(any());
        verify(entries, never()).setStrandParts(any(), any());
    }

    @Test
    void aSitePageIsNeverAStrand() throws Exception {
        weblogA.setHandle(Weblog.SITE_SPACE_HANDLE);
        weblogA.setSiteSpace(true);
        WeblogPage page = pageOn(weblogA, "about");

        controller.save(requestFor(weblogA), model, strandBean(page, "post-a"));

        assertFalse(page.getStrand());
        verify(entries, never()).setStrandParts(any(), any());
    }

    // --- refusals: nothing is saved ---

    @Test
    void aPostOfAnotherBlogIsRefusedAndNothingIsSaved() throws Exception {
        assertRefused(postOn(weblogB, "x"), "strand.error.otherBlog");
    }

    @Test
    void aPostInAnotherStrandIsRefusedAndNothingIsSaved() throws Exception {
        WeblogEntry elsewhere = postOn(weblogA, "x");
        elsewhere.setStrandPageId("page-other-strand");
        assertRefused(elsewhere, "strand.error.inAnotherStrand");
    }

    @Test
    void anUnknownPostIsRefused() throws Exception {
        WeblogPage page = pageOn(weblogA, "trip");

        controller.save(requestFor(weblogA), model, strandBean(page, "post-nowhere"));

        assertTrue(errors(model).contains("strand.error.unknownPost"), errors(model).toString());
        verify(weblogger.getWeblogPageManager(), never()).savePage(any());
    }

    @Test
    void aFailedPostLookupIsRefusedNotTakenAsAPass() throws Exception {
        WeblogPage page = pageOn(weblogA, "trip");
        when(entries.getWeblogEntry("post-a")).thenThrow(new WebloggerException("database down"));

        controller.save(requestFor(weblogA), model, strandBean(page, "post-a"));

        assertTrue(errors(model).contains("strand.error.unknownPost"), errors(model).toString());
        verify(weblogger.getWeblogPageManager(), never()).savePage(any());
    }

    @Test
    void aPostListedTwiceIsRefusedOnce() throws Exception {
        WeblogPage page = pageOn(weblogA, "trip");
        postOn(weblogA, "a");

        controller.save(requestFor(weblogA), model, strandBean(page, "post-a", "post-a"));

        assertEquals(List.of("strand.error.duplicate"), errors(model));
        verify(entries, never()).setStrandParts(any(), any());
    }

    private void assertRefused(WeblogEntry post, String key) throws Exception {
        WeblogPage page = pageOn(weblogA, "trip");
        page.setTitle("Old title");
        PageBean bean = strandBean(page, post.getId());
        bean.setTitle("New title");

        controller.save(requestFor(weblogA), model, bean);

        assertTrue(errors(model).contains(key), "expected " + key + ", got " + errors(model));
        assertEquals(List.of("page_strand_parts"), invalidFields(model));
        assertEquals("Old title", page.getTitle(), "a refused save changes nothing, page fields included");
        assertFalse(page.getStrand());
        verify(weblogger.getWeblogPageManager(), never()).savePage(any());
        verify(entries, never()).setStrandParts(any(), any());
    }

    // --- unticking ---

    @Test
    void untickingAStrandThatHasPartsIsRefused() throws Exception {
        registerMessage("pageEdit.error.strandHasParts", "This strand has {0} posts.");
        WeblogPage page = pageOn(weblogA, "trip");
        page.setStrand(true);
        when(entries.getStrandPartCount(page)).thenReturn(2L);
        PageBean bean = strandBean(page);
        bean.setStrand(false);

        controller.save(requestFor(weblogA), model, bean);

        assertEquals(List.of("This strand has 2 posts."), errors(model));
        assertEquals(List.of("page_bean_strand"), invalidFields(model));
        assertTrue(page.getStrand(), "still a strand");
        assertTrue(bean.getStrand(), "the redisplayed form shows what is stored");
        verify(weblogger.getWeblogPageManager(), never()).savePage(any());
    }

    @Test
    void unTickingAStrandWithNoPartsIsAllowed() throws Exception {
        WeblogPage page = pageOn(weblogA, "trip");
        page.setStrand(true);
        when(entries.getStrandPartCount(page)).thenReturn(0L);
        PageBean bean = strandBean(page);
        bean.setStrand(false);

        controller.save(requestFor(weblogA), model, bean);

        assertFalse(page.getStrand());
        verify(weblogger.getWeblogPageManager()).savePage(page);
        verify(entries, never()).setStrandParts(any(), any());
    }

    @Test
    void aFailedPartCountRefusesTheUntickRatherThanAllowingIt() throws Exception {
        WeblogPage page = pageOn(weblogA, "trip");
        page.setStrand(true);
        when(entries.getStrandPartCount(page)).thenThrow(new WebloggerException("database down"));
        PageBean bean = strandBean(page);
        bean.setStrand(false);

        controller.save(requestFor(weblogA), model, bean);

        assertTrue(errors(model).contains("generic.error.check.logs"), errors(model).toString());
        assertTrue(page.getStrand());
        verify(weblogger.getWeblogPageManager(), never()).savePage(any());
    }

    // --- what the panel renders from ---

    @Test
    void theEditorListsEveryMemberAndThePostsInNoStrand() throws Exception {
        WeblogPage page = pageOn(weblogA, "trip");
        page.setStrand(true);
        WeblogEntry member = postOn(weblogA, "member");
        member.setStrandPageId(page.getId());
        member.setStatus(PubStatus.TRASHED);
        WeblogEntry free = postOn(weblogA, "free");
        free.setTitle("Tom &amp; Jerry");
        WeblogEntry taken = postOn(weblogA, "taken");
        taken.setStrandPageId("page-other");
        WeblogEntry trashed = postOn(weblogA, "trashed");
        trashed.setStatus(PubStatus.TRASHED);
        when(entries.getStrandParts(page, false)).thenReturn(List.of(member));
        when(entries.getWeblogEntries(any(WeblogEntrySearchCriteria.class)))
                .thenReturn(List.of(free, taken, trashed));

        controller.edit(page.getId(), requestFor(weblogA), model);

        assertEquals(List.of(member), model.getAttribute("strandParts"));
        assertEquals(List.of(free), model.getAttribute("strandCandidates"));
        @SuppressWarnings("unchecked")
        Map<String, String> titles = (Map<String, String>) model.getAttribute("entryTitles");
        assertEquals("Tom & Jerry", titles.get("post-free"), "plain text, for the JSP to escape once");
        assertTrue(titles.containsKey("post-member"));
    }

    @Test
    void aNewPageHasNoPartsButStillOffersPosts() throws Exception {
        WeblogEntry free = postOn(weblogA, "free");
        when(entries.getWeblogEntries(any(WeblogEntrySearchCriteria.class))).thenReturn(List.of(free));

        controller.edit(null, requestFor(weblogA), model);

        assertEquals(List.of(), model.getAttribute("strandParts"));
        assertEquals(List.of(free), model.getAttribute("strandCandidates"));
        verify(entries, never()).getStrandParts(any(), org.mockito.ArgumentMatchers.anyBoolean());
    }

    @Test
    void aSitePageGetsNoPanel() throws Exception {
        weblogA.setHandle(Weblog.SITE_SPACE_HANDLE);
        weblogA.setSiteSpace(true);
        WeblogPage page = pageOn(weblogA, "about");

        controller.edit(page.getId(), requestFor(weblogA), model);

        assertNull(model.getAttribute("strandCandidates"));
        verify(entries, never()).getWeblogEntries(any());
    }

    @Test
    void aFailedPanelLookupShowsAnEmptyPanelRatherThanFailingThePage() throws Exception {
        WeblogPage page = pageOn(weblogA, "trip");
        page.setStrand(true);
        when(entries.getStrandParts(page, false)).thenThrow(new WebloggerException("database down"));
        when(entries.getWeblogEntries(any(WeblogEntrySearchCriteria.class)))
                .thenThrow(new WebloggerException("database down"));

        String view = controller.edit(page.getId(), requestFor(weblogA), model);

        assertEquals(".PageEdit", view);
        assertEquals(List.of(), model.getAttribute("strandParts"));
        assertEquals(List.of(), model.getAttribute("strandCandidates"));
    }

    @Test
    void openingAStrandLoadsTheSwitchIntoTheForm() throws Exception {
        WeblogPage page = pageOn(weblogA, "trip");
        page.setStrand(true);

        controller.edit(page.getId(), requestFor(weblogA), model);

        assertTrue(((PageBean) model.getAttribute("bean")).getStrand());
    }

    // --- binding through the real binder, with PageEdit.jsp's field names ---

    @Test
    void anUntickedStrandBoxAndAnEmptyPanelBindThroughTheirMarkers() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/pressroom-ui/authoring/pageEdit!save.rol");
        request.setParameter("_strand", "on");
        request.setParameter("_partIds", "on");
        PageBean bean = new PageBean();
        bean.setStrand(true);

        bind(bean, request);

        assertFalse(bean.getStrand());
        assertEquals(List.of(), bean.getPartIds(),
                "the marker turns 'every row removed' into an empty list, not 'field absent'");
    }

    @Test
    void thePartIdsBindInTheOrderTheRowsWerePosted() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/pressroom-ui/authoring/pageEdit!save.rol");
        request.setParameter("bean.strand", "true");
        request.setParameter("_strand", "on");
        request.addParameter("bean.partIds", "post-c");
        request.addParameter("bean.partIds", "post-a");
        request.setParameter("_partIds", "on");
        PageBean bean = new PageBean();

        bind(bean, request);

        assertTrue(bean.getStrand());
        assertEquals(List.of("post-c", "post-a"), bean.getPartIds());
    }

    @Test
    void theBeanCarriesTheSwitchBetweenPageAndForm() {
        WeblogPage page = new WeblogPage();
        page.setStrand(true);
        PageBean bean = new PageBean();
        bean.copyFrom(page);
        assertTrue(bean.getStrand());

        bean.setStrand(false);
        bean.copyTo(page);
        assertFalse(page.getStrand());
    }

    private void bind(PageBean bean, MockHttpServletRequest request) {
        ServletRequestDataBinder binder = new ServletRequestDataBinder(bean, "bean");
        controller.initBeanBinder(binder);
        binder.bind(request);
    }

    // --- fixtures ---

    private PageBean strandBean(WeblogPage page, String... partIds) {
        PageBean bean = new PageBean();
        bean.setId(page.getId());
        bean.setSlug(page.getSlug());
        bean.setTitle("Trip");
        bean.setStrand(true);
        bean.setPartIds(new ArrayList<>(List.of(partIds)));
        return bean;
    }

    private HttpServletRequest requestFor(Weblog weblog) {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getLocale()).thenReturn(Locale.US);
        when(req.getAttribute("authenticatedUser")).thenReturn(user);
        when(req.getAttribute("actionWeblog")).thenReturn(weblog);
        return req;
    }

    private WeblogPage pageOn(Weblog owner, String slug) throws WebloggerException {
        WeblogPage page = new WeblogPage();
        page.setId("page-" + slug);
        page.setWeblog(owner);
        page.setSlug(slug);
        when(weblogger.getWeblogPageManager().getPage(page.getId())).thenReturn(page);
        return page;
    }

    private WeblogEntry postOn(Weblog owner, String name) throws WebloggerException {
        WeblogEntry entry = new WeblogEntry();
        entry.setId("post-" + name);
        entry.setWebsite(owner);
        entry.setTitle(name);
        entry.setStatus(PubStatus.PUBLISHED);
        when(entries.getWeblogEntry(entry.getId())).thenReturn(entry);
        return entry;
    }
}
