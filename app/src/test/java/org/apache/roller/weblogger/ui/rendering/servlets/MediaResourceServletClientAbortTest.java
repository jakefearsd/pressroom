package org.apache.roller.weblogger.ui.rendering.servlets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import java.io.IOException;
import org.apache.catalina.connector.ClientAbortException;
import org.apache.logging.log4j.Level;
import org.apache.roller.weblogger.TestUtils;
import org.apache.roller.weblogger.pojos.MediaFile;
import org.apache.roller.weblogger.pojos.User;
import org.apache.roller.weblogger.pojos.Weblog;
import org.apache.roller.weblogger.ui.core.security.sso.SsoLogCapture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * A reader who leaves a gallery page mid-image is not a server error.
 *
 * <p>Tomcat reports the closed connection as a {@link ClientAbortException}
 * on the next write. The servlet used to log that at ERROR with a full stack
 * trace, once per abandoned image, which buried real failures in the log.
 */
class MediaResourceServletClientAbortTest {

    private User user;
    private Weblog weblog;

    @BeforeEach
    void setUp() throws Exception {
        RenderingTestSupport.ensureRenderingRuntime();
        RenderingTestSupport.clearRenderCaches();
        user = TestUtils.setupUser("abortuser");
        weblog = TestUtils.setupWeblog("abortblog", user);
        TestUtils.endSession(true);
    }

    @AfterEach
    void tearDown() throws Exception {
        TestUtils.teardownWeblog(weblog.getId());
        TestUtils.teardownUser(user.getUserName());
        TestUtils.endSession(true);
    }

    @Test
    void aReaderWhoLeavesMidImageIsNotLoggedAsAnError() throws Exception {
        MediaFile image = TestUtils.setupImageMediaFile(weblog, "abandoned.jpg");
        TestUtils.endSession(true);

        MockHttpServletRequest request = RenderingTestSupport.anonymousGet(
                "/roller-ui/rendering/media-resources", "/abortblog/" + image.getId());
        AbandonedResponse response = new AbandonedResponse();

        try (SsoLogCapture capture = SsoLogCapture.everyLogger()) {
            try {
                RenderingTestSupport.mediaResourceServlet().service(request, response);
            } finally {
                TestUtils.endSession(false);
            }

            assertTrue(capture.lines(Level.ERROR).isEmpty(), "logged as an error: " + capture.lines(Level.ERROR));
            assertTrue(capture.lines(Level.WARN).isEmpty(), "logged as a warning: " + capture.lines(Level.WARN));
            assertTrue(capture.lines(Level.DEBUG).stream().anyMatch(line -> line.contains(image.getId())),
                    "the abandoned file is still named at DEBUG: " + capture.lines(Level.DEBUG));
        }
        assertEquals(200, response.getStatus(), "nothing is sent after the reader has gone");
    }

    /** A response whose reader closed the connection once the first bytes went out. */
    private static final class AbandonedResponse extends MockHttpServletResponse {
        private final ServletOutputStream gone = new ServletOutputStream() {
            @Override
            public void write(int b) throws IOException {
                throw abort();
            }

            @Override
            public void write(byte[] b, int off, int len) throws IOException {
                throw abort();
            }

            @Override
            public boolean isReady() {
                return true;
            }

            @Override
            public void setWriteListener(WriteListener listener) {
                // not asynchronous
            }
        };

        private IOException abort() {
            setCommitted(true);
            return new ClientAbortException(new IOException("Broken pipe"));
        }

        @Override
        public ServletOutputStream getOutputStream() {
            return gone;
        }
    }
}
