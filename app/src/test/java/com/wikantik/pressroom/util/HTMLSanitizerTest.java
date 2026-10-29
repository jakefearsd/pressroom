/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  The ASF licenses this file to You
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
package com.wikantik.pressroom.util;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The sanitizer is a security control: everything it fails to strip ends up in
 * a visitor's browser, and every entry on the site passes through it.
 *
 * <p>Two halves. {@link Attacks} pins what must never survive. The markup
 * classes pin what must always survive -- each shortcode emits a specific
 * shape, and a policy that quietly drops one of their attributes breaks a
 * feature without failing anything else.
 */
class HTMLSanitizerTest {

    @Test
    void keepsExactlyTheThreeUmamiAttributesOnAnchors() {
        String out = HTMLSanitizer.sanitize(
                "<a href=\"https://x.example.com\" data-umami-event=\"e\""
                + " data-umami-event-entry=\"s\" data-umami-event-dest=\"d\""
                + " data-umami-event-foo=\"z\">x</a>"
                + "<div data-umami-event=\"e\">y</div>");
        assertTrue(out.contains("data-umami-event=\"e\""), out);
        assertTrue(out.contains("data-umami-event-entry=\"s\""), out);
        assertTrue(out.contains("data-umami-event-dest=\"d\""), out);
        assertFalse(out.contains("data-umami-event-foo"), out);
        assertEquals(3, out.split("data-umami-event", -1).length - 1, out);
    }

    @Nested
    class Attacks {

        @Test
        void scriptIsRemovedEntirely() {
            String out = HTMLSanitizer.sanitize("<p>before</p><script>alert(1)</script><p>after</p>");
            assertFalse(out.contains("<script"), out);
            assertFalse(out.contains("alert(1)"), out);
            assertTrue(out.contains("before"), out);
            assertTrue(out.contains("after"), out);
        }

        @Test
        void eventHandlerAttributesAreRemoved() {
            String out = HTMLSanitizer.sanitize(
                    "<p onclick=\"steal()\" onmouseover=\"x()\">text</p>");
            assertFalse(out.contains("onclick"), out);
            assertFalse(out.contains("onmouseover"), out);
            assertTrue(out.contains("text"), out);
        }

        @Test
        void scriptBearingUrlSchemesAreRemoved() {
            assertFalse(HTMLSanitizer.sanitize("<a href=\"javascript:alert(1)\">x</a>")
                    .contains("javascript"), "javascript: href");
            assertFalse(HTMLSanitizer.sanitize("<img src=\"javascript:alert(1)\">")
                    .contains("javascript"), "javascript: src");
            assertFalse(HTMLSanitizer.sanitize("<img src=\"data:image/gif;base64,R0lGOD\">")
                    .contains("data:"), "data: URLs are not a way in either");
        }

        @Test
        void embeddingElementsAreRemoved() {
            for (String markup : new String[]{
                    "<iframe src=\"http://evil.example\"></iframe>",
                    "<object data=\"x.swf\"></object>",
                    "<embed src=\"x.swf\">",
                    "<form action=\"/steal\"><input name=\"p\"></form>",
                    "<style>body{display:none}</style>",
                    "<link rel=\"stylesheet\" href=\"http://evil.example/x.css\">"}) {
                String out = HTMLSanitizer.sanitize(markup);
                assertFalse(out.contains("<iframe"), out);
                assertFalse(out.contains("<object"), out);
                assertFalse(out.contains("<embed"), out);
                assertFalse(out.contains("<form"), out);
                assertFalse(out.contains("<input"), out);
                assertFalse(out.contains("<style"), out);
                assertFalse(out.contains("<link"), out);
            }
        }

        @Test
        void dangerousCssIsRemovedButOrdinaryCssSurvives() {
            assertFalse(HTMLSanitizer.sanitize("<p style=\"width:expression(alert(1))\">x</p>")
                    .contains("expression"), "IE expression()");
            assertFalse(HTMLSanitizer.sanitize("<p style=\"background:url('javascript:alert(1)')\">x</p>")
                    .contains("javascript"), "javascript: inside url()");
            assertTrue(HTMLSanitizer.sanitize("<p style=\"background-color:#979695\">x</p>")
                    .contains("background-color"), "the blurhash placeholder colour must survive");
        }

