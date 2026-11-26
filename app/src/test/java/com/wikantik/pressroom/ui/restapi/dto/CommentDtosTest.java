package com.wikantik.pressroom.ui.restapi.dto;

import java.util.Date;
import com.wikantik.pressroom.pojos.ReaderComment;
import com.wikantik.pressroom.pojos.User;
import com.wikantik.pressroom.pojos.WeblogEntry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What the comments API tells a caller about one comment, with and without the optional parts. */
class CommentDtosTest {

    private static ReaderComment bare() {
        WeblogEntry entry = new WeblogEntry();
        entry.setId("e1");
        entry.setTitle("Tom &amp; Jerry");
        ReaderComment c = new ReaderComment();
        c.setId("c1");
        c.setEntry(entry);
        c.setStatus(ReaderComment.Status.PUBLISHED);
        return c;
    }

    @Test
    void aBareCommentCarriesNoAuthorNoDatesNoThread() {
        var view = CommentDtos.toView(bare());

        assertEquals("c1", view.id());
        assertEquals("e1", view.entryId());
        assertEquals("Tom & Jerry", view.entryTitle());
        assertEquals("PUBLISHED", view.status());
        assertNull(view.created());
        assertNull(view.statusChanged());
        assertNull(view.authorUserName());
        assertNull(view.authorDisplayName());
        assertFalse(view.authorIsOwner());
        assertNull(view.replyTo());
        assertNull(view.reply());
    }

    @Test
    void aNullBodyIsAnEmptyStringNotAMissingField() {
        assertEquals("", CommentDtos.toView(bare()).body());
        ReaderComment c = bare();
        c.setBody("hello");
        assertEquals("hello", CommentDtos.toView(c).body());
    }

    @Test
    void aFullCommentCarriesAuthorDatesAndThreadIds() {
        ReaderComment c = bare();
        User author = new User();
        author.setUserName("dora");
        author.setScreenName("Dora D");
        c.setAuthor(author);
        c.setAuthorIsOwner(true);
        Date created = new Date(1_700_000_000_000L);
        Date changed = new Date(1_700_000_100_000L);
        c.setCreated(created);
        c.setStatusChanged(changed);
        ReaderComment parent = new ReaderComment();
        parent.setId("p1");
        ReaderComment reply = new ReaderComment();
        reply.setId("r1");
        c.setReplyTo(parent);
        c.setReply(reply);

        var view = CommentDtos.toView(c);

        assertEquals("dora", view.authorUserName());
        assertEquals("Dora D", view.authorDisplayName());
        assertTrue(view.authorIsOwner());
        assertEquals(created.toInstant(), view.created());
        assertEquals(changed.toInstant(), view.statusChanged());
        assertEquals("p1", view.replyTo());
        assertEquals("r1", view.reply());
    }
}
