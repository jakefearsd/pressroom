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
package com.wikantik.pressroom.ui.rendering.velocity;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;

import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.business.WeblogManager;
import com.wikantik.pressroom.business.Weblogger;
import com.wikantik.pressroom.pojos.CustomTemplateRendition;
import com.wikantik.pressroom.pojos.TemplateRendition.RenditionType;
import com.wikantik.pressroom.pojos.WeblogTemplate;
import org.apache.velocity.exception.ResourceNotFoundException;
import org.apache.velocity.runtime.RuntimeServices;
import org.apache.velocity.util.ExtProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The Velocity loader that serves a weblog's own customised templates out of
 * the database.
 *
 * <p>It sits underneath every page rendered from a custom theme, and it had no
 * direct test at all: its behaviour was exercised only incidentally, through
 * whole-page renders that use the filesystem loader instead. The parts that
 * matter are its own small grammar -- {@code name|rendition} -- and its
 * failure modes, because a loader that throws the wrong exception type turns a
 * missing template into a 500 rather than Velocity's own not-found handling.
 */
class PressroomResourceLoaderTest {

    private Weblogger weblogger;
    private PressroomResourceLoader loader;

    /**
     * The loader is instantiated by Velocity, so it takes its facade from the
     * engine's application attributes (what {@code PressroomVelocity.initialize}
     * sets), read through {@code RuntimeServices} in {@code init}. Nothing here
     * is installed into any static: the facade the test stubs is the only one
     * the loader can possibly reach.
     */
    @BeforeEach
    void setUp() {
        weblogger = mock(Weblogger.class);
        when(weblogger.getWeblogManager()).thenReturn(mock(WeblogManager.class));
        RuntimeServices rsvc = mock(RuntimeServices.class);
        when(rsvc.getLog(anyString())).thenReturn(LoggerFactory.getLogger("test"));
        when(rsvc.getApplicationAttribute(PressroomVelocity.WEBLOGGER_ATTRIBUTE)).thenReturn(weblogger);
        loader = new PressroomResourceLoader();
        loader.commonInit(rsvc, new ExtProperties());
        loader.init(new ExtProperties());
    }

    /** An engine not built by {@code PressroomVelocity.initialize} carries no facade; fail at init, not at first render. */
    @Test
    void anEngineWithoutTheFacadeIsRefusedAtInit() {
        RuntimeServices bare = mock(RuntimeServices.class);
        when(bare.getLog(anyString())).thenReturn(LoggerFactory.getLogger("test"));
        PressroomResourceLoader fresh = new PressroomResourceLoader();
        fresh.commonInit(bare, new ExtProperties());
        IllegalStateException thrown = assertThrows(IllegalStateException.class, () -> fresh.init(new ExtProperties()));
        assertTrue(thrown.getMessage().contains(PressroomVelocity.WEBLOGGER_ATTRIBUTE), thrown.getMessage());
    }

    @Test
    void aTemplateIsReadFromItsStandardRendition() throws Exception {
        givenTemplate("_day", RenditionType.STANDARD, "<p>day template</p>");

        assertEquals("<p>day template</p>", read(loader.getResourceReader("_day", "UTF-8")));
    }

    /**
     * Theme template names carry the rendition after a pipe, which is the only
     * piece of syntax this class owns. STANDARD is the sole rendition type
     * left (the mobile one was removed), so what this really pins is that the
     * suffix is stripped from the name before the lookup rather than being
     * looked up as part of it.
     */
    @Test
    void theRenditionSuffixIsStrippedFromTheTemplateName() throws Exception {
        givenTemplate("weblog", RenditionType.STANDARD, "standard body");

        assertEquals("standard body", read(loader.getResourceReader("weblog|standard", "UTF-8")));
        assertEquals("standard body", read(loader.getResourceReader("weblog|STANDARD", "UTF-8")),
                "the suffix is matched case-insensitively");
        assertEquals("standard body", read(loader.getResourceReader("weblog", "UTF-8")),
                "no pipe means the standard rendition");
    }

    /**
     * A template row that exists but has no rendition of the requested type is
     * empty, not broken: Velocity renders nothing rather than the loader
     * throwing on a null.
     */
    @Test
    void aMissingRenditionReadsAsEmptyRatherThanFailing() throws Exception {
        WeblogTemplate template = mock(WeblogTemplate.class);
        when(template.getTemplateRendition(RenditionType.STANDARD)).thenReturn(null);
        when(weblogger.getWeblogManager().getTemplate("weblog")).thenReturn(template);

        assertEquals("", read(loader.getResourceReader("weblog", "UTF-8")));
    }

    @Test
    void anEmptyNameIsRefusedBeforeAnyLookup() {
        assertThrows(ResourceNotFoundException.class,
                () -> loader.getResourceReader(null, "UTF-8"));
        assertThrows(ResourceNotFoundException.class,
                () -> loader.getResourceReader("", "UTF-8"));
    }

    @Test
    void anUnknownTemplateIsNotFoundRatherThanNull() throws Exception {
        when(weblogger.getWeblogManager().getTemplate("no-such-template")).thenReturn(null);

        ResourceNotFoundException thrown = assertThrows(ResourceNotFoundException.class,
                () -> loader.getResourceReader("no-such-template", "UTF-8"));
        assertTrue(thrown.getMessage().contains("no-such-template"),
                "the message must name the template, or a theme bug is untraceable: "
                        + thrown.getMessage());
    }

    /**
     * A database failure has to arrive as Velocity's own not-found exception.
     * Letting a WebloggerException escape a resource loader takes down the
     * whole render with a stack trace instead of a missing-template error.
     */
    @Test
    void aDatabaseFailureBecomesANotFoundRatherThanEscaping() throws Exception {
        when(weblogger.getWeblogManager().getTemplate("weblog"))
                .thenThrow(new WebloggerException("database down"));

        ResourceNotFoundException thrown = assertThrows(ResourceNotFoundException.class,
                () -> loader.getResourceReader("weblog", "UTF-8"));
        assertTrue(thrown.getMessage().contains("weblog"), thrown.getMessage());
    }

    /**
     * Database templates cannot tell Velocity they changed, so the loader
     * declares them immutable. Reporting otherwise would have Velocity
     * re-reading them on a timer for a modification time it can never learn.
     */
    @Test
    void databaseTemplatesNeverReportThemselvesModified() {
        assertFalse(loader.isSourceModified(null));
        assertEquals(0, loader.getLastModified(null));
    }

    // ------------------------------------------------------------- fixtures

    private WeblogTemplate givenTemplate(String name, RenditionType type, String body)
            throws WebloggerException {
        WeblogTemplate template = mock(WeblogTemplate.class);
        givenRendition(template, type, body);
        when(weblogger.getWeblogManager().getTemplate(name)).thenReturn(template);
        return template;
    }

    private void givenRendition(WeblogTemplate template, RenditionType type, String body)
            throws WebloggerException {
        CustomTemplateRendition rendition = mock(CustomTemplateRendition.class);
        when(rendition.getTemplate()).thenReturn(body);
        when(template.getTemplateRendition(type)).thenReturn(rendition);
    }

    private String read(Reader reader) throws IOException {
        try (BufferedReader buffered = new BufferedReader(reader)) {
            StringBuilder out = new StringBuilder();
            int c;
            while ((c = buffered.read()) >= 0) {
                out.append((char) c);
            }
            return out.toString();
        }
    }
}
