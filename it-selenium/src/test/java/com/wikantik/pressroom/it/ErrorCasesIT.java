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

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import com.wikantik.pressroom.it.support.BrowserHealth;
import com.wikantik.pressroom.it.support.PressroomIT;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceAccessMode;
import org.junit.jupiter.api.parallel.ResourceLocks;
import org.junit.jupiter.api.parallel.ResourceLock;

import static com.codeborne.selenide.Condition.exist;
import static com.codeborne.selenide.Condition.visible;
import static com.codeborne.selenide.Selenide.$;
import static com.codeborne.selenide.Selenide.$$;
import static com.codeborne.selenide.Selenide.executeJavaScript;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The unhappy paths: bad input, missing things, and doors that should be shut.
 *
 * <p>Every other browser test drives a path that works. These drive the ones
 * that should not, which is where this codebase has repeatedly turned out to be
 * weak -- a login that succeeded into a 403, a comment form that always
 * returned 403, template ids resolved without an ownership check. A feature
 * failing correctly is as much a behaviour as a feature working.
 */
/*
 * Only reads the shared media page, so it takes both locks in READ mode: it can
 * run alongside other readers, but not while a media writer is mid-upload or
 * while a global-config class is toggling uploads.enabled.
 */
@ResourceLocks({
        @ResourceLock(value = PressroomIT.SHARED_MEDIA, mode = ResourceAccessMode.READ),
        @ResourceLock(value = PressroomIT.GLOBAL_CONFIG, mode = ResourceAccessMode.READ)
})
class ErrorCasesIT extends PressroomIT {

    private static final String CREATE_USER = "/pressroom-ui/admin/createUser.rol";
    private static final String CREATE_WEBLOG = "/pressroom-ui/createWeblog.rol";

    // ------------------------------------------------------------ not found

    /**
     * A URL shaped like a weblog that is not one must be a clean 404, not a
     * 500 and not a page pretending the weblog exists.
     */
    @Test
    void anUnknownWeblogIsNotFound() {
        assertEquals(404, statusOf(baseUrl() + "/nosuchweblog" + nonce()),
                "an unknown weblog handle must 404");
    }

    @Test
    void anUnknownEntryOnARealWeblogIsNotFound() {
        assertEquals(404,
                statusOf(baseUrl() + "/" + WEBLOG_HANDLE + "/entry/nosuchentry" + nonce()),
                "an unknown permalink on a real weblog must 404");
    }

    // --------------------------------------------------------- bad sign-in

    @Test
    void aWrongPasswordDoesNotSignAnyoneIn() {
        openPath("/pressroom-ui/login.rol");
        $("#j_username").setValue(ADMIN_USERNAME);
        $("#j_password").setValue("definitely-not-the-password");
        // The refusal is a new page (login.rol?error=true). Waiting for it is what makes
        // the #j_username check below about the answer rather than the form we typed into.
        clickAndAwaitNewPage($("#login"));

        // Back at a login form, and the editor is still closed to us.
        $("#j_username").should(exist);
        openPath("/pressroom-ui/authoring/entries.rol?weblog=" + WEBLOG_HANDLE);
        assertTrue($$("table.rollertable").isEmpty(),
                "a failed login left the entry list reachable");
    }

    // -------------------------------------------------------- validation

    /**
     * Handles are the weblog's URL, so a duplicate has to be refused rather
     * than shadowing the existing blog.
     */
    @Test
    void aDuplicateWeblogHandleIsRefused() {
        loginAsAdmin();
        openPath(CREATE_WEBLOG);
        $("#name").should(visible).setValue("Clashing Blog");
        $("#handle").setValue(WEBLOG_HANDLE);
        $("#emailAddress").setValue("clash@example.invalid");
        clickAndAwaitNewPage($("button[type='submit']"));

        // Outcome, not page shape: a refusal may render errors or bounce, but
        // it must never report success.
        assertFalse($$("#messages").size() > 0,
                "creating a weblog on an existing handle reported success");
        logout();
    }

