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

import com.wikantik.pressroom.it.support.AdminApp;
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
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The site Setup screen ({@code /admin/setup}): choosing the front-page weblog there takes effect at the
 * site root. A site admin picks a weblog of their own, saves, and an anonymous visitor to {@code /} gets
 * that weblog's posts (before, they did not); the choice survives a reload of the screen. The previous
 * choice is put back through Global Config (the Setup select has no empty option), in a {@code finally}.
 *
 * <p>{@code site.frontpage.weblog.handle} is global, so the class holds {@link PressroomIT#GLOBAL_CONFIG}
 * for WRITE, as {@code ColophonIT} does.
 */
@ResourceLock(value = PressroomIT.GLOBAL_CONFIG, mode = ResourceAccessMode.READ_WRITE)
class SetupIT extends PressroomIT {

    private static final String FRONTPAGE_FIELD = "site.frontpage.weblog.handle";

    @Test
    void choosingTheFrontPageWeblogOnSetupMakesItTheSiteRoot() {
        String nonce = Long.toString(System.nanoTime(), 36);
        String handle = "setupit" + nonce;
        String title = "Setup front page post " + nonce;
        String root = baseUrl() + "/";

        loginAsAdmin();
        createWeblogOnTheReactScreen(handle, "Setup " + nonce, "journal");
        openPath(AdminApp.newEntry(handle));
        AdminApp.write(title, "On the front page");
        AdminApp.publishButton().click();
        $(AdminApp.PERMALINK).should(exist);

        openGlobalConfig();
        String was = $("select[name='" + FRONTPAGE_FIELD + "']").should(exist).getSelectedOptionValue();
        if (was == null) {
            was = "";
        }
        boolean changed = false;
        try {
            // --- the checklist is there, and the site root is not this weblog yet
            openPath("/pressroom-ui/app/admin/setup");
            $$("h2.setup-section-title").shouldHave(com.codeborne.selenide.CollectionCondition.size(3));
            $("#frontpageBlog").should(visible);
            assertFalse(getAnonymously(root).contains(title), "the site root already shows the new weblog's post");

            // --- choose it and save
            $("#frontpageBlog").selectOptionByValue(handle);
            changed = true;
            $("button[type='submit'].btn-primary").click();
            $("#messages").shouldHave(text("Front page settings saved."));
            assertTrue($$("#errors").isEmpty(), "saving the front page reported an error");

            // --- it persisted, and it took effect at the site root
            openPath("/pressroom-ui/app/admin/setup");
            $("#frontpageBlog").should(visible);
            assertEquals(handle, $("#frontpageBlog").getSelectedOptionValue(), "the choice did not persist");
            assertTrue(getAnonymously(root).contains(title),
                    "the site root does not show the chosen weblog's posts");
        } finally {
            if (changed) {
                openGlobalConfig();
                $("select[name='" + FRONTPAGE_FIELD + "']").selectOptionByValue(was);
                clickAndAwaitSavedMessage($("#saveButton"));
                openGlobalConfig();
                assertEquals(was, $("select[name='" + FRONTPAGE_FIELD + "']").getSelectedOptionValue(),
                        "site.frontpage.weblog.handle was not restored");
            }
            openPath("/pressroom-ui/app/weblogs/" + handle + "/remove");
            $("button.btn-danger").should(visible).click();
            $("h2.mm_weblog_name").should(exist);
            $$("h2.mm_weblog_name .data").findBy(exactText(handle)).shouldNot(exist);
        }
        logout();
    }
}
