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
import com.wikantik.pressroom.it.support.PagesMediaApp;
import com.wikantik.pressroom.it.support.Editor;
import com.wikantik.pressroom.it.support.PressroomIT;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceAccessMode;
import org.junit.jupiter.api.parallel.ResourceLock;

import static com.codeborne.selenide.Condition.exist;
import static com.codeborne.selenide.Condition.text;
import static com.codeborne.selenide.Condition.visible;
import static com.codeborne.selenide.Selenide.$;
import static com.codeborne.selenide.Selenide.$$;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A blog's front door: choose a published page in the blog settings and the
 * blog root shows it, with the posts at {@code /posts} and a "Posts" nav link;
 * make the page a draft and the root lists posts again.
 *
 * <p>Owns its own {@code portfolio} weblog (the theme that renders the page
 * links), as {@code PageIT} does, and reads the public pages over plain HTTP
 * so that no step navigates the browser at a page that is meant to fail.
 */
@ResourceLock(value = PressroomIT.GLOBAL_CONFIG, mode = ResourceAccessMode.READ)
class FrontDoorIT extends PressroomIT {

    private HttpClient http;

    @BeforeEach
    void logIn() {
        loginAsAdmin();
        http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    @Test
    void aPublishedPageBecomesTheBlogRootAndADraftGivesItBack() {
        String nonce = Long.toString(System.nanoTime(), 36);
        String handle = "frontit" + nonce;
        String pageTitle = "Welcome " + nonce;
        String postTitle = "Post " + nonce;
        String root = baseUrl() + "/" + handle + "/";

        createWeblog(handle);
        publishPost(handle, postTitle);
        String before = get(root);
        assertTrue(before.contains(postTitle), "with no front door the root lists posts");
        assertFalse(before.contains(">Posts</a>"), "no Posts link without a front door");

        // --- a published page ---------------------------------------------
        openPath(PagesMediaApp.newPage(handle));
        PagesMediaApp.awaitPageEditor();
        $("#page_bean_slug").setValue("welcome");
        $("#page_bean_title").setValue(pageTitle);
        Editor.setText("Hello from the front page.");
        $("#page_bean_status").selectOptionByValue("PUBLISHED");
        PagesMediaApp.savePage();
        BrowserHealth.current().settle();

        // --- choose it in the blog settings ----------------------------------
        openPath("/pressroom-ui/authoring/weblogConfig.rol?weblog=" + handle);
        $("#weblog_bean_frontPageId").should(visible).selectOption(pageTitle);
        clickAndAwaitNewPage($("button[type='submit'].btn-primary").should(visible));
        $("#messages").should(exist);
        assertTrue($$("#errors").isEmpty(), "saving the blog settings reported an error");
        BrowserHealth.current().settle();

        String front = get(root);
        assertTrue(front.contains(pageTitle), "the root shows the front page, got: " + truncate(front));
        assertFalse(front.contains(postTitle), "the front page replaces the post list at the root");
        assertTrue(front.contains(">Posts</a>"), "the nav carries a Posts link");
        assertTrue(get(root + "posts").contains(postTitle), "/posts lists the post");

        // --- make the page a draft: the root lists posts again -----------------
        openPath(PagesMediaApp.pages(handle));
        PagesMediaApp.awaitPagesList();
        $$("tr.page-row td a").findBy(text(pageTitle)).should(exist).click();
        PagesMediaApp.awaitPageEditor();
        $("#page_bean_status").selectOptionByValue("DRAFT");
        PagesMediaApp.savePage();
        BrowserHealth.current().settle();

        String after = get(root);
        assertTrue(after.contains(postTitle), "a draft front page gives the root back to the posts");
        assertFalse(after.contains(">Posts</a>"), "the Posts link goes with the front door");
    }

    private void createWeblog(String handle) {
        openPath("/pressroom-ui/createWeblog.rol");
        $("#name").should(visible).setValue("Front " + handle);
        $("#handle").setValue(handle);
        $("#emailAddress").setValue(handle + "@example.invalid");
        $("select[name='theme']").selectOptionByValue("portfolio");
        $("button[type='submit']").click();
        $("h2.mm_weblog_name").should(exist); // created: the app home lists the weblogs
    }

    private void publishPost(String handle, String title) {
        openPath("/pressroom-ui/authoring/entryAdd.rol?weblog=" + handle);
        $("#entry").should(exist);
        $("[name='bean.title']").setValue(title);
        Editor.setText("Body of " + title + ".");
        clickAndAwaitNewPage($("button[formaction$='entryAdd!publish.rol']"));
        $("#entry_bean_permalink").should(exist);
    }

    /** An anonymous GET of the public page: what a reader sees, not the author's view. */
    private String get(String url) {
        try {
            HttpResponse<String> response = http.send(HttpRequest.newBuilder().uri(URI.create(url))
                    .timeout(Duration.ofSeconds(20)).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode(), "GET " + url);
            return response.body();
        } catch (Exception e) {
            throw new IllegalStateException("Could not GET " + url, e);
        }
    }

    private static String truncate(String text) {
        String flat = text.replaceAll("\\s+", " ").trim();
        return flat.length() <= 600 ? flat : flat.substring(0, 600) + "...";
    }
}
