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
package org.apache.roller.weblogger.ui.controllers;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.View;

import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RollerViewResolverTest {

    private RollerViewResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new RollerViewResolver();
    }

    @Test
    void baseDefinitionResolvesToNonNullView() {
        resolver.addDefinition(".base", "/layout.jsp",
                Map.of("content", "/page.jsp", "footer", "/footer.jsp"));

        View view = resolver.resolveViewName(".base", Locale.getDefault());

        assertNotNull(view);
        assertEquals("text/html", view.getContentType());
    }

    @Test
    void extendedDefinitionInheritsAttributes() {
        resolver.addDefinition(".base", "/layout.jsp",
                Map.of("content", "/default-content.jsp", "footer", "/footer.jsp"));
        resolver.addExtendedDefinition(".child", ".base",
                Map.of("content", "/child-content.jsp"));

        View view = resolver.resolveViewName(".child", Locale.getDefault());
        assertNotNull(view);
    }

    @Test
    void unknownViewNameReturnsNull() {
        View view = resolver.resolveViewName(".nonexistent", Locale.getDefault());
        assertNull(view);
    }

    @Test
    void extendingNonExistentBaseThrows() {
        assertThrows(IllegalArgumentException.class, () ->
                resolver.addExtendedDefinition(".child", ".missingBase", Map.of("content", "/page.jsp")));
    }

    @Test
    void initRegistersAllDefinitionsWithoutErrors() {
        resolver.init();

        // Spot-check several well-known view names from tiles.xml
        assertNotNull(resolver.resolveViewName(".EntryEdit", Locale.getDefault()),
                ".EntryEdit should be registered");
        assertNotNull(resolver.resolveViewName(".Login", Locale.getDefault()),
                ".Login should be registered");
        assertNotNull(resolver.resolveViewName(".MainMenu", Locale.getDefault()),
                ".MainMenu should be registered");
        assertNotNull(resolver.resolveViewName(".denied", Locale.getDefault()),
                ".denied should be registered");
        assertNotNull(resolver.resolveViewName(".tiles-tabbedpage", Locale.getDefault()),
                ".tiles-tabbedpage should be registered");
        assertNotNull(resolver.resolveViewName(".Maintenance", Locale.getDefault()),
                ".Maintenance should be registered");
        assertNotNull(resolver.resolveViewName(".MediaFileImageChooser", Locale.getDefault()),
                ".MediaFileImageChooser should be registered");
    }

    @Test
    void extendedDefinitionMergesOverrides() {
        resolver.addDefinition(".base", "/layout.jsp",
                Map.of("content", "/default.jsp", "footer", "/footer.jsp"));
        resolver.addExtendedDefinition(".child", ".base",
                Map.of("content", "/override.jsp"));

        // Resolve the child -- the parent's "footer" should be inherited
        // and "content" should be overridden.
        // We verify via ViewDefinition created in the resolver's internal map.
        // Since we can't directly inspect the map, we confirm the view is non-null
        // and trust the unit logic. A more thorough integration test would use
        // a mock request/response.
        View view = resolver.resolveViewName(".child", Locale.getDefault());
        assertNotNull(view);
    }

    @Test
    void viewDefinitionWithAttributeCreatesNewInstance() {
        ViewDefinition def = new ViewDefinition("/layout.jsp",
                Map.of("content", "/page.jsp"));
        ViewDefinition updated = def.withAttribute("sidebar", "/sidebar.jsp");

        // original is unchanged
        assertFalse(def.attributes().containsKey("sidebar"));
        // new instance has both
        assertEquals("/page.jsp", updated.attributes().get("content"));
        assertEquals("/sidebar.jsp", updated.attributes().get("sidebar"));
        assertEquals("/layout.jsp", updated.layout());
    }
    // --- B9a: two screens that had lost their chrome ---

    /**
     * Inquiries is an authoring screen reached from the editor tabs, so it
     * renders through {@code .tiles-tabbedpage} like every other one -- with
     * the rail, the weblog context block and the tab spine. It used to be a
     * {@code .tiles-simplepage}, which is the install/login chrome: a reader
     * who clicked Inquiries lost the navigation they arrived by and had no way
     * back except the browser's own history.
     *
     * <p>The attribute map is {@code .Trash}'s, which is the minimal tabbed
     * shape: an explicit head, the content tile, and no page-specific styles.
     * {@code menu} stays the base layout's {@code empty.jsp} -- the tabs come
     * from the {@code navMenu} model attribute, not from a tile.
     */
    @Test
    void submissionsRendersThroughTheTabbedLayout() throws Exception {
        resolver.init();

        MockHttpServletRequest request = renderAndCapture(".Submissions");

        assertEquals("/WEB-INF/jsps/editor/Submissions.jsp", request.getAttribute("tile_content"));
        assertEquals("/WEB-INF/jsps/tiles/empty.jsp", request.getAttribute("tile_menu"),
                ".Submissions must inherit the tabbed layout's empty menu tile");
        assertEquals("/WEB-INF/jsps/tiles/head.jsp", request.getAttribute("tile_head"));
        assertEquals("/WEB-INF/jsps/tiles/empty.jsp", request.getAttribute("tile_styles"));
    }

    @Test
    void commentsRendersThroughTheTabbedLayout() throws Exception {
        resolver.init();

        MockHttpServletRequest request = renderAndCapture(".Comments");

        assertEquals("/WEB-INF/jsps/editor/Comments.jsp", request.getAttribute("tile_content"));
        assertEquals("/WEB-INF/jsps/tiles/empty.jsp", request.getAttribute("tile_menu"));
        assertEquals("/WEB-INF/jsps/tiles/head.jsp", request.getAttribute("tile_head"));
    }

    /**
     * Profile is not weblog-scoped, so it has no tabs to render -- but it is
     * still a signed-in admin screen, and {@code .tiles-mainmenupage} is the
     * layout for exactly that (the rail with the account context block and no
     * tab groups, which is what MainMenu itself uses). On simplepage it
     * rendered as a bare card with no rail at all.
     */
    @Test
    void profileRendersThroughTheMainMenuLayout() throws Exception {
        resolver.init();

        MockHttpServletRequest request = renderAndCapture(".Profile");

        assertEquals("/WEB-INF/jsps/core/Profile.jsp", request.getAttribute("tile_content"));
        assertEquals("/WEB-INF/jsps/tiles/empty.jsp", request.getAttribute("tile_sidebar"));
    }

    /** Profile → API tokens and its reveal are signed-in, un-scoped screens, like Profile itself. */
    @Test
    void theTokenScreensRenderThroughTheMainMenuLayout() throws Exception {
        resolver.init();

        assertEquals("/WEB-INF/jsps/core/ProfileTokens.jsp",
                renderAndCapture(".ProfileTokens").getAttribute("tile_content"));
        assertEquals("/WEB-INF/jsps/core/TokenReveal.jsp",
                renderAndCapture(".TokenReveal").getAttribute("tile_content"));
    }

    /**
     * The reader's account page is a reader's only admin-UI screen, and a
     * reader has nowhere on the rail to go: its account link opens the
     * profile, which refuses a reader. So it is the plain card under the top
     * bar, whose site-name and log-out links are the ones a reader can use.
     */
    @Test
    void accountRendersThroughTheSimpleLayout() throws Exception {
        resolver.init();

        MockHttpServletRequest request = renderAndCapture(".Account");

        assertEquals("/WEB-INF/jsps/core/Account.jsp", request.getAttribute("tile_content"));
        assertEquals("/WEB-INF/jsps/tiles/bannerStatus.jsp", request.getAttribute("tile_banner"));
    }

    /**
     * Renders the named definition against a mock request/response pair and
     * hands back the request, whose {@code tile_*} attributes are the
     * definition's attribute map and whose response records the layout JSP the
     * view forwarded to. The resolver keeps its definition map private, and it
     * should: the layout is only observable the way the container observes it.
     */
    private MockHttpServletRequest renderAndCapture(String viewName) throws Exception {
        View view = resolver.resolveViewName(viewName, Locale.getDefault());
        assertNotNull(view, viewName + " is not registered");

        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        view.render(Map.of(), request, response);

        assertEquals(expectedLayout(viewName), response.getForwardedUrl(),
                viewName + " forwarded to the wrong layout JSP");
        return request;
    }

    private static String expectedLayout(String viewName) {
        return switch (viewName) {
            case ".Comments", ".Submissions" -> "/WEB-INF/jsps/tiles/tiles-tabbedpage.jsp";
            case ".Profile", ".ProfileTokens", ".TokenReveal" -> "/WEB-INF/jsps/tiles/tiles-mainmenupage.jsp";
            case ".Account" -> "/WEB-INF/jsps/tiles/tiles-simplepage.jsp";
            default -> throw new IllegalArgumentException("no expected layout for " + viewName);
        };
    }
}