        @Test
        void malformedMarkupCannotSmuggleATagThrough() {
            // The regex sanitizer this replaced was weakest exactly here: a
            // parser now decides what a tag is, rather than a pattern.
            String out = HTMLSanitizer.sanitize("<scr<script>ipt>alert(1)</script>");
            // The leftover "alert(1)" text is fine -- it is inert text content.
            // What must not survive is a tag the browser would execute.
            assertFalse(out.toLowerCase().contains("<script"), out);
            assertFalse(out.contains("<scr<"), out);
        }

        @Test
        void entityTextCannotBecomeRealMarkup() {
            // Wave 3's finding in its general form: literal entity text must
            // stay literal text and never be decoded into structure.
            String out = HTMLSanitizer.sanitize("<p>&lt;script&gt;alert(1)&lt;/script&gt;</p>");
            assertFalse(out.contains("<script"), out);
        }
    }

    @Nested
    class AuthoredContent {

        @Test
        void ordinaryProseAndStructureSurvive() {
            String out = HTMLSanitizer.sanitize(
                    "<h2>Day one</h2><p>A <strong>long</strong> drive, <em>worth it</em>.</p>"
                            + "<ul><li>pack a towel</li></ul>"
                            + "<blockquote><p>quoted</p></blockquote>"
                            + "<pre><code>code block</code></pre>");
            for (String expected : new String[]{"<h2>", "<strong>", "<em>", "<ul>", "<li>",
                    "<blockquote>", "<pre>", "<code>"}) {
                assertTrue(out.contains(expected), expected + " missing from: " + out);
            }
        }

        @Test
        void markdownTablesSurvive() {
            String out = HTMLSanitizer.sanitize(
                    "<table><thead><tr><th scope=\"col\">a</th></tr></thead>"
                            + "<tbody><tr><td colspan=\"2\">b</td></tr></tbody></table>");
            assertTrue(out.contains("<table>"), out);
            assertTrue(out.contains("scope=\"col\""), out);
            assertTrue(out.contains("colspan=\"2\""), out);
        }

        @Test
        void relativeAndFragmentLinksSurvive() {
            // Markdown authors link to their own posts constantly. The previous
            // sanitizer stripped every relative href, which would have made
            // Markdown authoring miserable.
            assertTrue(HTMLSanitizer.sanitize("<a href=\"/2026/08/other-post\">x</a>")
                    .contains("href=\"/2026/08/other-post\""), "site-absolute");
            assertTrue(HTMLSanitizer.sanitize("<a href=\"../sibling\">x</a>")
                    .contains("href=\"../sibling\""), "relative");
            assertTrue(HTMLSanitizer.sanitize("<a href=\"#section\">x</a>")
                    .contains("href=\"#section\""), "fragment");
            assertTrue(HTMLSanitizer.sanitize("<a href=\"https://example.com/x\">x</a>")
                    .contains("https://example.com/x"), "absolute");
        }

        @Test
        void aLinkMayOpenInANewTabWithoutHandingTheNewTabThisPage() {
            String out = HTMLSanitizer.sanitize(
                    "<a href=\"https://www.parks.ca.gov/\" target=\"_blank\">State Parks</a>");
            assertTrue(out.contains("target=\"_blank\""), out);
            // window.opener would let the opened site navigate this tab
            assertTrue(out.matches("(?s).*rel=\"[^\"]*\\bnoopener\\b[^\"]*\".*"), out);
        }

        @Test
        void aNewTabIsTheOnlyTargetALinkMayName() {
            for (String target : new String[] {"_top", "_parent", "_self", "sidebar"}) {
                String out = HTMLSanitizer.sanitize(
                        "<a href=\"https://example.com/\" target=\"" + target + "\">x</a>");
                assertFalse(out.contains("target="), target + ": " + out);
                assertTrue(out.contains("href=\"https://example.com/\""), target + ": " + out);
            }
        }
    }

    @Nested
    class ShortcodeFigureMarkup {

        private static final String FIGURE = "<figure class=\"shortcode-image\">\n"
                + "<picture>\n"
                + "<source type=\"image/webp\" srcset=\"http://e.com/m/1?w=480 480w, http://e.com/m/1 500w\" sizes=\"100vw\">\n"
                + "<img src=\"http://e.com/m/1\" srcset=\"http://e.com/m/1?w=480 480w, http://e.com/m/1 500w\" sizes=\"100vw\" width=\"500\" height=\"333\" alt=\"A hawk\" loading=\"lazy\" data-blurhash=\"LEHV6nWB2yk8\">\n"
                + "</picture>\n"
                + "<figcaption>A hawk</figcaption>\n"
                + "</figure>";

