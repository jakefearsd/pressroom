package com.wikantik.pressroom.ui.controllers;

import com.wikantik.pressroom.business.MockWeblogger;
import com.wikantik.pressroom.pojos.ReaderComment;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.pojos.WeblogCategory;
import com.wikantik.pressroom.pojos.WeblogEntry;
import com.wikantik.pressroom.pojos.WeblogPage;
import com.wikantik.pressroom.pojos.WeblogRedirect;
import com.wikantik.pressroom.pojos.WeblogTemplate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Every by-id lookup in {@link WeblogOwnership} answers "absent" for anything
 * that is not provably the action weblog's: a row with no owner recorded, a
 * foreign owner, an unknown id, a blank id, or a store that cannot answer.
 * None of those may read as found, because the permission interceptor vouches
 * only for the action weblog and every id in a request is attacker-chosen.
 * Complements {@link WeblogOwnershipTest}, which runs the entry and redirect
 * cases against the real store.
 */
class WeblogOwnershipRefusalsTest {

    private MockWeblogger mock;
    private Weblog mine;
    private Weblog theirs;

    @BeforeEach
    void setUp() {
        mock = MockWeblogger.create();
        mine = new Weblog();
        mine.setHandle("mine");
        theirs = new Weblog();
        theirs.setHandle("theirs");
    }

    // ---- category

    @Test
    void aCategoryIsFoundOnlyForItsOwnWeblog() throws Exception {
        WeblogCategory category = mock(WeblogCategory.class);
        when(category.getWeblog()).thenReturn(mine);
        when(mock.weblogEntryManager().getWeblogCategory("c1")).thenReturn(category);

        assertSame(category, WeblogOwnership.category(mock.weblogger(), "c1", mine));
        assertNull(WeblogOwnership.category(mock.weblogger(), "c1", theirs));
    }

    @Test
    void aCategoryWithNoWeblogRecordedIsAbsent() throws Exception {
        when(mock.weblogEntryManager().getWeblogCategory("c1")).thenReturn(mock(WeblogCategory.class));

        assertNull(WeblogOwnership.category(mock.weblogger(), "c1", mine));
    }

    @Test
    void anUnknownOrBlankCategoryIdIsAbsentAndABlankOneIsNeverLookedUp() throws Exception {
        assertNull(WeblogOwnership.category(mock.weblogger(), "nope", mine));
        assertNull(WeblogOwnership.category(mock.weblogger(), " ", mine));
        assertNull(WeblogOwnership.category(mock.weblogger(), null, mine));

        verify(mock.weblogEntryManager(), never()).getWeblogCategory(" ");
    }

    // ---- template

    @Test
    void aTemplateIsFoundOnlyForItsOwnWeblog() throws Exception {
        WeblogTemplate template = new WeblogTemplate();
        template.setWeblog(mine);
        when(mock.weblogManager().getTemplate("t1")).thenReturn(template);

        assertSame(template, WeblogOwnership.template(mock.weblogger(), "t1", mine));
        assertNull(WeblogOwnership.template(mock.weblogger(), "t1", theirs));
    }

    @Test
    void aTemplateWithNoWeblogRecordedUnknownOrBlankIsAbsent() throws Exception {
        when(mock.weblogManager().getTemplate("orphan")).thenReturn(new WeblogTemplate());

        assertNull(WeblogOwnership.template(mock.weblogger(), "orphan", mine));
        assertNull(WeblogOwnership.template(mock.weblogger(), "nope", mine));
        assertNull(WeblogOwnership.template(mock.weblogger(), "", mine));
    }

    // ---- redirect

    @Test
    void aRedirectWithNoWeblogRecordedIsAbsent() throws Exception {
        when(mock.weblogRedirectManager().getRedirect("r1")).thenReturn(new WeblogRedirect());

        assertNull(WeblogOwnership.redirect(mock.weblogger(), "r1", mine));
    }

    // ---- page

