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
package com.wikantik.pressroom.pojos;

import com.wikantik.pressroom.pojos.TemplateRendition.RenditionType;
import com.wikantik.pressroom.pojos.TemplateRendition.TemplateLanguage;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers {@link StaticTemplate} and {@link StaticThemeTemplate}, the wrappers
 * that let a template file shipped inside the WAR be handed to the renderer
 * alongside the database-backed ones.
 *
 * <p>Both are constructed from a single id, which they fan out into the name,
 * description and link the renderer then looks them up by. If that fan-out
 * stops happening the template is still constructed successfully and simply
 * never resolves -- the page renders blank rather than failing.
 */
class StaticTemplateTest {

    @Test
    void aStaticTemplateIsIdentifiedByItsIdEverywhere() {
        StaticTemplate template = new StaticTemplate("weblog", TemplateLanguage.VELOCITY);

        assertEquals("weblog", template.getId());
        assertEquals("weblog", template.getName(),
                "Lookups by name must find the template built from that id");
        assertEquals("weblog", template.getDescription());
        assertNotNull(template.getLastModified(),
                "The renderer caches on last-modified; a null one would defeat the cache "
                        + "or blow up in it");
    }

    @Test
    void aStaticTemplateCarriesTheOneRenditionItWasBuiltWith() throws Exception {
        StaticTemplate template = new StaticTemplate("weblog", TemplateLanguage.VELOCITY);

        TemplateRendition rendition = template.getTemplateRendition(RenditionType.STANDARD);

        assertNotNull(rendition,
                "A static template has exactly one rendition; returning null would leave "
                        + "the renderer with nothing to execute");
        assertEquals(TemplateLanguage.VELOCITY, rendition.getTemplateLanguage(),
                "The language passed to the constructor decides which engine renders it");
        assertEquals(RenditionType.STANDARD, rendition.getType());
    }

    @Test
    void aStaticThemeTemplateIsIdentifiedByItsIdEverywhere() {
        StaticThemeTemplate template =
                new StaticThemeTemplate("_day", TemplateLanguage.VELOCITY);

        assertEquals("_day", template.getId());
        assertEquals("_day", template.getName());
        assertEquals("_day", template.getDescription());
        assertEquals("_day", template.getLink(),
                "The link is what a URL resolves to a template by, so it has to be "
                        + "filled in as well as the name");
        assertEquals(TemplateLanguage.VELOCITY, template.getTemplateLanguage());
        assertEquals(RenditionType.STANDARD, template.getType());
        assertNotNull(template.getLastModified());
    }

    @Test
    void aStaticThemeTemplateIsVisibleAndOutsideTheNavbarByDefault() {
        StaticThemeTemplate template =
                new StaticThemeTemplate("_day", TemplateLanguage.VELOCITY);

        assertFalse(template.isHidden(),
                "A template shipped with the application is meant to be reachable; "
                        + "defaulting to hidden would make it unresolvable");
        assertFalse(template.isNavbar(),
                "but it is not a page the theme should link to from its navigation");

        // Flip both so neither accessor can pass by being hard-wired to one
        // answer or by answering for the other flag.
        template.setHidden(true);
        template.setNavbar(true);
        assertTrue(template.isHidden());
        assertTrue(template.isNavbar());
    }

    @Test
    void aStaticThemeTemplateHasNoStoredRendition() throws Exception {
        // Its content comes from a file on the classpath, not from the
        // rendition table, so the lookup must say so rather than pretend.
        StaticThemeTemplate template =
                new StaticThemeTemplate("_day", TemplateLanguage.VELOCITY);

        assertNull(template.getTemplateRendition(RenditionType.STANDARD),
                "A static theme template has no database rendition; the renderer reads "
                        + "its contents instead");
    }
}
