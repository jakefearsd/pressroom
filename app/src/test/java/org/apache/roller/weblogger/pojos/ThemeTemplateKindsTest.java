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
package org.apache.roller.weblogger.pojos;

import java.util.Date;

import org.apache.roller.weblogger.business.themes.SharedThemeTemplate;
import org.apache.roller.weblogger.pojos.TemplateRendition.RenditionType;
import org.apache.roller.weblogger.pojos.TemplateRendition.TemplateLanguage;
import org.apache.roller.weblogger.pojos.ThemeTemplate.ComponentType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The two {@link ThemeTemplate} implementations the renderer is handed that
 * are not database rows: the built-in fallback ({@code StaticThemeTemplate},
 * e.g. the static-page and preview templates) and a theme's own file-backed
 * template ({@code SharedThemeTemplate}). The renderer picks its engine,
 * cache entry and output type from exactly these fields.
 */
class ThemeTemplateKindsTest {

    @Test
    void aStaticTemplateIsNamedByItsIdAndStartsOutVisibleStandardAndUnrendered() throws Exception {
        StaticThemeTemplate t = new StaticThemeTemplate("templates/weblog/page.vm", TemplateLanguage.VELOCITY);

        assertEquals("templates/weblog/page.vm", t.getId());
        assertEquals("templates/weblog/page.vm", t.getName());
        assertEquals("templates/weblog/page.vm", t.getDescription());
        assertEquals("templates/weblog/page.vm", t.getLink());
        assertEquals(TemplateLanguage.VELOCITY, t.getTemplateLanguage());
        assertEquals(RenditionType.STANDARD, t.getType());
        assertFalse(t.isHidden());
        assertFalse(t.isNavbar());
        assertNull(t.getOutputContentType());
        assertNull(t.getAction());
        assertNull(t.getContents());
        assertNull(t.getTemplateRendition(RenditionType.STANDARD), "it renders from its id, not from stored text");
    }

    @Test
    void aStaticTemplateKeepsWhatItIsGiven() {
        StaticThemeTemplate t = new StaticThemeTemplate("x", TemplateLanguage.VELOCITY);
        Date when = new Date(1234);

        t.setId("id2");
        t.setName("name2");
        t.setDescription("desc2");
        t.setLink("link2");
        t.setAction(ComponentType.CUSTOM);
        t.setOutputContentType("text/plain");
        t.setContents("body");
        t.setHidden(true);
        t.setNavbar(true);
        t.setLastModified(when);
        t.setType(RenditionType.STANDARD);
        t.setTemplateLanguage(TemplateLanguage.VELOCITY);

        assertEquals("id2", t.getId());
        assertEquals("name2", t.getName());
        assertEquals("desc2", t.getDescription());
        assertEquals("link2", t.getLink());
        assertEquals(ComponentType.CUSTOM, t.getAction());
        assertEquals("text/plain", t.getOutputContentType());
        assertEquals("body", t.getContents());
        assertTrue(t.isHidden());
        assertTrue(t.isNavbar());
        assertEquals(when, t.getLastModified());
    }

    @Test
    void aSharedTemplateCarriesItsConstructorFieldsAndRenditionsByType() throws Exception {
        Date when = new Date(5678);
        SharedThemeTemplate t = new SharedThemeTemplate("id1", ComponentType.WEBLOG, "Weblog", "the page",
                "contents", "link1", when, true, true);

        assertEquals("id1", t.getId());
        assertEquals(ComponentType.WEBLOG, t.getAction());
        assertEquals("Weblog", t.getName());
        assertEquals("the page", t.getDescription());
        assertEquals("contents", t.getContents());
        assertEquals("link1", t.getLink());
        assertEquals(when, t.getLastModified());
        assertTrue(t.isHidden());
        assertTrue(t.isNavbar());
        assertNull(t.getTemplateRendition(RenditionType.STANDARD), "none added yet");

        TemplateRendition rendition = mock(TemplateRendition.class);
        when(rendition.getType()).thenReturn(RenditionType.STANDARD);
        t.addTemplateRendition(rendition);
        assertSame(rendition, t.getTemplateRendition(RenditionType.STANDARD));
        assertTrue(t.toString().startsWith("id1,Weblog,the page,link1,"), t.toString());
    }

    @Test
    void aSharedTemplateCanBeFilledInAfterConstruction() {
        SharedThemeTemplate t = new SharedThemeTemplate();

        t.setId("i");
        t.setName("n");
        t.setDescription("d");
        t.setContents("c");
        t.setLink("l");
        t.setLastModified(new Date(1));
        t.setHidden(true);
        t.setNavbar(true);
        t.setOutputContentType("text/css");
        t.setAction(ComponentType.CUSTOM);
        t.setType("standard");

        assertEquals("i", t.getId());
        assertEquals("n", t.getName());
        assertEquals("d", t.getDescription());
        assertEquals("c", t.getContents());
        assertEquals("l", t.getLink());
        assertEquals(new Date(1), t.getLastModified());
        assertTrue(t.isHidden());
        assertTrue(t.isNavbar());
        assertEquals("text/css", t.getOutputContentType());
        assertEquals(ComponentType.CUSTOM, t.getAction());
        assertEquals("standard", t.getType());
    }
}
