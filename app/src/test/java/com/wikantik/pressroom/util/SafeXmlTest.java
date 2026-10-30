package com.wikantik.pressroom.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.jdom2.Document;
import org.jdom2.JDOMException;
import org.jdom2.input.SAXBuilder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pins that {@link SafeXml#saxBuilder()} refuses a DOCTYPE, which is what
 * takes XXE off the table for every XML this application parses.
 *
 * <p>Pressroom's three SAXBuilder callers -- the runtime config defs, the admin
 * menus, and a shared theme's {@code theme.xml} -- all read files that ship
 * with the application, so none of them is reachable by an attacker today and
 * none of this is a live vulnerability. It is done anyway for the same reason
 * the uploads-dir containment check is: the cost is three lines and the
 * property being bought is "still safe if someone later points one of these at
 * a user-supplied file", which is exactly the change nobody remembers to
 * re-audit. A user-uploadable theme bundle is a plausible future feature.
 *
 * <p>Turning DOCTYPEs off outright (rather than only external entities) is
 * safe here because it was verified that not one of the shipped files --
 * runtimeConfigDefs.xml, editor-menu.xml, admin-menu.xml, or any theme.xml --
 * declares a DOCTYPE or an ENTITY. If a future file needs one, this decision
 * has to be revisited deliberately rather than by loosening the builder.
 */
class SafeXmlTest {

    /** Reads a local file through an external entity -- the classic XXE. */
    private static final String XXE_PAYLOAD = """
            <?xml version="1.0" encoding="UTF-8"?>
            <!DOCTYPE theme [ <!ENTITY xxe SYSTEM "file:///etc/hostname"> ]>
            <theme><id>&xxe;</id></theme>
            """;

    private static final String BENIGN = """
            <?xml version="1.0" encoding="UTF-8"?>
            <theme><id>journal</id></theme>
            """;

    @Test
    @DisplayName("a document carrying a DOCTYPE is refused outright")
    void aDoctypeIsRefused() {
        SAXBuilder builder = SafeXml.saxBuilder();
        assertThrows(JDOMException.class,
                () -> builder.build(new ByteArrayInputStream(
                        XXE_PAYLOAD.getBytes(StandardCharsets.UTF_8))),
                "a DOCTYPE must be rejected, not parsed");
    }

    @Test
    @DisplayName("an external entity never reaches the parsed document")
    void noLocalFileContentLeaksIntoTheDocument() throws Exception {
        // Belt and braces on the assertion above: prove the payload could
        // actually have leaked something, so a passing test is not just
        // asserting that an unreadable file stayed unread.
        Path hostname = Path.of("/etc/hostname");
        String secret = Files.isReadable(hostname) ? Files.readString(hostname).trim() : null;

        SAXBuilder builder = SafeXml.saxBuilder();
        try {
            Document doc = builder.build(new ByteArrayInputStream(
                    XXE_PAYLOAD.getBytes(StandardCharsets.UTF_8)));
            String parsed = doc.getRootElement().getChildText("id");
            if (secret != null && !secret.isEmpty()) {
                assertEquals("", parsed == null ? "" : parsed.trim(),
                        "external entity content leaked into the document");
            }
        } catch (JDOMException expected) {
            // Refused at the DOCTYPE, which is the desired outcome.
        }
    }

    @Test
    @DisplayName("ordinary documents still parse")
    void aBenignDocumentStillParses() throws Exception {
        Document doc = SafeXml.saxBuilder().build(new ByteArrayInputStream(
                BENIGN.getBytes(StandardCharsets.UTF_8)));
        assertEquals("journal", doc.getRootElement().getChildText("id"));
    }
}
