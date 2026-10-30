package com.wikantik.pressroom.build;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Pins the attribution in {@code NOTICE.txt}: the Apache Roller notice is kept // rebrand:keep
 * verbatim, and exactly one derivation line is added after its first paragraph.
 */
class NoticeAttributionTest {

    private static final Path NOTICE =
            Path.of(System.getProperty("user.dir")).getParent().resolve("NOTICE.txt");

    private static final String DERIVATION =
            "Pressroom is derived from Apache Roller (https://roller.apache.org/), " // rebrand:keep
                    + "developed at The Apache Software Foundation.";

    /** The notice as at the base of the rebrand, split on newlines. */
    private static final List<String> ORIGINAL = List.of(
            "Apache Roller", // rebrand:keep
            "Copyright 2001-2019 The Apache Software Foundation",
            "",
            "This product includes software developed at",
            "The Apache Software Foundation (http://www.apache.org/).",
            "",
            "The product includes snippets of code from the Blojsom blog server",
            "Copyright 2003 David A. Czarnecki & Mark Lussier. All rights reserved.",
            "",
            "The product includes snippets of code from the Pebble blog server",
            "Copyright 2003 Simon Brown, All rights reserved.",
            "",
            "The product includes snippets of code written by Sun Microsystems, Inc.",
            "Copyright 2009 Sun Microsystems, Inc.",
            "",
            "The product includes snippets of code written by Dave Johnson for RSS and Atom in Action",
            "Copyright 2005 David M Johnson (For RSS and Atom In Action)",
            "",
            "The product includes snippets of code from Spring Mobile",
            "Copyright (c) 2010-2014 Pivotal, Inc. All Rights Reserved.",
            "",
            "The product includes icons by Mark James (http://www.famfamfam.com/lab/icons)",
            "");

    @Test
    void theOriginalApacheNoticeIsKeptByteForByte() throws IOException {
        String text = Files.readString(NOTICE);
        List<String> kept = new java.util.ArrayList<>(List.of(text.split("\n", -1)));
        int at = kept.indexOf(DERIVATION);
        assertTrue(at > 0, "derivation line missing");
        kept.remove(at);
        // the derivation line is set off by a blank line; drop one with it
        kept.remove(at);
        assertEquals(ORIGINAL, kept);
    }

    @Test
    void theDerivationLineIsPresentExactlyOnce() throws IOException {
        long n = Files.readAllLines(NOTICE).stream().filter(DERIVATION::equals).count();
        assertEquals(1, n);
    }
}