    @Test
    void aDuplicateUserNameIsRefused() {
        loginAsAdmin();
        openPath(CREATE_USER);
        $("input[name='bean.userName']").should(visible).setValue(ADMIN_USERNAME);
        $("input[name='bean.screenName']").setValue("Impostor");
        $("input[name='bean.fullName']").setValue("Impostor");
        $("input[name='bean.password']").setValue("some-password");
        $("input[name='bean.emailAddress']").setValue("impostor@example.com");
        // Not click-then-settle: that read the form we had typed into, passed on it, and
        // signed out under the still-unsent POST, which then reached a dead session --
        // the 403 on createUser!save.rol (docs/dev/build-and-ci.md).
        clickAndAwaitNewPage($("#save_button"));

        assertFalse($$("#messages").size() > 0,
                "creating a second account with an existing username reported success");
        logout();
    }

    /**
     * An empty handle used to reach a code path that created a weblog with no
     * URL of its own; the controller guards it now, and this keeps it guarded.
     */
    @Test
    void aWeblogWithNoHandleIsRefused() {
        loginAsAdmin();
        openPath(CREATE_WEBLOG);
        $("#name").should(visible).setValue("No Handle Blog");
        $("#emailAddress").setValue("nohandle@example.invalid");
        // The form marks #handle `required`, so the browser would refuse to submit and
        // the server guard this test is named for would never run. Take it off.
        executeJavaScript("document.getElementById('handle').removeAttribute('required')");
        clickAndAwaitNewPage($("button[type='submit']"));

        // The server refused: the create form is shown again with its field error.
        $("#handle").should(visible);
        assertTrue($("body").text().contains("The handle you specified is not valid"),
                "the server did not refuse a weblog with no handle");
        logout();
    }

    // -------------------------------------------------------- permissions

    /**
     * The seeded administrator is an admin, so this asks the opposite
     * question: that the admin screens are actually gated rather than merely
     * unlinked from the menu. Anonymous is the cheapest caller to prove it
     * with, and the one an attacker uses.
     */
    @Test
    void adminScreensAreClosedToAnonymousCallers() {
        for (String path : new String[] {
                "/pressroom-ui/admin/globalConfig.rol",
                "/pressroom-ui/admin/createUser.rol",
                "/pressroom-ui/admin/userAdmin.rol"}) {
            int status = statusOf(baseUrl() + path);
            assertTrue(status == 401 || status == 403 || status == 302 || status == 200,
                    path + " answered " + status + " to an anonymous caller");
            if (status == 200) {
                String body = getAnonymously(baseUrl() + path);
                assertTrue(body.contains("j_username") || body.contains("Access Denied"),
                        path + " served its own content to an anonymous caller:\n"
                                + body.substring(0, Math.min(400, body.length())));
            }
        }
    }

    /**
     * The authoring screens take a weblog handle as a parameter, so an
     * anonymous caller naming a real weblog must still be turned away.
     */
    @Test
    void authoringScreensAreClosedToAnonymousCallers() {
        for (String path : new String[] {
                "/pressroom-ui/authoring/entries.rol?weblog=" + WEBLOG_HANDLE,
                "/pressroom-ui/authoring/entryAdd.rol?weblog=" + WEBLOG_HANDLE,
                "/pressroom-ui/authoring/mediaFileView.rol?weblog=" + WEBLOG_HANDLE}) {
            // A redirect to the login page is the expected answer and carries
            // no body, so judge on the status first and only inspect content
            // when something was actually served.
            int status = statusOf(baseUrl() + path);
            assertTrue(status == 302 || status == 401 || status == 403 || status == 200,
                    path + " answered " + status + " to an anonymous caller");
            if (status == 200) {
                String body = getAnonymously(baseUrl() + path);
                assertFalse(body.contains("rollertable") || body.contains("bean.title"),
                        path + " served authoring content to an anonymous caller");
            }
        }
    }

    // ---------------------------------------------------------------- utils

    /** Status of an anonymous GET, without following redirects. */
    private static int statusOf(String url) {
        try {
            HttpClient http = HttpClient.newBuilder()
                    .followRedirects(HttpClient.Redirect.NEVER)
                    .build();
            HttpResponse<Void> response = http.send(
                    HttpRequest.newBuilder().uri(URI.create(url))
                            .timeout(Duration.ofSeconds(20))
                            .GET().build(),
                    HttpResponse.BodyHandlers.discarding());
            return response.statusCode();
        } catch (Exception e) {
            throw new IllegalStateException("Could not GET " + url, e);
        }
    }

    private static String nonce() {
        return Long.toString(System.nanoTime(), 36);
    }
}
