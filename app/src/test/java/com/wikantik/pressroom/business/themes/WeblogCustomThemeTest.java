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
package com.wikantik.pressroom.business.themes;

import java.util.Date;

import com.wikantik.pressroom.business.WeblogManager;
import com.wikantik.pressroom.pojos.Theme;
import com.wikantik.pressroom.pojos.ThemeTemplate.ComponentType;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.pojos.WeblogTemplate;
import com.wikantik.pressroom.pojos.WeblogTheme;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * A weblog on its own custom theme: every template comes from the weblog's
 * stored templates, and the theme has no files of its own.
 *
 * <p>Characterisation tests, written against the existing behaviour and
 * expected to pass immediately. The weblog's template store is a mocked
 * {@link WeblogManager}.
 */
class WeblogCustomThemeTest {

    private final Weblog weblog = new Weblog();
    private final WeblogManager weblogManager = mock(WeblogManager.class);
    private WeblogCustomTheme theme;

    @BeforeEach
    void setUp() {
        weblog.setHandle("customblog");
        theme = new WeblogCustomTheme(weblog, weblogManager);
    }

    /**
     * A custom theme has no shared identity: it reports the CUSTOM marker
     * everywhere the picker and {@code ThemeEditController} compare against
     * {@link WeblogTheme#CUSTOM}.
     */
    @Test
    void aCustomThemeIdentifiesItselfAsCustom() {
        assertEquals(WeblogTheme.CUSTOM, theme.getId());
        assertEquals(WeblogTheme.CUSTOM, theme.getName());
        assertEquals(WeblogTheme.CUSTOM, theme.getDescription());
        assertEquals(WeblogTheme.CUSTOM, theme.getType());
        assertEquals("N/A", theme.getAuthor());
        assertTrue(theme.isEnabled(), "a weblog's own theme can never be switched off");
    }

    /**
     * Its templates live in the database and change with the weblog, so its
     * modification time is the weblog's -- the timestamp every template save
     * bumps.
     */
    @Test
    void itsLastModifiedIsTheWeblogs() {
        Date saved = new Date(1_700_000_000_000L);
        weblog.setLastModified(saved);

        assertEquals(saved, theme.getLastModified());
    }

    @Test
    void itSortsAgainstOtherThemesByName() {
        Theme alpha = mock(Theme.class);
        when(alpha.getName()).thenReturn("Alpha");
        Theme zulu = mock(Theme.class);
        when(zulu.getName()).thenReturn("zulu");

        assertTrue(theme.compareTo(alpha) > 0, "\"custom\" sorts after \"Alpha\"");
        assertTrue(theme.compareTo(zulu) < 0, "\"custom\" sorts before \"zulu\"");
    }

    @Test
    void theStylesheetIsTheWeblogsStylesheetActionTemplate() throws Exception {
        WeblogTemplate stylesheet = new WeblogTemplate();
        when(weblogManager.getTemplateByAction(weblog, ComponentType.STYLESHEET))
                .thenReturn(stylesheet);

        assertSame(stylesheet, theme.getStylesheet());
    }

    @Test
    void theDefaultTemplateIsTheWeblogsWeblogActionTemplate() throws Exception {
        WeblogTemplate front = new WeblogTemplate();
        when(weblogManager.getTemplateByAction(weblog, ComponentType.WEBLOG)).thenReturn(front);

        assertSame(front, theme.getDefaultTemplate());
    }

    @Test
    void templatesAreLookedUpByActionInTheWeblogsStore() throws Exception {
        WeblogTemplate permalink = new WeblogTemplate();
        when(weblogManager.getTemplateByAction(weblog, ComponentType.PERMALINK))
                .thenReturn(permalink);

        assertSame(permalink, theme.getTemplateByAction(ComponentType.PERMALINK));
        assertNull(theme.getTemplateByAction(ComponentType.TAGSINDEX),
                "an action the weblog has no template for is not found");
    }

    @Test
    void templatesAreLookedUpByNameInTheWeblogsStore() throws Exception {
        WeblogTemplate page = new WeblogTemplate();
        when(weblogManager.getTemplateByName(weblog, "_page")).thenReturn(page);

        assertSame(page, theme.getTemplateByName("_page"));
        assertNull(theme.getTemplateByName("nowhere"));
    }

    @Test
    void nullLookupsAreNotFoundAndQueryNothing() throws Exception {
        assertNull(theme.getTemplateByAction(null));
        assertNull(theme.getTemplateByName(null));
        assertNull(theme.getTemplateByLink(null));

        verifyNoInteractions(weblogManager);
    }

    /**
     * A custom theme has no static files: its images live in the weblog's
     * media library and are served from there, never as theme resources.
     */
    @Test
    void aCustomThemeHasNoResources() {
        assertNull(theme.getResource("img/logo.png"));
        verifyNoInteractions(weblogManager);
    }
}