        @Test
        void keepsTheFigurePictureSourceStructure() {
            String out = HTMLSanitizer.sanitize(FIGURE);
            assertTrue(out.contains("<figure class=\"shortcode-image\">"), out);
            assertTrue(out.contains("<picture>"), out);
            assertTrue(out.contains("<source"), out);
            assertTrue(out.contains("type=\"image/webp\""), out);
            assertTrue(out.contains("<figcaption>A hawk</figcaption>"), out);
        }

        @Test
        void keepsTheResponsiveImageAttributes() {
            String out = HTMLSanitizer.sanitize(FIGURE);
            for (String attr : new String[]{"srcset=", "sizes=", "width=\"500\"",
                    "height=\"333\"", "alt=\"A hawk\"", "loading=\"lazy\"",
                    "data-blurhash=\"LEHV6nWB2yk8\""}) {
                assertTrue(out.contains(attr), attr + " missing from: " + out);
            }
        }
    }

    @Nested
    class GalleryGridMarkup {

        private static final String GRID = "<div class=\"jgrid jgrid-h320\">\n"
                + "<figure class=\"ar-135\">\n"
                + "<a href=\"http://e.com/m/1\" data-pswp-width=\"500\" data-pswp-height=\"373\""
                + " data-caption=\"Hawk at dusk\" data-exif-camera=\"NIKON Z 6\""
                + " data-exif-aperture=\"f/2.8\" data-exif-iso=\"400\""
                + " data-blurhash=\"LEHV6nWB2yk8\">\n"
                + "<img src=\"http://e.com/m/1\" srcset=\"http://e.com/m/1?w=480 480w, http://e.com/m/1 500w\""
                + " sizes=\"375px\" width=\"500\" height=\"373\" alt=\"hawk.jpg\""
                + " loading=\"lazy\" decoding=\"async\" data-blurhash=\"LEHV6nWB2yk8\""
                + " style=\"background-color:#979695\">\n"
                + "</a>\n"
                + "<figcaption>Hawk at dusk</figcaption>\n"
                + "</figure>\n"
                + "</div>";

        @Test
        void keepsTheGridAndItsRatioClasses() {
            // The ratio and row height ride as classes precisely because the
            // policy cannot carry a --ar/--row-h custom property.
            String out = HTMLSanitizer.sanitize(GRID);
            assertTrue(out.contains("class=\"jgrid jgrid-h320\""), out);
            assertTrue(out.contains("class=\"ar-135\""), out);
        }

        @Test
        void keepsTheLightboxDataPayload() {
            String out = HTMLSanitizer.sanitize(GRID);
            for (String attr : new String[]{"data-pswp-width=\"500\"", "data-pswp-height=\"373\"",
                    "data-caption=\"Hawk at dusk\"", "data-exif-camera=\"NIKON Z 6\"",
                    "data-exif-aperture=\"f/2.8\"", "data-exif-iso=\"400\"",
                    "data-blurhash=\"LEHV6nWB2yk8\""}) {
                assertTrue(out.contains(attr), attr + " missing from: " + out);
            }
            assertTrue(out.contains("background-color"), "blurhash placeholder colour: " + out);
        }
    }

    @Nested
    class TravelShortcodeMarkup {

        private static final String PINS = "[{&quot;lat&quot;:48.8584,&quot;lng&quot;:2.2945,"
                + "&quot;label&quot;:&quot;Eiffel Tower&quot;},"
                + "{&quot;lat&quot;:48.8606,&quot;lng&quot;:2.3376}]";

        @Test
        void keepsTheMapPayload() {
            String out = HTMLSanitizer.sanitize("<div class=\"travel-map\" data-pins=\"" + PINS
                    + "\" data-center=\"48.8584,2.2945\" data-zoom=\"13\" data-route=\"true\"></div>");
            assertTrue(out.contains("class=\"travel-map\""), out);
            assertTrue(out.contains("data-center=\"48.8584,2.2945\""), out);
            assertTrue(out.contains("data-zoom=\"13\""), out);
            assertTrue(out.contains("data-route=\"true\""), out);
            assertTrue(out.contains("data-pins="), out);
            // the entity form may be re-encoded, but the payload must still
            // decode to the JSON the map script parses
            assertTrue(out.contains("48.8584"), out);
            assertTrue(out.contains("Eiffel Tower"), out);
        }

