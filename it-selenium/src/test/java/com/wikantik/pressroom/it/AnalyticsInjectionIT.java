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
import com.wikantik.pressroom.it.support.PressroomIT;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceAccessMode;
import org.junit.jupiter.api.parallel.ResourceLock;

import com.codeborne.selenide.CollectionCondition;

import static com.codeborne.selenide.Condition.exist;
import static com.codeborne.selenide.Condition.text;
import static com.codeborne.selenide.Condition.visible;
import static com.codeborne.selenide.Selenide.$;
import static com.codeborne.selenide.Selenide.$$;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Analytics needs no per-blog setup: Pressroom registers every blog with Umami
 * itself. The IT app has no Umami configured, so the Settings page offers no
 * analytics fields and no blog page carries a tracker tag. The tagged case is
 * covered by the rendering unit tests.
 *
 * <p>The tag check uses a raw, cookie-less {@code HttpClient} GET
 * ({@link #getAnonymously}) rather than a browser navigation, as before: were
 * a tag ever emitted, a browser visit would 404 the script (the IT stack has
 * nothing at {@code /analytics/}) and trip {@link BrowserHealth} on a failure
 * the test itself provoked.
 *
 * <p>Every test owns its own weblog and touches no site-wide configuration.
 */
@ResourceLock(value = PressroomIT.GLOBAL_CONFIG, mode = ResourceAccessMode.READ)
class AnalyticsInjectionIT extends PressroomIT {

    @Test
    void theHomePageCarriesNoAnalyticsScriptAndStaysClean() {
        loginAsAdmin();
        String handle = createWeblog();
        logout();

        openPath("/" + handle + "/");
        $("h1.qj-site").should(exist);
        $$("script[src^='/analytics/']").shouldHave(CollectionCondition.size(0));

        BrowserHealth.current().assertNoBrokenResources();
        BrowserHealth.current().assertNoFailedRequests();
    }

    @Test
    void theRenderedHeadHasNoTrackerTag() {
        loginAsAdmin();
        String handle = createWeblog();
        logout();

        String home = getAnonymously(baseUrl() + "/" + handle + "/");
        assertFalse(home.contains("data-website-id"),
                "with analytics unconfigured the head must carry no tracker tag, got: " + snippet(home));
        assertFalse(home.contains("/analytics/script.js"),
                "with analytics unconfigured the head must not load the tracker, got: " + snippet(home));
    }

    @Test
    void theSettingsPageHasNoAnalyticsFields() {
        loginAsAdmin();
        String handle = createWeblog();

        openSettings(handle);
        $("input[name='bean.analyticsSiteId']").shouldNot(exist);
        $("input[name='bean.analyticsShareUrl']").shouldNot(exist);
        $("#settings-analytics").shouldNot(exist);
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
