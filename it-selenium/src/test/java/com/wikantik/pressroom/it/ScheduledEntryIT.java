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

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

import com.wikantik.pressroom.it.support.Editor;
import com.wikantik.pressroom.it.support.PressroomIT;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceAccessMode;
import org.junit.jupiter.api.parallel.ResourceLock;

import static com.codeborne.selenide.Condition.exist;
import static com.codeborne.selenide.Condition.text;
import static com.codeborne.selenide.Condition.value;
import static com.codeborne.selenide.Condition.visible;
import static com.codeborne.selenide.Selenide.$;
import static com.codeborne.selenide.Selenide.executeJavaScript;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A future-dated entry is withheld from everything a reader or a crawler can
 * see.
 *
 * <p>{@code SCHEDULED} is the one publication status with no browser coverage.
 * It is also the one where a mistake is silent in both directions: an entry
 * that leaks early is published without its author meaning it, and one that
 * never leaks at all simply never appears. This covers the first — everywhere a
 * scheduled entry could escape to: the weblog's own pages, its Atom feed, and
 * the sitemap a crawler is pointed at.
 *
 * <p><b>What this deliberately does not cover</b> is the other half: the
 * {@code ScheduledEntriesTask} later promoting the entry to
 * {@code PUBLISHED}. That is not skipped for lack of value — it is the more
 * interesting failure — but for cost and determinism. An entry only becomes
 * {@code SCHEDULED} when its publication time is more than a minute away
 * ({@code EntryEditController}), and the task runs on a cadence configured in
 * <em>minutes</em>, minimum one ({@code PressroomTask#getInterval}). The two
 * together put the earliest possible observation between one and three minutes
 * after the entry is saved, with real variance, in a suite whose whole browser
 * run is about ten. Shortening it would mean giving the task a sub-minute
 * interval that the configuration cannot currently express — a product change
 * made for a test's convenience. {@code ScheduledEntriesTask} keeps its unit
 * coverage in the meantime.
 */
@ResourceLock(value = PressroomIT.GLOBAL_CONFIG, mode = ResourceAccessMode.READ)
class ScheduledEntryIT extends PressroomIT {

    /** Rendered on the edit page only once the entry has been saved. */
    private static final String PERMALINK = "#entry_bean_permalink";

    /** The wire format {@code bean.pubTimeLocal}'s {@code datetime-local} input carries. */
    private static final DateTimeFormatter ENTRY_PUB_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm");

    @Test
    void aScheduledEntryIsWithheldFromReaders() {
        String suffix = nonce();
        String live = "Live now " + suffix;
        String scheduled = "Scheduled for later " + suffix;

        loginAsAdmin();
        String handle = createWeblog();

        // A published entry alongside it, so "the scheduled one is absent"
        // cannot be satisfied by the whole weblog rendering empty.
        String livePermalink = publishEntry(handle, live);
        scheduleEntry(handle, scheduled);

        String home = readerHome(handle);
        assertTrue(home.contains(live),
                "the published entry must be on the front page, got: " + snippet(home));
        assertFalse(home.contains(scheduled),
                "a scheduled entry must not be on the front page, got: " + snippet(home));

        // RSS is gone (W2, Atom only) -- this used to loop rss/atom; only
        // the observation channel changed, not the invariant being proved.
        String feed = getAnonymously(baseUrl() + "/" + handle + "/feed/entries/atom");
        assertTrue(feed.contains(live),
                "atom must carry the published entry, got: " + snippet(feed));
        assertFalse(feed.contains(scheduled),
                "a scheduled entry must not be syndicated in atom, got: " + snippet(feed));

        // The sitemap is where a scheduled entry leaking is worst: a crawler
        // told about a URL fetches it, and keeps the result.
        String sitemap = getAnonymously(baseUrl() + "/sitemap-" + handle + ".xml");
        assertTrue(sitemap.contains(livePermalink),
                "the published entry must be in the sitemap, got: " + snippet(sitemap));
        // Proves anchorOf computes anchors the way Pressroom does. Without this the
        // absence check below would also pass on a helper that simply built the
        // wrong string.
        assertTrue(sitemap.contains(anchorOf(live)),
                "anchorOf must match the anchor Pressroom generated for " + live
                        + ", got: " + snippet(sitemap));
        assertFalse(sitemap.contains(anchorOf(scheduled)),
                "a scheduled entry must not be advertised to crawlers, got: " + snippet(sitemap));

        logout();
    }

    // ---------------------------------------------------------------- fixture

    /**
     * Saves an entry dated a year ahead, and checks the editor agrees it is
     * scheduled rather than published.
     *
     * <p>A year rather than a minute or two: the point here is the withholding,
     * and a publication time that could arrive mid-test would make every
     * assertion below a race.
     */
    private void scheduleEntry(String handle, String title) {
        openPath("/pressroom-ui/authoring/entryAdd.rol?weblog=" + handle);
        $("#entry").should(exist);
        $("[name='bean.title']").setValue(title);
        Editor.setText("Body of " + title);

        executeJavaScript("document.getElementsByName('bean.pubTimeLocal')[0].value = arguments[0];",
                LocalDate.now().plusYears(1).atStartOfDay().format(ENTRY_PUB_TIME));

        $("button[formaction$='entryAdd!publish.rol']").click();

        // Not the permalink control: the editor renders that only for a
        // published entry, which is itself part of the contract here. The saved
        // id is what says the entry exists at all.
        $("input[name='bean.id']").should(exist);
        $("input[name='bean.status']").shouldHave(value("SCHEDULED"));
        $("#messages").should(exist).shouldHave(text("scheduled"));
    }

    /** Creates a weblog owned by the seeded admin, on the default theme. */
    private String createWeblog() {
        String handle = "sched" + nonce();

        openPath("/pressroom-ui/createWeblog.rol");
        $("#name").should(visible).setValue("Scheduled " + handle);
        $("#handle").setValue(handle);
        $("#emailAddress").setValue(handle + "@example.invalid");
        $("select[name='theme']").selectOptionByValue("journal");
        $("button[type='submit']").click();

        $("#messages").should(exist);
        return handle;
    }

    /** Publishes one entry immediately and returns its permalink. */
    private String publishEntry(String handle, String title) {
        openPath("/pressroom-ui/authoring/entryAdd.rol?weblog=" + handle);
        $("#entry").should(exist);
        $("[name='bean.title']").setValue(title);
        Editor.setText("Body of " + title);
        $("button[formaction$='entryAdd!publish.rol']").click();

        $(PERMALINK).should(exist);
        String permalink = $(PERMALINK).getAttribute("href");
        assertNotNull(permalink, "publishing must expose the entry's permalink");
        return permalink;
    }

    /**
     * The anchor Pressroom generates from a title: the first five words,
     * lowercased and hyphen-joined. Matching on this rather than the whole
     * title is what makes the sitemap check meaningful — the sitemap carries
     * URLs, not titles.
     */
    private static String anchorOf(String title) {
        String[] words = title.toLowerCase().replaceAll("[^a-z0-9]+", " ").trim().split(" ");
        return String.join("-", java.util.Arrays.copyOfRange(words, 0, Math.min(5, words.length)));
    }

    /**
     * The weblog home page as an anonymous reader gets it. The trailing slash
     * is load-bearing: the bare handle redirects and the anonymous fetch helper
     * does not follow redirects.
     */
    private String readerHome(String handle) {
        return getAnonymously(baseUrl() + "/" + handle + "/");
    }

    private static String snippet(String body) {
        String flat = body.replaceAll("\\s+", " ").trim();
        return flat.length() <= 300 ? "<<" + flat + ">>" : "<<" + flat.substring(0, 300) + "...>>";
    }

    private static String nonce() {
        return Long.toString(System.nanoTime(), 36);
    }
}
