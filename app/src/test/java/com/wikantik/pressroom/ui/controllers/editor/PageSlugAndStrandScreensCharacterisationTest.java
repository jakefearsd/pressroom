package com.wikantik.pressroom.ui.controllers.editor;

import java.util.List;
import java.util.Locale;

import jakarta.servlet.http.HttpServletRequest;

import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.business.WeblogPageManager;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.pojos.WeblogPage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ui.Model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Characterisation test (spec 2026-11-20-jsp-rules-and-view-models-design,
 * Task 4): written against the code before the page slug and strand rules
 * moved into {@code PageRules}, and passing on it. It pins what the page
 * editor answers today (errors with their arguments, the marked field, the
 * view, whether anything was saved) for each slug and strand case the move
 * touches. The manager is mocked, so a stand-in refuses what the real
 * {@code savePage} refuses (a blank, slashed, reserved or locale slug, with
 * its message) and what the database would (a duplicate slug). Cases the task
 * changes on purpose are named "today" and are rewritten, new expectation
 * first, in the commit that changes them.
 */
class PageSlugAndStrandScreensCharacterisationTest extends EditorControllerTestSupport {

    private PageEditController controller;
    private Model model;
    private WeblogPageManager pages;

    @BeforeEach
    void setUp() throws Exception {
        controller = prepare(new PageEditController());
        model = newModel();
        pages = weblogger.getWeblogPageManager();
        registerMessage("pageEdit.error.strandHasParts", "This strand has {0} posts.");
        // What JPAWeblogPageManagerImpl.savePage refuses, with its messages, and what the unique index would.
        doAnswer(call -> {
            String slug = ((WeblogPage) call.getArgument(0)).getSlug();
            String trimmed = slug == null ? "" : slug.trim();
            if (trimmed.isEmpty()) {
                throw new WebloggerException("page slug is required");
            }
            if (trimmed.indexOf('/') >= 0) {
                throw new WebloggerException("page slug may not contain '/': " + trimmed);
            }
            if (com.wikantik.pressroom.pojos.ReservedSlugs.isReserved(trimmed)) {
                throw new WebloggerException("page slug is reserved: " + trimmed);
            }
            if (com.wikantik.pressroom.ui.rendering.util.LocaleSegment.isLocale(trimmed)) {
                throw new WebloggerException("page slug is reserved (it reads as a locale): " + trimmed);
            }
            return null;
        }).when(pages).savePage(any(), any(), any());
    }

    private PageBean bean(String title, String slug) {
        PageBean bean = new PageBean();
        bean.setTitle(title);
        bean.setSlug(slug);
        return bean;
    }

