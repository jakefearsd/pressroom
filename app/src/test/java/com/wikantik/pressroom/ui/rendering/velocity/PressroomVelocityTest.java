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

import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;

import jakarta.servlet.ServletContext;

import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.business.Weblogger;
import com.wikantik.pressroom.business.themes.SharedTheme;
import com.wikantik.pressroom.business.themes.ThemeManager;
import com.wikantik.pressroom.pojos.TemplateRendition;
import com.wikantik.pressroom.pojos.TemplateRendition.RenditionType;
import com.wikantik.pressroom.pojos.ThemeTemplate;
import com.wikantik.pressroom.ui.core.PressroomContext;
import com.wikantik.pressroom.ui.rendering.servlets.RenderingTestSupport;
import org.apache.velocity.VelocityContext;
import org.apache.velocity.app.VelocityEngine;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockServletContext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link PressroomVelocity} is initialised explicitly, at bootstrap, with the
 * {@link Weblogger} it hands to the two Roller resource loaders through the
 * engine's application attributes (the design spec's Decision 4). These pin
 * that contract without touching the JVM-wide engine where they can avoid it:
 * the whole app module shares one forked JVM and the engine cannot be
 * re-initialised, so anything that would install a mock facade into the real
 * engine would poison every rendering test that runs after it.
 */
class PressroomVelocityTest {

    /**
     * The end-to-end contract: an engine built by {@code PressroomVelocity} lets
     * {@code ThemeResourceLoader} resolve a theme template through <em>the
     * facade it was built with</em> -- nothing static, nothing installed.
     */
    @Test
    void anEngineBuiltHereHandsItsFacadeToTheThemeLoader() throws Exception {
        Weblogger weblogger = mock(Weblogger.class);
        ThemeManager themeManager = mock(ThemeManager.class);
        SharedTheme theme = mock(SharedTheme.class);
        ThemeTemplate template = mock(ThemeTemplate.class);
        TemplateRendition rendition = mock(TemplateRendition.class);
        when(weblogger.getThemeManager()).thenReturn(themeManager);
        when(themeManager.getTheme("probe")).thenReturn(theme);
        when(theme.getTemplateByName("weblog")).thenReturn(template);
        when(template.getTemplateRendition(RenditionType.STANDARD)).thenReturn(rendition);
        when(rendition.getTemplate()).thenReturn("hello from $who");

        VelocityEngine engine = PressroomVelocity.buildEngine(webappContext(), weblogger);

        assertSame(weblogger, engine.getApplicationAttribute(PressroomVelocity.WEBLOGGER_ATTRIBUTE));
        StringWriter out = new StringWriter();
        VelocityContext context = new VelocityContext();
        context.put("who", "the injected facade");
        engine.getTemplate("probe:weblog|standard").merge(context, out);
        assertEquals("hello from the injected facade", out.toString());
    }

    /** A missing velocity.properties is a checked failure and installs nothing. */
    @Test
    void aMissingConfigIsReportedRatherThanSwallowed() {
        MockServletContext empty = new MockServletContext();   // no /WEB-INF at all
        WebloggerException thrown = assertThrows(WebloggerException.class,
                () -> PressroomVelocity.buildEngine(empty, mock(Weblogger.class)));
        assertTrue(thrown.getMessage().contains(PressroomVelocity.VELOCITY_CONFIG), thrown.getMessage());
    }

    /**
     * Before {@code initialize}, a template lookup fails loudly rather than
     * NPE-ing inside Velocity. Order-dependent by nature -- the engine is
     * JVM-wide -- so it only runs when nothing in this JVM has initialised it
     * yet, and is skipped (not passed) otherwise.
     */
    @Test
    void templatesCannotBeLookedUpBeforeInitialisation() {
        assumeFalse(PressroomVelocity.isInitialized(), "engine already up in this JVM; contract covered elsewhere");
        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> PressroomVelocity.getTemplate("weblog"));
        assertTrue(thrown.getMessage().contains("not been initialised"), thrown.getMessage());
    }

    /**
     * The rendering test support -- like {@code PressroomLifecycle.start()} --
     * initialises the engine with the real facade; a later call with a
     * different facade is a no-op, which is what lets the suite share one
     * engine across hundreds of tests without any of them re-pointing it.
     */
    @Test
    void initialiseIsIdempotentAndKeepsTheFirstFacade() throws Exception {
        RenderingTestSupport.ensureRenderingRuntime();
        Object realFacade = PressroomVelocity.getEngine().getApplicationAttribute(PressroomVelocity.WEBLOGGER_ATTRIBUTE);
        Weblogger impostor = mock(Weblogger.class);

        PressroomVelocity.initialize(webappContext(), impostor);

        assertTrue(PressroomVelocity.isInitialized());
        assertSame(realFacade, PressroomVelocity.getEngine().getApplicationAttribute(PressroomVelocity.WEBLOGGER_ATTRIBUTE));
        assertNotSame(impostor, realFacade);
    }

    // ------------------------------------------------------------ fixtures

    /**
     * A ServletContext over src/main/webapp, held in PressroomContext too because
     * the (unchanged) WebappResourceLoader still reads it from there during
     * engine init -- the macro libraries in velocity.properties load through
     * it. Same root the rendering suite installs, so holding it is harmless
     * whichever runs first.
     */
    private static ServletContext webappContext() {
        Path candidate = Path.of("src", "main", "webapp");
        if (!Files.isDirectory(candidate)) {
            candidate = Path.of("app", "src", "main", "webapp");
        }
        MockServletContext context = new MockServletContext(candidate.toAbsolutePath().toUri().toString());
        if (PressroomContext.getServletContext() == null) {
            PressroomContext.hold(context);
        }
        return context;
    }
}
