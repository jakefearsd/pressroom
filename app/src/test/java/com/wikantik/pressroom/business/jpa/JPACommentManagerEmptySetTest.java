package com.wikantik.pressroom.business.jpa;

import java.util.List;

import com.wikantik.pressroom.pojos.ReaderComment;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/** The queue's manager query is never "all weblogs": an empty set answers empty and 0 without touching the store. */
class JPACommentManagerEmptySetTest {

    @Test
    void anEmptySetOfWeblogsDoesNoQuery() throws Exception {
        JPAPersistenceStrategy strategy = mock(JPAPersistenceStrategy.class);
        JPACommentManagerImpl manager = new JPACommentManagerImpl(strategy);

        assertTrue(manager.getComments(List.of(), ReaderComment.Status.PENDING, 0, 50).isEmpty());
        assertEquals(0, manager.countComments(List.of(), ReaderComment.Status.PENDING));
        assertTrue(manager.getComments(List.of(), null, 0, 50).isEmpty());

        verifyNoInteractions(strategy);
    }
}
