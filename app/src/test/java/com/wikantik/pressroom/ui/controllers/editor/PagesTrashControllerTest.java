package com.wikantik.pressroom.ui.controllers.editor;

import java.sql.Timestamp;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.business.runnable.TrashPurgeTask;
import com.wikantik.pressroom.config.WebloggerRuntimeConfig;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.pojos.WeblogPage;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

/**
 * The page trash screen (spec 2026-11-15, Part 1). What it must hold: it is
 * the only screen that resolves a trashed page by id, and it resolves
 * nothing else, so a live page's id or another blog's trash can be neither
 * "restored" (demoted to a draft) nor deleted for good from here.
 */
class PagesTrashControllerTest extends EditorControllerTestSupport {

    private static final String REDIRECT = "redirect:/pressroom-ui/authoring/pagesTrash.rol?weblog=";

    private Weblog other;
    private PagesTrashController controller;
    private Model model;
    private RedirectAttributes redirectAttributes;
    private HttpServletRequest request;

    @BeforeEach
    void setUp() {
        controller = prepare(new PagesTrashController());
        model = newModel();
        redirectAttributes = newRedirectAttributes();
        other = new Weblog();
        other.setId("weblog-2");
        other.setHandle("someoneelse");
        request = mock(HttpServletRequest.class);
        when(request.getLocale()).thenReturn(Locale.US);
        when(request.getAttribute("authenticatedUser")).thenReturn(user);
        when(request.getAttribute("actionWeblog")).thenReturn(weblog);
    }

    private WeblogPage page(Weblog owner, String slug, WeblogPage.PubStatus status) throws WebloggerException {
        WeblogPage page = new WeblogPage();
        page.setId("page-" + slug);
        page.setWeblog(owner);
        page.setSlug(slug);
        page.setTitle("Title of " + slug);
        page.setStatus(status);
        if (status == WeblogPage.PubStatus.TRASHED) {
            page.setTrashedAt(new Timestamp(1_800_000_000_000L));
        }
        when(weblogger.getWeblogPageManager().getPage(page.getId())).thenReturn(page);
        return page;
    }

    private String redirect() {
        return REDIRECT + weblog.getHandle();
    }

    // ------------------------------------------------------------------ list

    @Test
    void theTrashViewListsTrashedPagesWithTheDayEachIsPurged() throws Exception {
        WeblogPage trashed = page(weblog, "about", WeblogPage.PubStatus.TRASHED);
        when(weblogger.getWeblogPageManager().getTrashedPages(weblog)).thenReturn(List.of(trashed));

        try (MockedStatic<WebloggerRuntimeConfig> config = mockStatic(
                WebloggerRuntimeConfig.class, withSettings().defaultAnswer(CALLS_REAL_METHODS))) {
            config.when(() -> WebloggerRuntimeConfig.getIntProperty(TrashPurgeTask.PAGE_RETENTION_PROPERTY))
                    .thenReturn(7);

            assertEquals(".PagesTrash", controller.execute(request, model));
        }

        assertEquals(List.of(trashed), model.getAttribute("trashedPages"));
        assertEquals(7, model.getAttribute("pageTrashRetentionDays"));
        Map<?, ?> purgeDates = (Map<?, ?>) model.getAttribute("purgeDates");
        assertNotNull(purgeDates);
        assertEquals(new Date(1_800_000_000_000L + TimeUnit.DAYS.toMillis(7)), purgeDates.get(trashed.getId()));
    }

    /** Retention -1 keeps trash until deleted by hand: there is no date to show. */
    @Test
    void withTrashKeptForeverNoPurgeDateIsShown() throws Exception {
        WeblogPage trashed = page(weblog, "about", WeblogPage.PubStatus.TRASHED);
        when(weblogger.getWeblogPageManager().getTrashedPages(weblog)).thenReturn(List.of(trashed));

        try (MockedStatic<WebloggerRuntimeConfig> config = mockStatic(
                WebloggerRuntimeConfig.class, withSettings().defaultAnswer(CALLS_REAL_METHODS))) {
            config.when(() -> WebloggerRuntimeConfig.getIntProperty(TrashPurgeTask.PAGE_RETENTION_PROPERTY))
                    .thenReturn(-1);

            controller.execute(request, model);
        }

        assertEquals(-1, model.getAttribute("pageTrashRetentionDays"));
        assertTrue(((Map<?, ?>) model.getAttribute("purgeDates")).isEmpty());
    }

    @Test
    void aTrashThatCannotBeReadIsReportedAndShownEmpty() throws Exception {
        when(weblogger.getWeblogPageManager().getTrashedPages(weblog))
                .thenThrow(new WebloggerException("database down"));

        assertEquals(".PagesTrash", controller.execute(request, model));

        assertEquals(List.of(), model.getAttribute("trashedPages"));
        assertTrue(errors(model).contains("generic.error.check.logs"), "got: " + errors(model));
    }

    // --------------------------------------------------------------- restore

    @Test
    void restoreBringsThePageBackAndSaysItIsADraft() throws Exception {
        registerMessage("pages.trash.restored", "restored:{0}");
        WeblogPage trashed = page(weblog, "<b>about</b>", WeblogPage.PubStatus.TRASHED);

        String view = controller.restore(request, trashed.getId(), redirectAttributes);

        assertEquals(redirect(), view);
        verify(weblogger.getWeblogPageManager()).restorePage(trashed);
        assertEquals(1, weblogger.flushCount());
        assertEquals(List.of("restored:&lt;b&gt;about&lt;/b&gt;"), flashMessages(redirectAttributes),
                "the slug is author-typed and the message sink does not escape");
    }