    @Test
    void aPageIsFoundOnlyForItsOwnWeblog() throws Exception {
        WeblogPage page = new WeblogPage();
        page.setWeblog(mine);
        when(mock.weblogPageManager().getPage("p1")).thenReturn(page);

        assertSame(page, WeblogOwnership.page(mock.weblogger(), "p1", mine));
        assertNull(WeblogOwnership.page(mock.weblogger(), "p1", theirs));
    }

    @Test
    void aPageWithNoWeblogUnknownBlankOrUnreadableIsAbsent() throws Exception {
        when(mock.weblogPageManager().getPage("orphan")).thenReturn(new WeblogPage());
        when(mock.weblogPageManager().getPage("boom"))
                .thenThrow(new com.wikantik.pressroom.WebloggerException("store down"));

        assertNull(WeblogOwnership.page(mock.weblogger(), "orphan", mine));
        assertNull(WeblogOwnership.page(mock.weblogger(), "nope", mine));
        assertNull(WeblogOwnership.page(mock.weblogger(), null, mine));
        assertNull(WeblogOwnership.page(mock.weblogger(), "boom", mine));
    }

    // ---- comment (owned through its entry)

    private ReaderComment commentOn(Weblog weblog) {
        WeblogEntry entry = new WeblogEntry();
        entry.setWebsite(weblog);
        ReaderComment comment = new ReaderComment();
        comment.setEntry(entry);
        return comment;
    }

    @Test
    void aCommentIsFoundOnlyThroughAnEntryOfTheActionWeblog() throws Exception {
        ReaderComment comment = commentOn(mine);
        when(mock.commentManager().getComment("k1")).thenReturn(comment);

        assertSame(comment, WeblogOwnership.comment(mock.weblogger(), "k1", mine));
        assertNull(WeblogOwnership.comment(mock.weblogger(), "k1", theirs));
    }

    @Test
    void aCommentWithNoEntryOrAnEntryWithNoWeblogIsAbsent() throws Exception {
        when(mock.commentManager().getComment("no-entry")).thenReturn(new ReaderComment());
        ReaderComment noWeblog = new ReaderComment();
        noWeblog.setEntry(new WeblogEntry());
        when(mock.commentManager().getComment("no-weblog")).thenReturn(noWeblog);

        assertNull(WeblogOwnership.comment(mock.weblogger(), "no-entry", mine));
        assertNull(WeblogOwnership.comment(mock.weblogger(), "no-weblog", mine));
    }

    @Test
    void anUnknownBlankOrUnreadableCommentIsAbsent() throws Exception {
        when(mock.commentManager().getComment("boom"))
                .thenThrow(new com.wikantik.pressroom.WebloggerException("store down"));

        assertNull(WeblogOwnership.comment(mock.weblogger(), "nope", mine));
        assertNull(WeblogOwnership.comment(mock.weblogger(), "", mine));
        assertNull(WeblogOwnership.comment(mock.weblogger(), "boom", mine));
    }

    // ---- entry, the cases the real-store test leaves out

    @Test
    void anEntryWithNoWeblogRecordedIsAbsent() throws Exception {
        when(mock.weblogEntryManager().getWeblogEntry("e1")).thenReturn(new WeblogEntry());

        assertNull(WeblogOwnership.entry(mock.weblogger(), "e1", mine));
    }

    @Test
    void anEntryLookupThatThrowsIsAbsentAndAnActionWeblogOfNullMatchesNothing() throws Exception {
        when(mock.weblogEntryManager().getWeblogEntry("boom"))
                .thenThrow(new com.wikantik.pressroom.WebloggerException("store down"));
        WeblogEntry entry = new WeblogEntry();
        entry.setWebsite(mine);
        when(mock.weblogEntryManager().getWeblogEntry("e1")).thenReturn(entry);

        assertNull(WeblogOwnership.entry(mock.weblogger(), "boom", null));
        assertNull(WeblogOwnership.entry(mock.weblogger(), "e1", null));
    }
}
