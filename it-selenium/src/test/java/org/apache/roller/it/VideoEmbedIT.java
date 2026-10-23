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
package org.apache.roller.it;

import com.codeborne.selenide.Selenide;
import org.apache.roller.it.support.BrowserHealth;
import org.apache.roller.it.support.Editor;
import org.apache.roller.it.support.RollerIT;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static com.codeborne.selenide.Condition.attribute;
import static com.codeborne.selenide.Condition.exist;
import static com.codeborne.selenide.Selenide.$;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * The {@code [video]} shortcode's click-to-play facade, in a real browser.
 *
 * <p>{@code VideoShortcodeTest} (server-side, in the {@code app} module) pins
 * the exact markup a {@code [video]} shortcode renders to: a
 * {@code <div class="video-embed">} carrying the provider and video id as
 * data attributes, plus a thumbnail -- never an {@code <iframe>}. What that
 * unit test cannot see is the browser half of the contract: that a real
 * permalink page really does stop at the placeholder and inject nothing from
 * YouTube until a reader acts.
 *
 * <p><b>This deliberately never clicks the facade.</b> Clicking would load a
 * real {@code youtube-nocookie.com} iframe, making the suite depend on a
 * third-party service being reachable from CI for a assertion that has
 * nothing to do with whether Roller wired the facade correctly. What is
 * asserted instead is the wiring: the placeholder's data attributes are
 * present and no {@code <iframe>} exists before any interaction --
 * {@code #showEmbedAssets} (see {@code weblog.vm}) only ever creates one from
 * a {@code click} listener.
 */
class VideoEmbedIT extends RollerIT {

    private static final String ENTRY_ADD = "/roller-ui/authoring/entryAdd.rol?weblog=" + WEBLOG_HANDLE;

    /** Rendered on the edit page only once the entry has been saved. */
    private static final String PERMALINK = "#entry_bean_permalink";

    /** The id {@code https://youtu.be/dQw4w9WgXcQ} carries, per VideoShortcode's YouTube id pattern. */
    private static final String VIDEO_ID = "dQw4w9WgXcQ";

    @BeforeEach
    void logIn() {
        loginAsAdmin();
    }

    @Test
    void aPublishedVideoShortcodeRendersAFacadeWithNoIframeBeforeAClick() {
        String suffix = nonce();

        // --- author and publish an entry carrying [video] --------------------
        openPath(ENTRY_ADD);
        $("#entry").should(exist);
        $("[name='bean.title']").setValue("IT Video " + suffix);
        Editor.setText("<p>Watch this.</p><p>[video url=\"https://youtu.be/" + VIDEO_ID + "\"]</p>");
        $("button[formaction$='entryAdd!publish.rol']").click();

        $(PERMALINK).should(exist);
        String permalink = $(PERMALINK).getAttribute("href");
        assertNotNull(permalink, "publishing must expose the entry's permalink");

        // --- the public permalink shows the facade, not a live embed --------
        Selenide.open(permalink);
        BrowserHealth.current().settle();

        $(".video-embed").should(exist)
                .shouldHave(attribute("data-provider", "youtube"))
                .shouldHave(attribute("data-video-id", VIDEO_ID));

        // The privacy property this test exists for: nothing from YouTube is
        // loaded until a reader clicks.
        $(".video-embed iframe").shouldNot(exist);

        // Catches the CSP refusing the thumbnail -- see BrowserHealth's own
        // comment on why a refused sub-resource can otherwise render an
        // unstyled/broken page and still pass a plain element-exists check.
        BrowserHealth.current().assertNoBrokenResources();
        BrowserHealth.current().assertNoFailedRequests();
    }

    private static String nonce() {
        return Long.toString(System.nanoTime(), 36);
    }
}
