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
package com.wikantik.pressroom.ui.controllers.editor;

import com.wikantik.pressroom.WebloggerException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ui.Model;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

/**
 * Tests for {@link WeblogRemoveController}.
 *
 * <p>Removing a weblog is irreversible, so the two things worth pinning are
 * that a successful removal actually reaches the manager and is committed
 * (not just a redirect with nothing behind it), and that a failure keeps the
 * admin on the confirmation page with an explanation rather than bouncing
 * them away as if it worked.
 */
class WeblogRemoveControllerTest extends EditorControllerTestSupport {

    private WeblogRemoveController controller;
    private Model model;
    private RedirectAttributes redirectAttributes;

    @BeforeEach
    void setUp() {
        controller = prepare(new WeblogRemoveController());
        model = newModel();
        redirectAttributes = newRedirectAttributes();
    }

    @Test
    void executeShowsTheConfirmationPage() {
        String view = controller.execute(request, model);

        assertEquals(".WeblogRemoveConfirm", view);
    }

    @Test
    void removingTheWeblogCommitsAndRedirectsToTheMenu() throws Exception {
        String view = controller.remove(request, model, redirectAttributes);

        assertEquals("redirect:/pressroom-ui/menu.rol", view);
        verify(weblogger.getWeblogManager()).removeWeblog(weblog);
        assertTrue(weblogger.flushCount() > 0, "A weblog removal must be committed");
        assertEquals(java.util.List.of("websiteRemove.success"), flashMessages(redirectAttributes));
    }

    @Test
    void theRemovalNoticeEscapesTheWeblogTitleItNames() throws Exception {
        // Weblog.getName() stores raw author input -- WeblogWrapper escapes it
        // on the way to a theme, which is exactly why this call site looks safe
        // and is not. messages.jsp renders a flash message unescaped.
        //
        // The fixture value is an UNTERMINATED tag on purpose.
        // Weblog.setName runs Utilities.removeHTML, which drops text between
        // '<' and '>' -- but its no-closing-bracket branch appends the rest of
        // the string verbatim, '<' included. So the stripper is not the
        // boundary it looks like, and this is the shape that gets past it.
        registerMessage("websiteRemove.success", "removed:{0}");
        weblog.setName("<img src=x onerror=alert(1)");

        controller.remove(request, model, redirectAttributes);

        assertEquals(java.util.List.of("removed:&lt;img src=x onerror=alert(1)"),
                flashMessages(redirectAttributes),
                "Expected the weblog title HTML-escaped");
    }

    @Test
    void aFailedRemovalStaysOnTheConfirmationPageWithAnError() throws Exception {
        doThrow(new WebloggerException("in use"))
                .when(weblogger.getWeblogManager()).removeWeblog(any());

        String view = controller.remove(request, model, redirectAttributes);

        assertEquals(".WeblogRemoveConfirm", view);
        assertTrue(errors(model).contains("websiteRemove.error"),
                "Expected a removal error, got: " + errors(model));
        assertTrue(flashMessages(redirectAttributes).isEmpty(),
                "A failed removal must not also report success");
    }
}
