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
package com.wikantik.pressroom.it;

import com.wikantik.pressroom.it.support.BrowserHealth;
import com.codeborne.selenide.SelenideElement;
import com.wikantik.pressroom.it.support.PressroomIT;
import org.junit.jupiter.api.Test;

import static com.codeborne.selenide.Condition.exist;
import static com.codeborne.selenide.Condition.value;
import static com.codeborne.selenide.Condition.visible;
import static com.codeborne.selenide.Selenide.$;
import static com.codeborne.selenide.Selenide.$$;
import static com.codeborne.selenide.Selectors.byLinkText;
import static com.codeborne.selenide.Selenide.confirm;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Weblog templates: adding one, editing its source, and removing it.
 *
 * <p>{@code templateEdit.rol} is another route the sweep skips for want of a
 * seeded id, so the theme-editing surface had no coverage at all -- and it is
 * the surface where a mistake writes arbitrary markup onto a public page.
 *
 * <p>Templates belong to a weblog and the controllers resolve client-supplied
 * ids, so the ownership checks matter here more than anywhere; those are
 * asserted as unit tests against {@code lookupTemplate}. What needs a browser
 * is the plumbing around them: that the add form reaches the list, that an edit
 * survives a round trip, and that the delete control does anything at all.
 */
class TemplateIT extends PressroomIT {

    private static final String TEMPLATES = "/roller-ui/authoring/templates.rol?weblog=" + WEBLOG_HANDLE;

    @Test
    void aTemplateCanBeAddedEditedAndRemoved() {
        String name = "ITTemplate" + nonce();
        String body = "## added by TemplateIT " + nonce();

        loginAsAdmin();
        addTemplate(name);
        assertTrue(templateNames().contains(name),
                "a template added through the sidebar form must appear in the list: "
                        + templateNames());

        editTemplateSource(name, body);
        openTemplate(name);
        $("textarea[name='bean.contentsStandard']").shouldHave(value(body));

        removeTemplate(name);
        assertFalse(templateNames().contains(name),
                "a removed template must be gone from the list: " + templateNames());
        logout();
    }

    /**
     * The description is editable and the name is not: a template's name is how
     * the theme and the rendering layer refer to it.
     */
    @Test
    void aTemplateDescriptionSurvivesTheRoundTrip() {
        String name = "ITTemplateDesc" + nonce();
        String description = "Described by TemplateIT " + nonce();

        loginAsAdmin();
        addTemplate(name);

        openTemplate(name);
        $("textarea[name='bean.description']").setValue(description);
        saveTemplate();

        openTemplate(name);
        $("textarea[name='bean.description']").shouldHave(value(description));

        removeTemplate(name);
        logout();
    }

    /**
     * The point of the whole page: a custom template renders as a public page.
     *
     * <p>Everything else here is bookkeeping -- this is the assertion that the
     * template a user edits is the markup a reader receives. It also covers the
     * removal reaching the public side: the rendered page is cached, and both
     * saveTemplate and removeTemplate bump the weblog's lastModified, which is
     * what expires that cache. A deleted template still being served would be
     * markup nobody can edit any more.
     *
     * <p>The seeded weblog runs a shared theme, so this exercises the
     * WeblogSharedTheme fallback that looks a custom template up on the weblog
     * when the theme itself has no such link.
     */
    @Test
    void aCustomTemplateIsServedAsAPublicPageAndStopsBeingWhenRemoved() {
        String name = "ITTemplatePage" + nonce();
        String marker = "IT template marker " + nonce();
        String pageUrl = baseUrl() + "/" + WEBLOG_HANDLE + "/page/" + name;

        loginAsAdmin();
        addTemplate(name);
        editTemplateSource(name, marker);

        // First fetch is after the save, so nothing stale can be cached.
        assertTrue(getAnonymously(pageUrl).contains(marker),
                "a custom template must be served at its own page URL");

        removeTemplate(name);
        assertFalse(getAnonymously(pageUrl).contains(marker),
                "a removed template must stop being served to readers");
        logout();
    }

    // ---------------------------------------------------------------- helpers

    /** Adds a CUSTOM template through the sidebar form. */
    private void addTemplate(String name) {
        openPath(TEMPLATES);
        $("#templateAdd input[name='newTmplName']").should(visible).setValue(name);
        $("#templateAdd select[name='newTmplAction']").selectOptionByValue("CUSTOM");
        $("#templateAdd button[type='submit']").click();

        // The handler re-renders the list in place, so the row IS the evidence
        // the template was created.
        linkFor(name).should(exist);
        BrowserHealth.current().settle();
    }

    private void openTemplate(String name) {
        openPath(TEMPLATES);
        linkFor(name).should(visible).click();
        $("textarea[name='bean.contentsStandard']").should(visible);
    }

    private void editTemplateSource(String name, String body) {
        openTemplate(name);
        $("textarea[name='bean.contentsStandard']").setValue(body);
        saveTemplate();
    }

    private void saveTemplate() {
        $("#template button[type='submit']").click();
        $("#messages").should(exist);
        BrowserHealth.current().settle();
    }

    /**
     * Removes a template through the list's trash control, which asks for
     * confirmation through a native dialog.
     *
     * <p>Targets the button by its {@code data-template-name} attribute, not
     * an onclick substring: the delete control's id/name ride in data-*
     * attributes now (apostrophe-in-onclick fix), so there is no onclick
     * text to match against any more.
     */
    private void removeTemplate(String name) {
        openPath(TEMPLATES);
        $("button.template-delete-btn[data-template-name='" + name + "']")
                .should(visible).click();
        confirm();

        $("table").should(exist);
        linkFor(name).shouldNot(exist);
        BrowserHealth.current().settle();
    }

    /**
     * The list's link to a named template. The href carries the id, not the
     * name, so this matches on the link text -- which is the name.
     */
    private SelenideElement linkFor(String name) {
        return $(byLinkText(name));
    }

    private String templateNames() {
        openPath(TEMPLATES);
        $("table").should(exist);
        return $$("a[href*='templateEdit.rol']").texts().toString();
    }

    private static String nonce() {
        return Long.toString(System.nanoTime(), 36);
    }
}
