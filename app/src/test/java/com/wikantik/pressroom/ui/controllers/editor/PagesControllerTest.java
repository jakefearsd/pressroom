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

import java.util.List;
import java.util.Locale;

import jakarta.servlet.http.HttpServletRequest;

import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.pojos.WeblogPage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ui.Model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link PagesController}: the list view and removal.
 *
 * <p>{@link #removingAPageBelongingToAnotherWeblogIsRefused()} is the same
 * ownership hazard covered for every other by-id action in this package --
 * {@code removeId} is client input and {@code getPage} is a global lookup.
 */
class PagesControllerTest extends EditorControllerTestSupport {

    private Weblog weblogA;
    private Weblog weblogB;

    private PagesController controller;
    private Model model;

    @BeforeEach
    void setUp() {
        controller = prepare(new PagesController());
        model = newModel();

        weblogA = weblog;
        weblogB = new Weblog();
        weblogB.setId("weblog-2");
        weblogB.setHandle("someoneelse");
    }

    @Test
    void listingShowsOnlyThisWeblogsPages() throws Exception {
        WeblogPage mine = pageOn(weblogA, "about");
        when(weblogger.getWeblogPageManager().getPages(weblogA)).thenReturn(List.of(mine));

        String view = controller.execute(requestFor(weblogA), model);

        assertEquals(".Pages", view);
        assertEquals(List.of(mine), model.getAttribute("pages"));
        // Never asked for weblogB's pages at all -- the list is scoped by
        // construction, not filtered after the fact.
        verify(weblogger.getWeblogPageManager(), never()).getPages(weblogB);
    }

    @Test
    void removingAPageDeletesOnlyTheNamedPage() throws Exception {
        WeblogPage mine = pageOn(weblogA, "about");
        when(weblogger.getWeblogPageManager().getPages(weblogA)).thenReturn(List.of());

        String view = controller.remove(requestFor(weblogA), model, mine.getId());

        assertEquals(".Pages", view);
        // To the trash, never straight out of the database (spec 2026-11-15, Part 1).
        verify(weblogger.getWeblogPageManager()).trashPage(mine);
        verify(weblogger.getWeblogPageManager(), never()).removePage(any());
        assertEquals(1, weblogger.flushCount());
    }

    @Test
    void theRemovalNoticeEscapesTheSlugItNames() throws Exception {
        // A slug is author-typed on the page editor and lands in
        // messages.jsp's deliberately-raw sink.
        registerMessage("pages.trash.moved", "removed:{0}");
        WeblogPage mine = pageOn(weblogA, "<b>about</b>");
        when(weblogger.getWeblogPageManager().getPages(weblogA)).thenReturn(List.of());

        controller.remove(requestFor(weblogA), model, mine.getId());

        assertTrue(messages(model).contains("removed:&lt;b&gt;about&lt;/b&gt;"),
                "Expected the slug HTML-escaped, got: " + messages(model));
    }

    @Test
    void removingAPageBelongingToAnotherWeblogIsRefused() throws Exception {
        WeblogPage foreign = pageOn(weblogB, "their-about");
        when(weblogger.getWeblogPageManager().getPages(weblogA)).thenReturn(List.of());

        String view = controller.remove(requestFor(weblogA), model, foreign.getId());

        assertEquals(".Pages", view);
        verify(weblogger.getWeblogPageManager(), never()).removePage(any());
        verify(weblogger.getWeblogPageManager(), never()).trashPage(any());
        assertTrue(errors(model).contains("pageEdit.notFound"),
                "Expected a not-found error, got: " + errors(model));
    }

    @Test
    void removingWithABlankIdIsRefusedRatherThanLookedUp() throws Exception {
        when(weblogger.getWeblogPageManager().getPages(weblogA)).thenReturn(List.of());

        String view = controller.remove(requestFor(weblogA), model, "   ");

        assertEquals(".Pages", view);
        verify(weblogger.getWeblogPageManager(), never()).removePage(any());
    }

    @Test
    void aFailedRemovalIsReportedGenericallyRatherThanThrowing() throws Exception {
        WeblogPage mine = pageOn(weblogA, "about");
        org.mockito.Mockito.doThrow(new WebloggerException("database down"))
                .when(weblogger.getWeblogPageManager()).trashPage(mine);
        when(weblogger.getWeblogPageManager().getPages(weblogA)).thenReturn(List.of());

        String view = controller.remove(requestFor(weblogA), model, mine.getId());

        assertEquals(".Pages", view);
        assertTrue(errors(model).contains("generic.error.check.logs"),
                "Expected a generic failure, got: " + errors(model));
    }

    @Test
    void aFailedListLookupIsReportedGenericallyRatherThanThrowing() throws Exception {
        when(weblogger.getWeblogPageManager().getPages(weblogA))
                .thenThrow(new WebloggerException("database down"));

        String view = controller.execute(requestFor(weblogA), model);

        assertEquals(".Pages", view);
        assertTrue(errors(model).contains("generic.error.check.logs"),
                "Expected a generic failure, got: " + errors(model));
    }

    // --- strands ---

    @Test
    void theListCountsEachStrandsPartsForItsDeleteConfirmation() throws Exception {
        WeblogPage strand = pageOn(weblogA, "trip");
        strand.setStrand(true);
        WeblogPage plain = pageOn(weblogA, "about");
        when(weblogger.getWeblogPageManager().getPages(weblogA)).thenReturn(List.of(strand, plain));
        when(weblogger.getWeblogEntryManager().getStrandPartCount(strand)).thenReturn(3L);

        controller.execute(requestFor(weblogA), model);

        assertEquals(java.util.Map.of(strand.getId(), 3L), model.getAttribute("strandPartCounts"));
        verify(weblogger.getWeblogEntryManager(), never()).getStrandPartCount(plain);
    }

    @Test
    void aFailedPartCountLeavesThatRowWithThePlainConfirmation() throws Exception {
        WeblogPage strand = pageOn(weblogA, "trip");
        strand.setStrand(true);
        when(weblogger.getWeblogPageManager().getPages(weblogA)).thenReturn(List.of(strand));
        when(weblogger.getWeblogEntryManager().getStrandPartCount(strand))
                .thenThrow(new WebloggerException("database down"));

        String view = controller.execute(requestFor(weblogA), model);

        assertEquals(".Pages", view);
        assertEquals(java.util.Map.of(), model.getAttribute("strandPartCounts"));
    }

    @Test
    void trashingAStrandMovesOnlyThePageItsPostsStay() throws Exception {
        // trashPage keeps the parts attached (WeblogPageTrashTest); the
        // controller must not take the posts with it.
        WeblogPage strand = pageOn(weblogA, "trip");
        strand.setStrand(true);
        when(weblogger.getWeblogPageManager().getPages(weblogA)).thenReturn(List.of());

        controller.remove(requestFor(weblogA), model, strand.getId());

        verify(weblogger.getWeblogPageManager()).trashPage(strand);
        verify(weblogger.getWeblogEntryManager(), never()).removeWeblogEntry(any());
    }

    // --- reorder ---

    @Test
    void reorderingSavesTheOrderAndRedirectsWithTheSavedMessage() throws Exception {
        registerMessage("weblogPagesForm.orderSaved", "order saved");
        WeblogPage a = pageOn(weblogA, "a");
        WeblogPage b = pageOn(weblogA, "b");
        org.springframework.web.servlet.mvc.support.RedirectAttributes flash = newRedirectAttributes();

        String view = controller.reorder(requestFor(weblogA), flash,
                List.of(b.getId(), a.getId()));

        assertEquals("redirect:/pressroom-ui/authoring/pages.rol?weblog=" + weblogA.getHandle(), view);
        verify(weblogger.getWeblogPageManager()).setNavOrder(weblogA, List.of(b.getId(), a.getId()));
        assertEquals(1, weblogger.flushCount());
        assertEquals(List.of("order saved"), flashMessages(flash));
    }

    @Test
    void reorderingWithNoIdsChangesNothingAndIsNotAnError() throws Exception {
        org.springframework.web.servlet.mvc.support.RedirectAttributes flash = newRedirectAttributes();

        String view = controller.reorder(requestFor(weblogA), flash, null);

        assertEquals("redirect:/pressroom-ui/authoring/pages.rol?weblog=" + weblogA.getHandle(), view);
        verify(weblogger.getWeblogPageManager(), never()).setNavOrder(any(), any());
        assertTrue(flashErrors(flash).isEmpty(), "got: " + flashErrors(flash));
    }

    @Test
    void aFailedReorderIsReportedGenerically() throws Exception {
        org.mockito.Mockito.doThrow(new WebloggerException("database down"))
                .when(weblogger.getWeblogPageManager()).setNavOrder(any(), any());
        org.springframework.web.servlet.mvc.support.RedirectAttributes flash = newRedirectAttributes();

        String view = controller.reorder(requestFor(weblogA), flash, List.of("page-a"));

        assertEquals("redirect:/pressroom-ui/authoring/pages.rol?weblog=" + weblogA.getHandle(), view);
        assertTrue(flashErrors(flash).contains("generic.error.check.logs"), "got: " + flashErrors(flash));
    }

    @Test
    void theReorderRouteIsPostOnlyAndNeedsTheListsPermission() throws Exception {
        java.lang.reflect.Method m = PagesController.class.getMethod("reorder", HttpServletRequest.class,
                org.springframework.web.servlet.mvc.support.RedirectAttributes.class, List.class);
        assertTrue(m.isAnnotationPresent(org.springframework.web.bind.annotation.PostMapping.class));
        assertEquals("/pagesOrder.rol",
                m.getAnnotation(org.springframework.web.bind.annotation.PostMapping.class).value()[0]);
        assertFalse(m.isAnnotationPresent(org.springframework.web.bind.annotation.GetMapping.class),
                "a GET of the route is refused (405), never a write");
        // The permission is per controller, so it is the Pages screen's own.
        assertEquals(List.of(com.wikantik.pressroom.pojos.WeblogPermission.POST),
                controller.requiredWeblogPermissionActions());
    }

    // --- fixtures ---

    // --- the trash (spec 2026-11-15, Part 1) ---

    /** A double submit, or a stale tab: the page is already in the trash, and stays as it is. */
    @Test
    void removingATrashedIdIsNotFound() throws Exception {
        WeblogPage trashed = pageOn(weblogA, "about");
        trashed.setStatus(WeblogPage.PubStatus.TRASHED);
        when(weblogger.getWeblogPageManager().getPages(weblogA)).thenReturn(List.of());

        controller.remove(requestFor(weblogA), model, trashed.getId());

        verify(weblogger.getWeblogPageManager(), never()).trashPage(any());
        verify(weblogger.getWeblogPageManager(), never()).removePage(any());
        assertTrue(errors(model).contains("pageEdit.notFound"), "got: " + errors(model));
    }

    @Test
    void theListCarriesHowManyPagesAreInTheTrash() throws Exception {
        when(weblogger.getWeblogPageManager().getPages(weblogA)).thenReturn(List.of());
        when(weblogger.getWeblogPageManager().getTrashedPages(weblogA))
                .thenReturn(List.of(new WeblogPage(), new WeblogPage()));

        controller.execute(requestFor(weblogA), model);

        assertEquals(2, model.getAttribute("trashedPageCount"));
    }

    /** A display path: the trash link is left out, the list still renders, no error is shown. */
    @Test
    void aTrashCountThatCannotBeReadLeavesTheListAlone() throws Exception {
        WeblogPage mine = pageOn(weblogA, "about");
        when(weblogger.getWeblogPageManager().getPages(weblogA)).thenReturn(List.of(mine));
        when(weblogger.getWeblogPageManager().getTrashedPages(weblogA))
                .thenThrow(new WebloggerException("database down"));

        String view = controller.execute(requestFor(weblogA), model);

        assertEquals(".Pages", view);
        assertEquals(List.of(mine), model.getAttribute("pages"));
        assertEquals(0, model.getAttribute("trashedPageCount"));
        assertTrue(errors(model).isEmpty(), "got: " + errors(model));
    }

    private HttpServletRequest requestFor(Weblog weblog) {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getLocale()).thenReturn(Locale.US);
        when(req.getAttribute("authenticatedUser")).thenReturn(user);
        when(req.getAttribute("actionWeblog")).thenReturn(weblog);
        return req;
    }

    private WeblogPage pageOn(Weblog weblog, String slug) throws WebloggerException {
        WeblogPage page = new WeblogPage();
        page.setId("page-" + slug);
        page.setWeblog(weblog);
        page.setSlug(slug);
        when(weblogger.getWeblogPageManager().getPage(page.getId())).thenReturn(page);
        return page;
    }
}
