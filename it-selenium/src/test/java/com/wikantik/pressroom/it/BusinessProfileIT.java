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

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.codeborne.selenide.Selenide;
import com.codeborne.selenide.SelenideElement;
import com.wikantik.pressroom.it.support.BrowserHealth;
import com.wikantik.pressroom.it.support.PressroomIT;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceAccessMode;
import org.junit.jupiter.api.parallel.ResourceLock;

import static com.codeborne.selenide.Condition.exist;
import static com.codeborne.selenide.Condition.text;
import static com.codeborne.selenide.Condition.visible;
import static com.codeborne.selenide.Selenide.$;
import static com.codeborne.selenide.Selenide.$$;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Business profiles end to end: a site admin creates a shared business, a
 * blog owner selects it and describes the place, and an anonymous reader
 * gets the footer card and a second JSON-LD block whose place node names
 * the business as its parent organisation.
 *
 * <p>Owns its weblog and its business (the business is deleted at the end,
 * after the blog lets go of it, since a business in use cannot be deleted);
 * touches no global flag, so the global-config lock is READ only. The public
 * page is fetched anonymously so what is asserted is what a reader sees.
 */
@ResourceLock(value = PressroomIT.GLOBAL_CONFIG, mode = ResourceAccessMode.READ)
class BusinessProfileIT extends PressroomIT {

    /**
     * Unique per test instance: class-parallel runs, and the same suite at two
     * context paths against one database, must not meet in the businesses list.
     */
    private final String businessName = "IT Rentals " + Long.toString(System.nanoTime(), 36);

    private static final Pattern JSON_LD = Pattern.compile(
            "<script type=\"application/ld\\+json\">(.*?)</script>", Pattern.DOTALL);

    @Test
    void aBlogOwnerPicksASharedBusinessAndTheReaderSeesTheCardAndTheJsonLd() {
        loginAsAdmin();
        String handle = createWeblog();
        AtomicBoolean businessCreated = new AtomicBoolean();
        Throwable primary = null;
        try {
            // --- the site admin creates the shared business ---------------
            openPath("/pressroom-ui/admin/businesses.rol");
            $("#businesses-list-marker").should(exist);
            clickAndAwaitNewPage($("#business-new"));
            $("#bean_name").should(visible).setValue(businessName);
            $("#bean_bookingUrl").setValue("https://book.example.com/stay");
            $("#bean_telephone").setValue("+351 912 345 678");
            businessCreated.set(true);
            clickAndAwaitNewPage($("#business-save"));
            $("#messages").should(exist);
            assertTrue($$("#errors").isEmpty(), "saving the business reported an error");
            openPath("/pressroom-ui/admin/businesses.rol");
            $$("table.rollertable td").findBy(text(businessName)).should(exist);

            // --- the blog owner selects it and describes the place --------
            openPath("/pressroom-ui/authoring/weblogConfig.rol?weblog=" + handle);
            $("#settings-business").should(exist);
            $("#weblog_bean_businessId").selectOption(businessName);
            $("#weblog_bean_placeType").selectOptionByValue("LodgingBusiness");
            $("#weblog_bean_placeLocality").setValue("Porto");
            $("#weblog_bean_placeCountry").setValue("PT");
            saveSettings();

            // --- the reader sees the card and the place node --------------
            String home = getAnonymously(baseUrl() + "/" + handle + "/");
            Matcher card = Pattern.compile("<aside class=\"business-card\">(.*?)</aside>",
                    Pattern.DOTALL).matcher(home);
            assertTrue(card.find(), "the footer card must render");
            String cardHtml = card.group(1);
            assertTrue(cardHtml.contains(">Porto<"), "the card must carry the locality: " + cardHtml);
            // A place's card shows the place name (the blog name); the
            // business appears as the place's parentOrganization below.
            assertTrue(cardHtml.contains(">Business " + handle + "<"),
                    "the card must carry the place (blog) name: " + cardHtml);
            assertTrue(cardHtml.contains("href=\"https://book.example.com/stay"),
                    "the card must link the booking URL: " + cardHtml);
            assertTrue(cardHtml.contains("data-umami-event=\"business-card-click\""), cardHtml);

            List<String> blocks = jsonLdBlocks(home);
            assertTrue(blocks.size() >= 2, "expected a second ld+json block, got " + blocks.size());
            String place = blocks.get(1);
            assertTrue(Pattern.compile("\"@type\"\\s*:\\s*\"LodgingBusiness\"").matcher(place).find(),
                    "the second block must be the LodgingBusiness place: " + place);
            assertTrue(Pattern.compile("\"parentOrganization\"\\s*:\\s*\\{[^{}]*\"name\"\\s*:\\s*\""
                            + Pattern.quote(businessName) + "\"").matcher(place).find(),
                    "the place must name the business as its parentOrganization: " + place);
        } catch (Throwable t) {
            primary = t;
            throw t;
        } finally {
            // A cleanup failure must never replace the failure being reported.
            try {
                cleanUp(handle, businessCreated.get());
            } catch (RuntimeException | AssertionError cleanupFailure) {
                if (primary == null) {
                    throw cleanupFailure;
                }
                primary.addSuppressed(cleanupFailure);
            }
        }
        logout();
    }

    // ---------------------------------------------------------------- helpers

    private static List<String> jsonLdBlocks(String html) {
        Matcher m = JSON_LD.matcher(html);
        List<String> blocks = new java.util.ArrayList<>();
        while (m.find()) {
            blocks.add(m.group(1));
        }
        return blocks;
    }

    /** Unsets the blog's business, then deletes the business: identity cleanup. */
    private void cleanUp(String handle, boolean businessCreated) {
        openPath("/pressroom-ui/authoring/weblogConfig.rol?weblog=" + handle);
        $("#weblog_bean_businessId").selectOptionByValue("");
        saveSettings();
        if (!businessCreated) {
            return;
        }
        openPath("/pressroom-ui/admin/businesses.rol");
        SelenideElement row = $$("table.rollertable tr").findBy(text(businessName));
        awaitNewPageAfter(() -> {
            row.find("button[data-confirm]").click();
            Selenide.confirm();
        });
        $("#messages").should(exist);
        $$("table.rollertable td").findBy(text(businessName)).shouldNot(exist);
    }

    private void saveSettings() {
        clickAndAwaitNewPage($("button[type='submit'].btn-primary").should(visible));
        $("#messages").should(exist);
        assertTrue($$("#errors").isEmpty(),
                "saving the weblog settings reported an error: "
                        + ($$("#errors").isEmpty() ? "" : $("#errors").getText()));
        BrowserHealth.current().settle();
    }

    private String createWeblog() {
        String handle = "bizit" + Long.toString(System.nanoTime(), 36);

        openPath("/pressroom-ui/createWeblog.rol");
        $("#name").should(visible).setValue("Business " + handle);
        $("#handle").setValue(handle);
        $("#emailAddress").setValue(handle + "@example.invalid");
        $("select[name='theme']").selectOptionByValue("journal");
        clickAndAwaitNewPage($("button[type='submit']"));

        $("#messages").should(exist);
        return handle;
    }
}
