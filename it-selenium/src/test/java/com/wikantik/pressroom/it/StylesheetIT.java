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

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.wikantik.pressroom.it.support.BrowserHealth;
import com.wikantik.pressroom.it.support.PressroomIT;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceAccessMode;
import org.junit.jupiter.api.parallel.ResourceLock;

import static com.codeborne.selenide.Condition.exactText;
import static com.codeborne.selenide.Condition.exist;
import static com.codeborne.selenide.Condition.text;
import static com.codeborne.selenide.Condition.visible;
import static com.codeborne.selenide.Selenide.$;
import static com.codeborne.selenide.Selenide.$$;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Stylesheet screen end to end, on a weblog of its own: a weblog on a shared theme offers to copy the
 * theme's stylesheet; the copy is edited and saved, and the public blog then serves the edit; deleting the
 * copy gives the theme's default back, and the public blog stops serving the edit. What is asserted is the
 * stylesheet an anonymous reader's browser would fetch, not that a click happened.
 *
 * <p>Holds {@link PressroomIT#GLOBAL_CONFIG} for READ: it creates a weblog.
 */
@ResourceLock(value = PressroomIT.GLOBAL_CONFIG, mode = ResourceAccessMode.READ)
class StylesheetIT extends PressroomIT {

    private static final Pattern STYLESHEET_LINK = Pattern.compile(
            "<link[^>]*rel=\"stylesheet\"[^>]*href=\"([^\"]+)\"|<link[^>]*href=\"([^\"]+)\"[^>]*rel=\"stylesheet\"");

    @Test
    void aCopiedStylesheetIsEditedServedToReadersAndDeleted() {
        String nonce = Long.toString(System.nanoTime(), 36);
        String handle = "styleit" + nonce;
        String marker = ".it-marker-" + nonce;
        String screen = "/pressroom-ui/app/weblogs/" + handle + "/stylesheet";

        loginAsAdmin();
        createWeblogOnTheReactScreen(handle, "Stylesheet " + nonce, "journal");
        try {
            // --- a shared theme with a stylesheet: nothing to edit until it is copied
            openPath(screen);
            $$("button.btn-primary").findBy(text("Copy stylesheet")).should(exist);
            assertTrue($$("#stylesheetEditForm").isEmpty(), "a weblog on a shared theme has nothing to edit yet");
            assertFalse(servedStylesheets(handle).contains(marker), "the marker is on the public blog before it exists");
            $$("button.btn-primary").findBy(text("Copy stylesheet")).click();
            $("#messages").shouldHave(text("Custom stylesheet created."));
            $("#stylesheet_contentsStandard").should(visible);
            String theme = $("#stylesheet_contentsStandard").getValue();
            assertTrue(theme != null && !theme.isBlank(), "the copy starts as the theme's stylesheet");

            // --- edit and save: it persists across a reload
            String edited = theme + "\n" + marker + " { color: #010203; }\n";
            $("#stylesheet_contentsStandard").setValue(edited);
            $("#stylesheetEditForm button.btn-primary").click();
            $("#messages").shouldHave(text("Stylesheet saved."));
            openPath(screen);
            $("#stylesheet_contentsStandard").should(visible);
            assertTrue($("#stylesheet_contentsStandard").getValue().contains(marker),
                    "the saved edit is not in the stylesheet after a reload");

            // --- the public blog serves it
            assertTrue(servedStylesheets(handle).contains(marker),
                    "the public blog does not serve the edited stylesheet");

            // --- delete the copy: the theme's default comes back, and the edit goes
            $("#stylesheetEditForm button.btn-danger").click();
            confirmReactDialog();
            $("#messages").shouldHave(text("Stylesheet deleted."));
            $$("button.btn-primary").findBy(text("Copy stylesheet")).should(exist);
            assertTrue($$("#stylesheetEditForm").isEmpty(), "the editor is still offered after the copy was deleted");
            assertFalse(servedStylesheets(handle).contains(marker),
                    "the public blog still serves the deleted stylesheet's edit");
            BrowserHealth.current().settle();
        } finally {
            openPath("/pressroom-ui/app/weblogs/" + handle + "/remove");
            $("button.btn-danger").should(visible).click();
            $("h2.mm_weblog_name").should(exist);
            $$("h2.mm_weblog_name .data").findBy(exactText(handle)).shouldNot(exist);
        }
        logout();
    }

    /** Every stylesheet the weblog's public home links, fetched anonymously, concatenated. */
    private static String servedStylesheets(String handle) {
        String page = getAnonymously(baseUrl() + "/" + handle + "/");
        List<String> hrefs = new ArrayList<>();
        Matcher match = STYLESHEET_LINK.matcher(page);
        while (match.find()) {
            hrefs.add((match.group(1) != null ? match.group(1) : match.group(2)).replace("&amp;", "&"));
        }
        assertFalse(hrefs.isEmpty(), "the public blog links no stylesheet: " + page);
        StringBuilder all = new StringBuilder();
        for (String href : hrefs) {
            String url = href.startsWith("http") ? href : baseUrl().replaceFirst("(https?://[^/]+).*", "$1") + href;
            all.append(getAnonymously(url)).append('\n');
        }
        return all.toString();
    }
}
