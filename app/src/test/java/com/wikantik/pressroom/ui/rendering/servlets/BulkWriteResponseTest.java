package com.wikantik.pressroom.ui.rendering.servlets;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The rendering tests' response takes a buffer in one write where Spring's
 * mock writes and flushes byte by byte, and is otherwise Spring's: same
 * content, same commit rule (committed once the content passes the buffer
 * size), same stream-versus-writer exclusivity.
 */
class BulkWriteResponseTest {

    @Test
    void aBulkWriteLeavesTheSameContentAndCommitStateAsSpringsByteByByteWrite() throws Exception {
        byte[] page = "x".repeat(10_000).getBytes(StandardCharsets.UTF_8);
        MockHttpServletResponse spring = new MockHttpServletResponse();
        MockHttpServletResponse bulk = new RenderingTestSupport.BulkWriteResponse();

        spring.getOutputStream().write(page);
        bulk.getOutputStream().write(page);

        assertArrayEquals(spring.getContentAsByteArray(), bulk.getContentAsByteArray());
        assertEquals(spring.isCommitted(), bulk.isCommitted());
        assertTrue(bulk.isCommitted(), "past the buffer size, the response is committed");
    }

    @Test
    void aWriteWithinTheBufferDoesNotCommit() throws Exception {
        MockHttpServletResponse bulk = new RenderingTestSupport.BulkWriteResponse();
        bulk.getOutputStream().write("small".getBytes(StandardCharsets.UTF_8));
        bulk.getOutputStream().write('!');

        assertEquals("small!", bulk.getContentAsString());
        assertFalse(bulk.isCommitted());
    }

    @Test
    void theStreamIsOneObjectAndExcludesTheWriterAsSpringsDoes() throws Exception {
        MockHttpServletResponse bulk = new RenderingTestSupport.BulkWriteResponse();
        assertSame(bulk.getOutputStream(), bulk.getOutputStream());
        bulk.getOutputStream().write(new byte[] {'a', 'b', 'c'}, 1, 2);
        assertEquals("bc", bulk.getContentAsString());

        MockHttpServletResponse writerFirst = new RenderingTestSupport.BulkWriteResponse();
        writerFirst.getWriter();
        assertThrows(IllegalStateException.class, writerFirst::getOutputStream);
    }
}
