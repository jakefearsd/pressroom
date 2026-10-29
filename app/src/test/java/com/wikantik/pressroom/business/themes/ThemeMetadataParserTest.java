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
package com.wikantik.pressroom.business.themes;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.stream.Stream;

import com.wikantik.pressroom.pojos.TemplateRendition.RenditionType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link ThemeMetadataParser}, focused on the five places a
 * malformed theme.xml throws {@link ThemeParsingException}. Each of these
 * used to discard the parse failure's cause (see CLAUDE.md's
 * PreserveStackTrace note) -- every assertion here checks the cause survived
 * alongside the message, so the fix stays pinned.
 */
class ThemeMetadataParserTest {

    private static final String HEADER =
            "<weblogtheme><id>t</id><name>t</name><preview-image path=\"p.png\" />";
    private static final String FOOTER = "</weblogtheme>";

    private ThemeMetadata parse(String xml) throws Exception {
        InputStream in = new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8));
        return new ThemeMetadataParser().unmarshall(in);
    }

    @Test
    void unknownTemplateActionValueIsWrappedWithItsCause() {
        String xml = HEADER
                + "<template action=\"bogus\"><name>t</name><link>t</link></template>"
                + FOOTER;

        ThemeParsingException ex = assertThrows(ThemeParsingException.class, () -> parse(xml));

        assertEquals("Unknown template action value 'bogus'", ex.getMessage());
        assertNotNull(ex.getCause(),
                "the IllegalArgumentException that caused this must survive as the cause");
    }

    @Test
    void invalidTemplateRenditionTypeIsWrappedWithItsCause() {
        String xml = HEADER
                + "<template action=\"weblog\"><name>t</name><link>t</link>"
                + "<rendition type=\"bogus\"><templateLanguage>velocity</templateLanguage>"
                + "<contentsFile>t.vm</contentsFile></rendition></template>"
                + FOOTER;

        ThemeParsingException ex = assertThrows(ThemeParsingException.class, () -> parse(xml));

        assertEquals("Invalid rendition type bogus found.", ex.getMessage());
        assertNotNull(ex.getCause());
    }

    @Test
    void unknownTemplateLanguageIsWrappedWithItsCause() {
        String xml = HEADER
                + "<template action=\"weblog\"><name>t</name><link>t</link>"
                + "<rendition><templateLanguage>bogus</templateLanguage>"
                + "<contentsFile>t.vm</contentsFile></rendition></template>"
                + FOOTER;

        ThemeParsingException ex = assertThrows(ThemeParsingException.class, () -> parse(xml));

        assertEquals("Unknown templateLanguage value 'bogus'", ex.getMessage());
        assertNotNull(ex.getCause());
    }

    @Test
    void invalidStylesheetRenditionTypeIsWrappedWithItsCause() {
        String xml = HEADER
                + "<stylesheet><name>s</name><link>s</link>"
                + "<rendition type=\"bogus\"><templateLanguage>velocity</templateLanguage>"
                + "<contentsFile>s.css</contentsFile></rendition></stylesheet>"
                + FOOTER;

        ThemeParsingException ex = assertThrows(ThemeParsingException.class, () -> parse(xml));

        assertEquals("Invalid rendition type bogus found.", ex.getMessage());
        assertNotNull(ex.getCause());
    }

    @Test
    void unknownStylesheetTemplateLanguageIsWrappedWithItsCause() {
        String xml = HEADER
                + "<stylesheet><name>s</name><link>s</link>"
                + "<rendition><templateLanguage>bogus</templateLanguage>"
                + "<contentsFile>s.css</contentsFile></rendition></stylesheet>"
                + FOOTER;

        ThemeParsingException ex = assertThrows(ThemeParsingException.class, () -> parse(xml));

        assertEquals("Unknown templateLanguage value 'bogus'", ex.getMessage());
        assertNotNull(ex.getCause());
    }

    // RenditionType has exactly one value (STANDARD), so an explicit
    // type="standard" attribute is the only value that can ever parse
    // successfully -- the two tests below drive that success path, distinct
    // from the default-when-absent branch every other fixture above exercises.

    @Test
    void templateRenditionWithExplicitStandardTypeIsAccepted() throws Exception {
        String xml = HEADER
                + "<template action=\"weblog\"><name>t</name><link>t</link>"
                + "<rendition type=\"standard\"><templateLanguage>velocity</templateLanguage>"
                + "<contentsFile>t.vm</contentsFile></rendition></template>"
                + FOOTER;

        ThemeMetadata parsed = parse(xml);

        Iterator<ThemeMetadataTemplate> templates = parsed.getTemplates().iterator();
        assertTrue(templates.hasNext(), "the template must have parsed");
        ThemeMetadataTemplateRendition rendition =
                templates.next().getTemplateRendition(RenditionType.STANDARD);
        assertNotNull(rendition, "the explicit standard rendition must be reachable by type");
        assertEquals(RenditionType.STANDARD, rendition.getType());
    }

    @Test
    void stylesheetRenditionWithExplicitStandardTypeIsAccepted() throws Exception {
        String xml = HEADER
                + "<template action=\"weblog\"><name>t</name><link>t</link>"
                + "<rendition><templateLanguage>velocity</templateLanguage>"
                + "<contentsFile>t.vm</contentsFile></rendition></template>"
                + "<stylesheet><name>s</name><link>s</link>"
                + "<rendition type=\"standard\"><templateLanguage>velocity</templateLanguage>"
                + "<contentsFile>s.css</contentsFile></rendition></stylesheet>"
                + FOOTER;

        ThemeMetadata parsed = parse(xml);

        ThemeMetadataTemplateRendition rendition =
                parsed.getStylesheet().getTemplateRendition(RenditionType.STANDARD);
        assertNotNull(rendition, "the explicit standard rendition must be reachable by type");
        assertEquals(RenditionType.STANDARD, rendition.getType());
    }

    // --- required elements ---------------------------------------------------
    //
    // Characterisation tests, written against the existing behaviour and
    // expected to pass immediately: each names what a theme.xml left out, and
    // that message is all a theme author gets in the startup log.

    private static final String WEBLOG_TEMPLATE =
            "<template action=\"weblog\"><name>t</name>"
            + "<rendition><templateLanguage>velocity</templateLanguage>"
            + "<contentsFile>t.vm</contentsFile></rendition></template>";

    static Stream<Arguments> descriptorsMissingARequiredElement() {
        return Stream.of(
                Arguments.of("no id",
                        "<weblogtheme><name>t</name><preview-image path=\"p.png\" />"
                                + WEBLOG_TEMPLATE + FOOTER,
                        "'id' and 'name' are required theme elements"),
                Arguments.of("no name",
                        "<weblogtheme><id>t</id><preview-image path=\"p.png\" />"
                                + WEBLOG_TEMPLATE + FOOTER,
                        "'id' and 'name' are required theme elements"),
                Arguments.of("no preview image",
                        "<weblogtheme><id>t</id><name>t</name>" + WEBLOG_TEMPLATE + FOOTER,
                        "No preview image specified"),
                Arguments.of("no weblog template",
                        HEADER + "<template action=\"permalink\"><name>p</name></template>"
                                + FOOTER,
                        "did not find a template of action = 'weblog'"),
                Arguments.of("a template with no action",
                        HEADER + "<template><name>t</name></template>" + FOOTER,
                        "Template must contain an 'action' element"),
                Arguments.of("a template with no name",
                        HEADER + "<template action=\"weblog\"><link>t</link></template>" + FOOTER,
                        "templates must contain a 'name' element"),
                Arguments.of("a rendition with no language",
                        HEADER + "<template action=\"weblog\"><name>t</name>"
                                + "<rendition><contentsFile>t.vm</contentsFile></rendition>"
                                + "</template>" + FOOTER,
                        "rendition must contain a 'templateLanguage' element"),
                Arguments.of("a template rendition with no file",
                        HEADER + "<template action=\"weblog\"><name>t</name>"
                                + "<rendition><templateLanguage>velocity</templateLanguage>"
                                + "</rendition></template>" + FOOTER,
                        "Rendition must contain a 'contentsFile' element"),
                Arguments.of("a stylesheet rendition with no file",
                        HEADER + "<stylesheet><name>s</name><link>s</link>"
                                + "<rendition><templateLanguage>velocity</templateLanguage>"
                                + "</rendition></stylesheet>" + WEBLOG_TEMPLATE + FOOTER,
                        "stylesheet must contain a 'contentsFile' element"),
                Arguments.of("a stylesheet with no name",
                        HEADER + "<stylesheet><link>s</link></stylesheet>"
                                + WEBLOG_TEMPLATE + FOOTER,
                        "stylesheet must contain a 'name' element"),
                Arguments.of("a stylesheet with no link",
                        HEADER + "<stylesheet><name>s</name></stylesheet>"
                                + WEBLOG_TEMPLATE + FOOTER,
                        "stylesheet must contain a 'link' element"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("descriptorsMissingARequiredElement")
    void aDescriptorMissingARequiredElementIsRefusedNamingIt(String what, String xml,
            String expectedMessage) {
        ThemeParsingException ex = assertThrows(ThemeParsingException.class, () -> parse(xml));

        assertEquals(expectedMessage, ex.getMessage());
    }

    /**
     * The navbar flag is what puts a theme page in the weblog's navigation
     * menu; no bundled theme sets it, and it is read case-insensitively.
     */
    @Test
    void theNavbarAndHiddenFlagsAreReadCaseInsensitively() throws Exception {
        String xml = HEADER
                + "<template action=\"weblog\"><name>t</name>"
                + "<navbar>TRUE</navbar><hidden>True</hidden>"
                + "<rendition><templateLanguage>velocity</templateLanguage>"
                + "<contentsFile>t.vm</contentsFile></rendition></template>"
                + "<template action=\"custom\"><name>plain</name>"
                + "<navbar>yes</navbar>"
                + "<rendition><templateLanguage>velocity</templateLanguage>"
                + "<contentsFile>p.vm</contentsFile></rendition></template>"
                + FOOTER;

        ThemeMetadata parsed = parse(xml);

        ThemeMetadataTemplate flagged = parsed.getTemplates().stream()
                .filter(t -> "t".equals(t.getName())).findFirst().orElseThrow();
        ThemeMetadataTemplate plain = parsed.getTemplates().stream()
                .filter(t -> "plain".equals(t.getName())).findFirst().orElseThrow();
        assertTrue(flagged.isNavbar());
        assertTrue(flagged.isHidden());
        assertFalse(plain.isNavbar(), "only \"true\" turns the flag on");
        assertFalse(plain.isHidden(), "and it is off when absent");
    }
}
