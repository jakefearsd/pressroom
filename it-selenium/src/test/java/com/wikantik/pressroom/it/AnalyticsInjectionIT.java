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

import java.util.UUID;

import com.wikantik.pressroom.it.support.BrowserHealth;
import com.wikantik.pressroom.it.support.PressroomIT;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceAccessMode;
import org.junit.jupiter.api.parallel.ResourceLock;

import com.codeborne.selenide.CollectionCondition;

import static com.codeborne.selenide.Condition.exist;
import static com.codeborne.selenide.Condition.text;
import static com.codeborne.selenide.Condition.value;
import static com.codeborne.selenide.Condition.visible;
import static com.codeborne.selenide.Selenide.$;
import static com.codeborne.selenide.Selenide.$$;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Umami analytics tag: off by default, opt-in per weblog through the real
 * Settings form, validated as a UUID, and never reachable through a browser
 * visit in this stack.
 *
 * <p>The tracker script ({@code /analytics/script.js}) is served by Umami
 * itself in production (see {@code deploy/analytics}); the IT stack has
 * nothing listening there. A browser navigation to a page that emits the tag
 * would 404 the script and trip {@link BrowserHealth}'s broken-resource
 * check for a 404 the test itself provoked -- the same reasoning
 * {@code PageIT} uses for a draft page's status code. So the tag is proved
 * with the same tool: a raw, cookie-less {@code HttpClient} GET
 * ({@link #getAnonymously}), never a browser navigation to a page that would
 * actually try to load the script.
 *
 * <p>Every test owns its own weblog and touches no site-wide configuration,
 * the same discipline {@code WeblogConfigMatrixIT} uses -- this class drives
 * the identical Settings form to move {@code analyticsSiteId}.
 */
@ResourceLock(value = PressroomIT.GLOBAL_CONFIG, mode = ResourceAccessMode.READ)
class AnalyticsInjectionIT extends PressroomIT {

    @Test
    void withNoSiteIdTheHomePageCarriesNoAnalyticsScriptAndStaysClean() {
        loginAsAdmin();
        String handle = createWeblog();
        logout();

        openPath("/" + handle + "/");
        $("h1.qj-site").should(exist);
        $$("script[src^='/analytics/']").shouldHave(CollectionCondition.size(0));

        BrowserHealth.current().assertNoBrokenResources();
        BrowserHealth.current().assertNoFailedRequests();
    }

    /**
     * Sets a valid UUID through the real Settings form, then proves the tag
     * lands in the rendered head via a raw fetch -- see the class comment for
     * why this is not a browser visit.
     */
    @Test
    void aValidSiteIdSetThroughSettingsIsInjectedIntoTheRenderedHead() {
        loginAsAdmin();
        String handle = createWeblog();
        String siteId = UUID.randomUUID().toString();

        setAnalyticsSiteId(handle, siteId);
        logout();

        String home = getAnonymously(baseUrl() + "/" + handle + "/");
        assertTrue(home.contains("src=\"/analytics/script.js\""),
                "the rendered head must carry the tracker script src, got: " + snippet(home));
        assertTrue(home.contains("data-website-id=\"" + siteId + "\""),
                "the rendered head must carry the configured website id, got: " + snippet(home));
        assertTrue(home.contains("data-host-url=\"/analytics\""),
                "the rendered head must point the tracker at the self-hosted analytics path, "
                        + "got: " + snippet(home));
    }

    /**
     * An invalid site id is rejected by the same form, and rejecting it must
     * not overwrite whatever was stored before -- the field error is not
     * enough on its own; a validator that reports an error but still saves
     * would pass a check that only looked at {@code #errors}.
     */
    @Test
    void anInvalidSiteIdRendersAFieldErrorAndLeavesTheStoredValueUnchanged() {
        loginAsAdmin();
        String handle = createWeblog();
        String validSiteId = UUID.randomUUID().toString();

        setAnalyticsSiteId(handle, validSiteId);

        openSettings(handle);
        $("input[name='bean.analyticsSiteId']").setValue("not-a-uuid");
        $("button[type='submit'].btn-primary").should(visible).click();

        $("#errors").should(exist);
        $("#errors").shouldHave(text("not a website ID"));

        openSettings(handle);
        $("input[name='bean.analyticsSiteId']").shouldHave(value(validSiteId));
        logout();
    }

    /**
     * The Maintenance page renders with flush-cache still on it and no reset
     * button -- there is nothing left to reset now that hit counts are gone
     * (see the Wave C design doc), so a stray "Reset" control left over from
     * before that deletion would offer an operation the backing feature no
     * longer has.
     *
     * <p>Maintenance moved off the blog-author tabs and into Global Admin
     * (W2 Task 7): the page is reached at {@code /pressroom-ui/admin/
     * maintenance.rol} with no {@code weblog=} parameter, and the weblog to
     * operate on is chosen from a {@code <select>} on the page instead. This
     * still needs a weblog to exist so the picker has an option to select
     * (and so the page is not just proving an empty dropdown), even though
     * the test never submits the form.
     */
    @Test
    void maintenancePageKeepsFlushCacheAndHasNoResetButton() {
        loginAsAdmin();
        createWeblog();

        openPath("/pressroom-ui/admin/maintenance.rol");
        $("button[formaction$='maintenance!flushCache.rol']").should(exist);
        $$("button").findBy(text("Reset")).shouldNot(exist);
        assertFalse($("body").getText().toLowerCase().contains("reset hit"),
                "no control should offer to reset hit counts -- that feature is gone");

        BrowserHealth.current().assertNoBrokenResources();
        BrowserHealth.current().assertNoFailedRequests();
        logout();
    }

    // ---------------------------------------------------------------- helpers

    /** Sets {@code analyticsSiteId} through the real Settings form and confirms it stuck. */
    private void setAnalyticsSiteId(String handle, String siteId) {
        openSettings(handle);
        $("input[name='bean.analyticsSiteId']").setValue(siteId);
        $("button[type='submit'].btn-primary").should(visible).click();
        $("#messages").should(exist);
        assertTrue($$("#errors").isEmpty(),
                "saving a valid analytics site id must not report an error");
        BrowserHealth.current().settle();

        openSettings(handle);
        $("input[name='bean.analyticsSiteId']").shouldHave(value(siteId));
    }

    private void openSettings(String handle) {
        openPath("/pressroom-ui/authoring/weblogConfig.rol?weblog=" + handle);
        $("input[name='bean.name']").should(visible);
    }

    /** Creates a weblog owned by the seeded admin, on the default theme. */
    private String createWeblog() {
        String handle = "analytics" + nonce();

        openPath("/pressroom-ui/createWeblog.rol");
        $("#name").should(visible).setValue("Analytics " + handle);
        $("#handle").setValue(handle);
        $("#emailAddress").setValue(handle + "@example.invalid");
        $("select[name='theme']").selectOptionByValue("journal");
        $("button[type='submit']").click();

        $("#messages").should(exist);
        return handle;
    }

    private static String snippet(String body) {
        String flat = body.replaceAll("\\s+", " ").trim();
        return flat.length() <= 300 ? "<<" + flat + ">>" : "<<" + flat.substring(0, 300) + "...>>";
    }

    private static String nonce() {
        return Long.toString(System.nanoTime(), 36);
    }
}