    private HttpServletRequest requestFor(Weblog owner) {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getLocale()).thenReturn(Locale.US);
        when(req.getAttribute("authenticatedUser")).thenReturn(user);
        when(req.getAttribute("actionWeblog")).thenReturn(owner);
        return req;
    }

    private WeblogPage stored(String id, String slug) throws WebloggerException {
        WeblogPage page = new WeblogPage();
        page.setId(id);
        page.setWeblog(weblog);
        page.setSlug(slug);
        page.setTitle("Title");
        when(pages.getPage(id)).thenReturn(page);
        return page;
    }

    private WeblogPage holder(String id, String slug, WeblogPage.PubStatus status) throws WebloggerException {
        WeblogPage held = stored(id, slug);
        held.setStatus(status);
        when(pages.getPageBySlug(weblog, slug)).thenReturn(held);
        return held;
    }

    private void save(PageBean bean) {
        assertEquals(".PageEdit", controller.save(requestFor(weblog), model, bean));
    }

    // --- slugs the manager refuses today ---

    @Test
    void aReservedSlugIsTheReservedErrorOnTheSlugField() {
        save(bean("Feed", "feed"));
        assertEquals(List.of("pageEdit.error.slugReserved"), errors(model));
        assertEquals(List.of("page_bean_slug"), invalidFields(model));
    }

    @Test
    void aSlugThatReadsAsALocaleIsTheReservedError() {
        save(bean("English", "en_US"));
        assertEquals(List.of("pageEdit.error.slugReserved"), errors(model));
        assertEquals(List.of("page_bean_slug"), invalidFields(model));
    }

    @Test
    void aSlugWithASlashIsTheInvalidError() {
        save(bean("Deep", "a/b"));
        assertEquals(List.of("pageEdit.error.slugInvalid"), errors(model));
        assertEquals(List.of("page_bean_slug"), invalidFields(model));
    }

    @Test
    void aBlankSlugOnAnExistingPageIsTheInvalidError() throws Exception {
        WeblogPage page = stored("p1", "about");
        PageBean bean = bean("About", "   ");
        bean.setId(page.getId());
        save(bean);
        assertEquals(List.of("pageEdit.error.slugInvalid"), errors(model));
        assertEquals(List.of("page_bean_slug"), invalidFields(model));
    }

    @Test
    void aNewPageWhoseTitleHasNoLettersAndNoSlugIsTheInvalidError() {
        save(bean("!!!", ""));
        assertEquals(List.of("pageEdit.error.slugInvalid"), errors(model));
        assertEquals(List.of("page_bean_slug"), invalidFields(model));
    }

    @Test
    void aNewPageWithNoSlugTakesItsTitles() throws Exception {
        save(bean("About Us", ""));
        assertTrue(errors(model).isEmpty(), errors(model).toString());
        ArgumentCaptor<WeblogPage> saved = ArgumentCaptor.forClass(WeblogPage.class);
        verify(pages).savePage(saved.capture(), any(), any());
        assertEquals("about-us", saved.getValue().getSlug());
    }

    @Test
    void aNewPagesTitleSlugThatIsReservedIsTheReservedError() {
        save(bean("Feed", ""));
        assertEquals(List.of("pageEdit.error.slugReserved"), errors(model));
    }

    // --- slugs other pages hold ---

    @Test
    void aSlugHeldByAPageInTheTrashIsTheTrashErrorAndNothingIsSaved() throws Exception {
        holder("t1", "about", WeblogPage.PubStatus.TRASHED);
        save(bean("About", "about"));
        assertEquals(List.of("pageEdit.error.slugInTrash"), errors(model));
        assertEquals(List.of("page_bean_slug"), invalidFields(model));
        verify(pages, never()).savePage(any(), any(), any());
    }

    @Test
    void aNewPageWhoseTitleSlugIsHeldByTheTrashIsTheTrashError() throws Exception {
        holder("t1", "about-us", WeblogPage.PubStatus.TRASHED);
        save(bean("About Us", ""));
        assertEquals(List.of("pageEdit.error.slugInTrash"), errors(model));
    }

    @Test
    void renamingOntoASlugHeldByTheTrashIsTheTrashError() throws Exception {
        WeblogPage mine = stored("p1", "contact");
        holder("t1", "about", WeblogPage.PubStatus.TRASHED);
        PageBean bean = bean("Contact", "about");
        bean.setId(mine.getId());
        save(bean);
        assertEquals(List.of("pageEdit.error.slugInTrash"), errors(model));
        assertEquals("contact", mine.getSlug());
    }

    @Test
    void aPageThatHoldsItsOwnSlugSavesWithoutError() throws Exception {
        WeblogPage mine = stored("p1", "about");
        when(pages.getPageBySlug(weblog, "about")).thenReturn(mine);
        PageBean bean = bean("About", "about");
        bean.setId(mine.getId());
        save(bean);
        assertTrue(errors(model).isEmpty(), errors(model).toString());
        verify(pages).savePage(any(), any(), any());
    }

    /** Changed on purpose (spec Task 4, criterion 4): a live holder was left to the unique index ("check the logs"). */
    @Test
    void aSlugHeldByALivePageIsTheTakenErrorOnTheSlugFieldAndNothingIsSaved() throws Exception {
        holder("p9", "about", WeblogPage.PubStatus.PUBLISHED);
        save(bean("About", "about"));
        assertEquals(List.of("pageEdit.error.slugTaken"), errors(model));
        assertEquals(List.of("page_bean_slug"), invalidFields(model));
        verify(pages, never()).savePage(any(), any(), any());
    }

    @Test
    void renamingOntoASlugALivePageHoldsIsTheTakenError() throws Exception {
        WeblogPage mine = stored("p1", "contact");
        holder("p9", "about", WeblogPage.PubStatus.PUBLISHED);
        PageBean bean = bean("Contact", "about");
        bean.setId(mine.getId());
        save(bean);
        assertEquals(List.of("pageEdit.error.slugTaken"), errors(model));
        assertEquals("contact", mine.getSlug());
    }

    /**
     * Changed on purpose (spec Task 4, as built): no length check; the database refused the value and the editor
     * said "check the logs". The slug is held to its column, as the API holds it.
     */
    @Test
    void aSlugOverTheColumnIsTheTooLongErrorOnTheSlugFieldAndNothingIsSaved() throws Exception {
        save(bean("Long", "s".repeat(256)));
        assertEquals(List.of("pageEdit.error.slugTooLong"), errors(model));
        assertEquals(List.of("page_bean_slug"), invalidFields(model));
        verify(pages, never()).savePage(any(), any(), any());
        model = newModel();
        save(bean("Long", "s".repeat(255)));
        assertTrue(errors(model).isEmpty(), errors(model).toString());
    }

    /**
     * Changed on purpose (spec Task 4, criterion 3): the manager's refusal of a slug was only reached once the
     * form had no other error, so a reserved slug beside a bad canonical URL showed the URL error alone. The
     * slug is judged by the rules before the save now, so both are shown at once.
     */
    @Test
    void aBadSlugAndAnotherFieldErrorAreBothShown() {
        PageBean bean = bean("Feed", "feed");
        bean.setCanonicalUrl("javascript:alert(1)");
        save(bean);
        assertEquals(List.of("entryEdit.canonicalUrlInvalid", "pageEdit.error.slugReserved"), errors(model));
        assertEquals(List.of("seo_canonicalUrl", "page_bean_slug"), invalidFields(model));
    }

    @Test
    void aSlugLookupThatFailsRefusesTheSave() throws Exception {
        when(pages.getPageBySlug(weblog, "about")).thenThrow(new WebloggerException("database down"));
        save(bean("About", "about"));
        assertEquals(List.of("generic.error.check.logs"), errors(model));
        verify(pages, never()).savePage(any(), any(), any());
    }

    // --- strands ---

    private PageBean strandBean(WeblogPage page, boolean strand) {
        PageBean bean = bean("Trip", page.getSlug());
        bean.setId(page.getId());
        bean.setStrand(strand);
        return bean;
    }

    @Test
    void aSitePageIsSilentlyNotAStrand() throws Exception {
        weblog.setHandle(Weblog.SITE_SPACE_HANDLE);
        weblog.setSiteSpace(true);
        WeblogPage page = stored("p1", "about");
        save(strandBean(page, true));
        assertTrue(errors(model).isEmpty(), errors(model).toString());
        assertFalse(page.getStrand());
    }

    @Test
    void unsettingAStrandWithPartsIsTheHasPartsErrorOnTheStrandBoxAndItStaysOn() throws Exception {
        WeblogPage page = stored("p1", "trip");
        page.setStrand(true);
        when(weblogger.getWeblogEntryManager().getStrandPartCount(page)).thenReturn(4L);
        PageBean bean = strandBean(page, false);
        save(bean);
        assertEquals(List.of("This strand has 4 posts."), errors(model));
        assertEquals(List.of("page_bean_strand"), invalidFields(model));
        assertTrue(bean.getStrand());
        verify(pages, never()).savePage(any(), any(), any());
    }

    @Test
    void unsettingAStrandWithNoPartsSaves() throws Exception {
        WeblogPage page = stored("p1", "trip");
        page.setStrand(true);
        when(weblogger.getWeblogEntryManager().getStrandPartCount(page)).thenReturn(0L);
        save(strandBean(page, false));
        assertTrue(errors(model).isEmpty(), errors(model).toString());
        assertFalse(page.getStrand());
    }

    @Test
    void aPartCountThatCannotBeReadRefusesTheUnsettingAndItStaysOn() throws Exception {
        WeblogPage page = stored("p1", "trip");
        page.setStrand(true);
        when(weblogger.getWeblogEntryManager().getStrandPartCount(page))
                .thenThrow(new WebloggerException("database down"));
        PageBean bean = strandBean(page, false);
        save(bean);
        assertEquals(List.of("generic.error.check.logs"), errors(model));
        assertTrue(invalidFields(model).isEmpty());
        assertTrue(bean.getStrand());
        verify(pages, never()).savePage(any(), any(), any());
    }
}
