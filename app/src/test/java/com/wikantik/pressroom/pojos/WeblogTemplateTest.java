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
import com.wikantik.pressroom.pojos.ThemeTemplate.ComponentType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the two derived flags on {@link WeblogTemplate} and its rendition
 * collection.
 *
 * <p>{@code isRequired} is what stops the template editor deleting a template
 * the renderer cannot do without -- delete "Weblog" and the blog stops
 * rendering entirely. {@code isCustom} decides whether a template is offered
 * as a standalone page. Both are computed rather than stored, so a change to
 * either silently reclassifies every existing template.
 */
class WeblogTemplateTest {

    private Weblog weblog;

    @BeforeEach
    void setUp() {
        weblog = new Weblog();
        weblog.setHandle("testblog");
    }

    private WeblogTemplate template(String name, String link) {
        WeblogTemplate template = new WeblogTemplate();
        template.setName(name);
        template.setLink(link);
        template.setWeblog(weblog);
        return template;
    }

    @Test
    void theTemplatesTheRendererCannotDoWithoutAreRequired() {
        assertTrue(template("Weblog", "weblog").isRequired(),
                "'Weblog' is the main page template; if it can be deleted the blog stops "
                        + "rendering at all");
        assertTrue(template("_day", "day").isRequired(),
                "'_day' is included by the main template for every day of entries");
    }

    @Test
    void aTemplateLinkedAsWeblogIsRequiredWhateverItIsCalled() {
        // Blogs migrated from much older Pressroom versions have a renamed main
        // template that is still reached through the "Weblog" link.
        assertTrue(template("MyCustomHomePage", "Weblog").isRequired(),
                "A template serving the 'Weblog' link is the main page under another "
                        + "name and must be just as undeletable");
    }

    @Test
    void anOrdinaryTemplateIsNotRequired() {
        assertFalse(template("About", "about").isRequired(),
                "An author's own page must stay deletable");
        assertFalse(template("weblog", "about").isRequired(),
                "The required-name check is case sensitive: only the exact names "
                        + "'Weblog' and '_day' are structural");
    }

    @Test
    void onlyNonRequiredCustomActionTemplatesCountAsCustom() {
        WeblogTemplate about = template("About", "about");
        about.setAction(ComponentType.CUSTOM);
        assertTrue(about.isCustom(),
                "A CUSTOM-action template that is not structural is an author's own page");

        WeblogTemplate permalink = template("Permalink", "permalink");
        permalink.setAction(ComponentType.PERMALINK);
        assertFalse(permalink.isCustom(),
                "A template bound to a built-in action is not a custom page");

        WeblogTemplate main = template("Weblog", "weblog");
        main.setAction(ComponentType.CUSTOM);
        assertFalse(main.isCustom(),
                "The main template must never be offered as a deletable custom page even "
                        + "if its action says CUSTOM -- that combination exists in blogs "
                        + "migrated from older versions");

        WeblogTemplate unset = template("About", "about");
        assertFalse(unset.isCustom(),
                "A template with no action at all is not custom; a null action must not "
                        + "throw here either");
    }

    @Test
    void theHiddenAndNavbarFlagsAreIndependent() {
        // Both feed the theme's navigation: hidden removes the page entirely,
        // navbar decides whether it gets a link. An accessor answering for the
        // other flag would either publish a hidden page or hide a linked one.
        WeblogTemplate template = template("About", "about");
        template.setHidden(true);
        template.setNavbar(false);
        assertTrue(template.isHidden());
        assertFalse(template.isNavbar());

        template.setHidden(false);
        template.setNavbar(true);
        assertFalse(template.isHidden());
        assertTrue(template.isNavbar());
    }

    @Test
    void renditionsAreLookedUpByType() throws Exception {
        WeblogTemplate empty = template("About", "about");
        assertNull(empty.getTemplateRendition(RenditionType.STANDARD),
                "A template with no rendition stored must read as null so the renderer "
                        + "can fall back to the shared theme's copy");

        WeblogTemplate template = template("About", "about");
        CustomTemplateRendition standard = new CustomTemplateRendition(template, RenditionType.STANDARD);

        assertSame(standard, template.getTemplateRendition(RenditionType.STANDARD),
                "The renderer asks for the rendition matching the request type");
    }

    @Test
    void aTemplateCannotHoldTwoRenditionsOfTheSameType() {
        WeblogTemplate template = template("About", "about");
        new CustomTemplateRendition(template, RenditionType.STANDARD);

        IllegalArgumentException clash = assertThrows(IllegalArgumentException.class,
                () -> new CustomTemplateRendition(template, RenditionType.STANDARD),
                "Two renditions of the same type make the lookup order-dependent, so the "
                        + "second must be rejected at the point it is added");
        assertTrue(clash.getMessage().contains("About"),
                "The error must name the template so the offending template is findable: "
                        + clash.getMessage());

        assertEquals(1, template.getTemplateRenditions().size(),
                "The rejected rendition must not have been added");
    }

    @Test
    void renditionPresenceIsCheckedByTypeNotIdentity() {
        WeblogTemplate template = template("About", "about");

        CustomTemplateRendition candidate = new CustomTemplateRendition();
        candidate.setType(RenditionType.STANDARD);
        assertFalse(template.hasTemplateRendition(candidate),
                "A template with no renditions collides with nothing");

        new CustomTemplateRendition(template, RenditionType.STANDARD);
        assertTrue(template.hasTemplateRendition(candidate),
                "A different object of the same rendition type still collides -- the "
                        + "check is on the type, not on object identity, because the "
                        + "candidate has not been persisted yet");
    }
}