    /** "Restoring" a live page would demote a published page to a draft through the back door. */
    @Test
    void restoreOfALivePageIsNotFound() throws Exception {
        WeblogPage live = page(weblog, "about", WeblogPage.PubStatus.PUBLISHED);

        String view = controller.restore(request, live.getId(), redirectAttributes);

        assertEquals(redirect(), view);
        verify(weblogger.getWeblogPageManager(), never()).restorePage(any());
        assertEquals(List.of("pageEdit.notFound"), flashErrors(redirectAttributes));
    }

    @Test
    void restoreOfAnotherBlogsTrashIsNotFound() throws Exception {
        WeblogPage foreign = page(other, "their-about", WeblogPage.PubStatus.TRASHED);

        controller.restore(request, foreign.getId(), redirectAttributes);

        verify(weblogger.getWeblogPageManager(), never()).restorePage(any());
        assertEquals(List.of("pageEdit.notFound"), flashErrors(redirectAttributes));
    }

    @Test
    void restoreWithABlankOrUnknownIdIsNotFound() throws Exception {
        controller.restore(request, "  ", redirectAttributes);
        controller.restore(request, "no-such-page", redirectAttributes);

        verify(weblogger.getWeblogPageManager(), never()).restorePage(any());
    }

    @Test
    void aFailedRestoreIsReportedAndDoesNotClaimSuccess() throws Exception {
        WeblogPage trashed = page(weblog, "about", WeblogPage.PubStatus.TRASHED);
        doThrow(new WebloggerException("database down"))
                .when(weblogger.getWeblogPageManager()).restorePage(trashed);

        controller.restore(request, trashed.getId(), redirectAttributes);

        assertEquals(List.of("generic.error.check.logs"), flashErrors(redirectAttributes));
        assertTrue(flashMessages(redirectAttributes).isEmpty());
    }

    // -------------------------------------------------------- delete forever

    @Test
    void deleteForeverRemovesTheTrashedPage() throws Exception {
        registerMessage("pages.trash.deletedForever", "gone:{0}");
        WeblogPage trashed = page(weblog, "about", WeblogPage.PubStatus.TRASHED);

        String view = controller.delete(request, trashed.getId(), redirectAttributes);

        assertEquals(redirect(), view);
        verify(weblogger.getWeblogPageManager()).removePage(trashed);
        assertEquals(1, weblogger.flushCount());
        assertEquals(List.of("gone:about"), flashMessages(redirectAttributes));
    }

    /** Deleting forever is a second step after trashing, never a way around it. */
    @Test
    void deleteForeverOfALivePageIsNotFound() throws Exception {
        WeblogPage live = page(weblog, "about", WeblogPage.PubStatus.DRAFT);

        controller.delete(request, live.getId(), redirectAttributes);

        verify(weblogger.getWeblogPageManager(), never()).removePage(any());
        assertEquals(List.of("pageEdit.notFound"), flashErrors(redirectAttributes));
    }

    @Test
    void deleteForeverOfAnotherBlogsTrashIsNotFound() throws Exception {
        WeblogPage foreign = page(other, "their-about", WeblogPage.PubStatus.TRASHED);

        controller.delete(request, foreign.getId(), redirectAttributes);

        verify(weblogger.getWeblogPageManager(), never()).removePage(any());
        assertEquals(List.of("pageEdit.notFound"), flashErrors(redirectAttributes));
    }

    @Test
    void aFailedDeleteForeverIsReportedAndDoesNotClaimSuccess() throws Exception {
        WeblogPage trashed = page(weblog, "about", WeblogPage.PubStatus.TRASHED);
        doThrow(new WebloggerException("database down"))
                .when(weblogger.getWeblogPageManager()).removePage(trashed);

        controller.delete(request, trashed.getId(), redirectAttributes);

        assertEquals(List.of("generic.error.check.logs"), flashErrors(redirectAttributes));
        assertTrue(flashMessages(redirectAttributes).isEmpty());
    }

    // ------------------------------------------------------------- the routes

    /** Both changes are POST only (a GET is a 405), and the list is the one GET. */
    @Test
    void theTwoChangesArePostOnly() throws Exception {
        var restore = PagesTrashController.class.getMethod("restore",
                HttpServletRequest.class, String.class, RedirectAttributes.class);
        var delete = PagesTrashController.class.getMethod("delete",
                HttpServletRequest.class, String.class, RedirectAttributes.class);
        var list = PagesTrashController.class.getMethod("execute", HttpServletRequest.class, Model.class);

        assertEquals("/pagesTrash!restore.rol", restore.getAnnotation(PostMapping.class).value()[0]);
        assertNull(restore.getAnnotation(GetMapping.class));
        assertEquals("/pagesTrash!delete.rol", delete.getAnnotation(PostMapping.class).value()[0]);
        assertNull(delete.getAnnotation(GetMapping.class));
        assertEquals("/pagesTrash.rol", list.getAnnotation(GetMapping.class).value()[0]);
    }

    /** Site pages have a trash too, and the Pages tab stays the current one. */
    @Test
    void itServesTheSiteSpaceUnderThePagesTab() {
        assertTrue(controller.allowsSiteSpace());
        assertEquals("pages", controller.getActionName());
        assertEquals("pages.trash.title", controller.getPageTitle());
    }
}
