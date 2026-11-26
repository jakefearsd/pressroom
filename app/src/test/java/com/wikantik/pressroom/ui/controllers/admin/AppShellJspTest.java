package com.wikantik.pressroom.ui.controllers.admin;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The shell page, read as text: the stylesheets head.jsp loads, in its order
 * (DesignTokenTest keeps the tokens file before pressroom.css), one bootstrap
 * block and the versioned bundle, every URL through {@code c:url} so the
 * context path is applied.
 */
class AppShellJspTest {

    private static final Path SHELL = Path.of("src/main/webapp/WEB-INF/jsps/app/Shell.jsp");
    private static final Path HEAD = Path.of("src/main/webapp/WEB-INF/jsps/tiles/head.jsp");

    private static int indexIn(String text, String needle) {
        int at = text.indexOf(needle);
        assertTrue(at >= 0, needle + " missing");
        return at;
    }

    /**
     * The shell loads the stylesheets the server-rendered pages load, plus the
     * editor surface's: head.jsp used to add pressroom-editor.css only on the
     * two editor screens, and every React screen shares the one shell, so it is
     * here for all of them. Without it the writing surface lost its layout.
     */
    @Test
    void theShellLoadsTheSharedStylesheetsInOrderThenTheEditorSurface() throws IOException {
        String shell = Files.readString(SHELL);
        String head = Files.readString(HEAD);
        String[] sheets = {"/webjars/bootstrap/5.3.8/css/bootstrap.min.css",
                "/webjars/bootstrap-icons/1.13.2/font/bootstrap-icons.min.css",
                "/pressroom-ui/styles/pressroom-tokens.css", "/pressroom-ui/styles/pressroom.css"};
        int shellAt = -1;
        int headAt = -1;
        for (String sheet : sheets) {
            assertTrue(indexIn(shell, sheet) > shellAt, sheet + " out of order in the shell");
            assertTrue(indexIn(head, sheet) > headAt, sheet + " out of order in head.jsp");
            shellAt = shell.indexOf(sheet);
            headAt = head.indexOf(sheet);
        }
        assertTrue(indexIn(shell, "/pressroom-ui/styles/pressroom-editor.css") > shellAt,
                "the editor surface's stylesheet must follow pressroom.css in the shell");
    }

    @Test
    void oneBootstrapBlockAndTheVersionedBundleAllUnderTheContextPath() throws IOException {
        String shell = Files.readString(SHELL);

        assertEquals(1, shell.split("id=\"pressroom-bootstrap\"", -1).length - 1);
        assertTrue(shell.contains("<script type=\"application/json\" id=\"pressroom-bootstrap\">"
                + "<c:out value=\"${bootstrapJson}\" escapeXml=\"false\"/></script>"));
        assertTrue(shell.contains("<c:url value='/pressroom-ui/app/admin.js'>"
                + "<c:param name='v' value='${appVersion}'/></c:url>"));
        assertTrue(shell.contains("<div id=\"pressroom-root\"></div>"));
        assertFalse(shell.matches("(?s).*(href|src)=\"/.*"), "no root-relative URL that ignores the context path");
    }
}
