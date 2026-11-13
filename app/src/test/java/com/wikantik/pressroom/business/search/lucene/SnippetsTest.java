/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  The ASF licenses this file to You
 * under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.  For additional information regarding
 * copyright in this work, please see the NOTICE file in the top level
 * directory of this distribution.
 */
package com.wikantik.pressroom.business.search.lucene;

import java.util.ArrayList;
import java.util.List;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.en.EnglishAnalyzer;
import org.apache.lucene.queryparser.classic.MultiFieldQueryParser;
import org.apache.lucene.search.Query;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The reader-facing passage: escaped text with only {@code <mark>} markup. */
class SnippetsTest {

    private static final Analyzer ANALYZER = new EnglishAnalyzer();

    private static String html(String text, String words, int max) throws Exception {
        Query query = SearchOperation.parse(words, MultiFieldQueryParser.Operator.OR);
        return Snippets.html(text, query, ANALYZER, max);
    }

    @Test
    void marksTheMatchedWords() throws Exception {
        String out = html("We said our kitchen remodel cost too much.", "kitchen remodel", 160);
        assertTrue(out.contains("<mark>kitchen</mark>"), out);
        assertTrue(out.contains("<mark>remodel</mark>"), out);
    }

    @Test
    void marksStemmedForms() throws Exception {
        assertTrue(html("The remodeling took a month.", "remodel", 160).contains("<mark>remodeling</mark>"));
    }

    @Test
    void escapesMarkupInTheText() throws Exception {
        String out = html("<script>alert(1)</script> remodel", "remodel", 160);
        assertTrue(out.contains("&lt;script&gt;"), out);
        assertFalse(out.contains("<script"), out);
        assertEquals("", out.replace("<mark>", "").replace("</mark>", "")
                .replaceAll("[^<>]", ""), "no markup other than mark survives");
    }

    @Test
    void escapesQuotesAndAmpersandOnce() throws Exception {
        String out = html("Fish & chips \"remodel\" it's", "remodel", 160);
        assertTrue(out.contains("Fish &amp; chips"), out);
        assertFalse(out.contains("&amp;amp;"), out);
        assertFalse(out.contains("\""), out);
        assertFalse(out.contains("'"), out);
    }

    @Test
    void staysNearTheLimit() throws Exception {
        String text = "alpha beta gamma delta ".repeat(100) + "remodel " + "epsilon zeta eta theta ".repeat(100);
        String out = html(text, "remodel", 160);
        String visible = out.replace("<mark>", "").replace("</mark>", "");
        assertTrue(visible.length() <= 200, "" + visible.length());
        assertTrue(out.contains("<mark>remodel</mark>"), out);
        assertEquals(visible.strip(), visible, "no ragged edges");
    }

    /** Captures what Snippets' own logger emits. */
    private static final class Capture extends AbstractAppender {
        private final List<LogEvent> events = new ArrayList<>();

        Capture() {
            super("snippets-test-appender", null, null, false, null);
        }

        @Override
        public void append(LogEvent event) {
            events.add(event.toImmutable());
        }
    }

    /** A post with no passage match is the ordinary excerpt-fallback case: empty, and nothing logged. */
    @Test
    void noMatchIsEmpty() throws Exception {
        Logger logger = (Logger) LogManager.getLogger(Snippets.class);
        Level previous = logger.getLevel();
        Capture capture = new Capture();
        capture.start();
        logger.addAppender(capture);
        logger.setLevel(Level.ALL);
        try {
            assertEquals("", html("nothing to see here", "remodel", 160));
            assertEquals("", html("", "remodel", 160));
            assertEquals("", html(null, "remodel", 160));
        } finally {
            logger.removeAppender(capture);
            logger.setLevel(previous);
            capture.stop();
        }
        assertEquals(List.of(), capture.events.stream()
                .filter(e -> e.getLevel().isMoreSpecificThan(Level.WARN))
                .map(e -> e.getMessage().getFormattedMessage() + " / " + e.getThrown()).toList());
    }
}
