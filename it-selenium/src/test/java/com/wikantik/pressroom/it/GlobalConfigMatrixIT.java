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

import java.io.File;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.Map;

import com.codeborne.selenide.CollectionCondition;
import com.wikantik.pressroom.it.support.Editor;
import com.wikantik.pressroom.it.support.PressroomIT;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;

import static com.codeborne.selenide.Condition.exist;
import static com.codeborne.selenide.Condition.visible;
import static com.codeborne.selenide.Selenide.$;
import static com.codeborne.selenide.Selenide.$$;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Site-wide switches on the Admin Settings page, each proved by the behaviour
 * it is supposed to change.
 *
 * <p>Thirteen runtime properties have real branches in the code and exactly one
 * ({@code uploads.enabled}) had ever been moved from its default by a browser
 * test — and only ever in the direction that turns a feature on. Two of the
 * switches covered here could not be reached at all until they were promoted
 * from startup-only to runtime-settable.
 *
 * <p><b>This is the class that mutates global state</b>, deliberately kept to
 * one. Every flag is restored in a {@code finally}, because the suite shares a
 * single running instance and whatever is left switched stays switched for
 * every test that runs afterwards. Keeping the global permutations here is also
 * what would let the rest of the suite run in parallel one day: everything in
 * {@code WeblogConfigMatrixIT} and {@code ThemeMatrixIT} is per-weblog and safe.
 */
@ResourceLock(PressroomIT.GLOBAL_CONFIG)
class GlobalConfigMatrixIT extends PressroomIT {

    private static final String PERMALINK = "#entry_bean_permalink";

    /**
     * Two features switched off site-wide, each refused on its own page.
     *
     * <p>Batched into one settings save rather than two tests: the flags do
     * not interact, they are asserted on two different pages, and a test that
     * spends two settings round trips to prove two independent refusals is
     * mostly measuring the settings page.
     *
     * <p>The uploads check has to attempt an actual upload — the refusal lives
     * in the save handler, so the media page renders perfectly well with
     * uploads off.
     *
     * <p>{@code groupblogging.enabled} used to be checked here too, against
     * {@code invite.rol} — deleted along with the rest of the invite/accept
     * ceremony (the members page now grants access directly, see
     * {@code MembersController}). Its own behaviour survives (a user who
     * already owns a weblog is refused a second one) and is covered
     * separately below, isolated from {@code site.allowUserWeblogCreation} so
     * each flag proves its own branch rather than either one masking the
     * other.
     */
    @Test
    void switchingFeaturesOffSiteWideRefusesThemWhereTheyAreUsed() {
        loginAsAdmin();
        Map<String, Boolean> before = setGlobalFlags(Map.of(
                "uploads.enabled", false,
                "site.allowUserWeblogCreation", false));
        try {
            openPath("/roller-ui/authoring/mediaFileAdd.rol?weblog=" + WEBLOG_HANDLE);
            $("#uploadedFiles").should(exist).uploadFile(testImage());
            $("#uploadButton").click();
            $("#errors").should(visible);

            openPath("/roller-ui/createWeblog.rol");
            $("#errors").should(visible);
            $$("#handle").shouldHave(CollectionCondition.size(0));
        } finally {
            setGlobalFlags(before);
            logout();
        }
    }

    /**
     * {@code groupblogging.enabled} off refuses a second weblog to an
     * account that already owns one — the seeded admin, via the seed
     * fixture's {@code it_weblog} permission. Isolated from
     * {@code site.allowUserWeblogCreation} (left at its default) so this
     * assertion cannot pass for the wrong reason.
     */
    @Test
    void groupBloggingDisabledRefusesASecondWeblogToAnExistingOwner() {
        loginAsAdmin();
        boolean before = setGlobalFlag("groupblogging.enabled", false);
        try {
            openPath("/roller-ui/createWeblog.rol");
            $("#errors").should(visible);
            $$("#handle").shouldHave(CollectionCondition.size(0));
        } finally {
            setGlobalFlag("groupblogging.enabled", before);
            logout();
        }
    }

    /**
     * The word separator for new entry URLs is a site setting, and it applies to
     * anchors generated from then on.
     *
     * <p>Only reachable at all since this property was promoted from
     * startup-only: before that, seeing the other branch meant restarting the
     * server with a different {@code roller.properties}. It was also a
     * {@code static final} read once at class load, so even a restart-free
     * change would have been ignored.
     *
     * <p>The entry published <em>before</em> the switch keeps its hyphens, which
     * is what stops a change of setting from breaking every existing permalink.
     */
    @Test
    void theEntryUrlSeparatorAppliesToNewEntriesOnly() {
        String suffix = nonce();

        loginAsAdmin();
        String handle = createWeblog();
        String hyphenated = publishEntry(handle, "Hyphen separated title " + suffix);
        assertTrue(hyphenated.contains("hyphen-separated-title"),
                "the shipped default joins words with hyphens, got: " + hyphenated);

        boolean before = setGlobalFlag("weblogentry.title.useUnderscoreSeparator", true);
        try {
            String underscored = publishEntry(handle, "Underscore separated title " + suffix);
            assertTrue(underscored.contains("underscore_separated_title"),
                    "an entry created after the switch must join words with underscores, got: "
                            + underscored);

            assertTrue(getAnonymously(hyphenated).contains("Hyphen separated title"),
                    "and the entry published beforehand must still be served at its "
                            + "original hyphenated permalink");
        } finally {
            setGlobalFlag("weblogentry.title.useUnderscoreSeparator", before);
            logout();
        }
    }

    // ---------------------------------------------------------------- fixture

    /** Creates a weblog owned by the seeded admin, on the default theme. */
    private String createWeblog() {
        String handle = "gcfg" + nonce();

        openPath("/roller-ui/createWeblog.rol");
        $("#name").should(visible).setValue("Global Config " + handle);
        $("#handle").setValue(handle);
        $("#emailAddress").setValue(handle + "@example.invalid");
        $("select[name='theme']").selectOptionByValue("journal");
        $("button[type='submit']").click();

        $("#messages").should(exist);
        return handle;
    }

    /** Publishes one entry and returns its permalink. */
    private String publishEntry(String handle, String title) {
        openPath("/roller-ui/authoring/entryAdd.rol?weblog=" + handle);
        $("#entry").should(exist);
        $("[name='bean.title']").setValue(title);
        Editor.setText("Body of " + title);
        $("button[formaction$='entryAdd!publish.rol']").click();

        $(PERMALINK).should(exist);
        String permalink = $(PERMALINK).getAttribute("href");
        assertNotNull(permalink, "publishing must expose the entry's permalink");
        return permalink;
    }

    private static File testImage() {
        URL resource = GlobalConfigMatrixIT.class.getResource("/hawk.jpg");
        assertNotNull(resource, "hawk.jpg must be on the it-selenium test classpath");
        try {
            return new File(resource.toURI());
        } catch (URISyntaxException e) {
            throw new IllegalStateException("Cannot resolve test image", e);
        }
    }

    private static String nonce() {
        return Long.toString(System.nanoTime(), 36);
    }
}