        @Test
        void keepsTheCtaCard() {
            String out = HTMLSanitizer.sanitize("<a class=\"cta-card\""
                    + " href=\"https://booking.example.com/cottage?utm_source=blog\""
                    + " rel=\"nofollow sponsored noopener\" target=\"_blank\">"
                    + "<span class=\"cta-label\">Check availability</span>"
                    + "<span class=\"cta-note\">Sleeps four</span></a>");
            assertTrue(out.contains("class=\"cta-card\""), out);
            assertTrue(out.contains("class=\"cta-label\""), out);
            // CtaShortcode opens the booking site in a new tab, and its rel
            // keeps search engines from crediting a paid link
            assertTrue(out.contains("target=\"_blank\""), out);
            for (String rel : new String[] {"nofollow", "sponsored", "noopener"}) {
                assertTrue(out.matches("(?s).*rel=\"[^\"]*\\b" + rel + "\\b[^\"]*\".*"), rel + ": " + out);
            }
            // The policy entity-encodes "=" inside attribute values, so the
            // href reads utm_source&#61;blog in the page source. That is not
            // breakage: a browser decodes entities in an attribute before it
            // resolves the URL, so the request still carries utm_source=blog.
            // The same is true of the "?w=480" candidates in every srcset.
            assertTrue(out.contains("utm_source&#61;blog") || out.contains("utm_source=blog"),
                    "campaign tag must survive in some encoding: " + out);
            assertEquals("https://booking.example.com/cottage?utm_source=blog",
                    org.apache.commons.text.StringEscapeUtils.unescapeHtml4(
                            out.replaceAll("(?s).*href=\"([^\"]*)\".*", "$1")),
                    "and must decode back to exactly the URL the author gave");
        }

        @Test
        void keepsTheFaqList() {
            String out = HTMLSanitizer.sanitize(
                    "<dl class=\"faq\"><dt>How long?</dt><dd>Four hours.</dd></dl>");
            assertTrue(out.contains("<dl class=\"faq\">"), out);
            assertTrue(out.contains("<dt>How long?</dt>"), out);
            assertTrue(out.contains("<dd>Four hours.</dd>"), out);
        }
    }

    @Nested
    class ConditionalSanitizing {

        @Test
        void theSwitchDecidesWhetherAnythingHappens() {
            Boolean previous = HTMLSanitizer.xssEnabled;
            try {
                String hostile = "<p onclick=\"x()\">text</p>";

                HTMLSanitizer.xssEnabled = Boolean.TRUE;
                assertFalse(HTMLSanitizer.conditionallySanitize(hostile).contains("onclick"),
                        "enabled: the handler must go");

                HTMLSanitizer.xssEnabled = Boolean.FALSE;
                assertEquals(hostile, HTMLSanitizer.conditionallySanitize(hostile),
                        "disabled: content passes through byte for byte");
            } finally {
                HTMLSanitizer.xssEnabled = previous;
            }
        }

        @Test
        void nullSurvivesEitherWay() {
            assertNull(HTMLSanitizer.sanitize(null));
            assertNull(HTMLSanitizer.conditionallySanitize(null));
        }
    }

    @Nested
    class Escaping {

        @Test
        void quotesAndTagsAreEncoded() {
            assertEquals("&quot;q&quot; &#39;a&#39;", HTMLSanitizer.htmlEncodeApexes("\"q\" 'a'"));
            assertEquals("&lt;b&gt;", HTMLSanitizer.htmlEncodeTag("<b>"));
            assertEquals("&lt;b&gt; &quot;q&quot;",
                    HTMLSanitizer.htmlEncodeApexesAndTags("<b> \"q\""));
        }

        @Test
        void nullIsPassedThrough() {
            assertNull(HTMLSanitizer.htmlEncodeApexesAndTags(null));
            assertNull(HTMLSanitizer.htmlEncodeApexes(null));
            assertNull(HTMLSanitizer.htmlEncodeTag(null));
        }
    }
}
