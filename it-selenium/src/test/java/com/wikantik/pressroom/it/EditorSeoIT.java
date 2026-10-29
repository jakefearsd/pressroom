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

import com.codeborne.selenide.Condition;
import com.wikantik.pressroom.it.support.Editor;
import com.wikantik.pressroom.it.support.PressroomIT;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceAccessMode;
import org.junit.jupiter.api.parallel.ResourceLocks;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.openqa.selenium.WebElement;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;

import static com.codeborne.selenide.Condition.exist;
import static com.codeborne.selenide.Condition.visible;
import static com.codeborne.selenide.Selenide.$;
import static com.codeborne.selenide.Selenide.switchTo;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Closes the SEO loop end to end: what an author types into the editor's SEO
 * card must come out of the public permalink's {@code <head>}.
 *
 * <p>{@code SeoHeadRenderingTest} proves the {@code #showSeoHead} macro emits
 * the tags when the entity carries the fields; the unit controller tests prove
 * the fields survive the bean round-trip. Neither proves the editor UI can set
 * them in the first place — the accordion card, the media-chooser picker
 * callback and the hidden inputs are all JSP + JavaScript that only a browser
 * exercises. This test drives exactly that path: upload an image, write an
 * entry, fill every SEO field, pick the featured image through the chooser
 * modal, publish, and read the head tags back as an anonymous visitor.
 */
/*
 * Writes the shared weblog's media directory, and depends on `uploads.enabled`
 * staying true while it runs. The READ lock on GLOBAL_CONFIG is what keeps
 * GlobalConfigMatrixIT (which sets uploads.enabled=false) and ThemeMatrixIT
 * from flipping that flag mid-test -- with uploads off, the media page simply
 * does not render the buttons these tests look for, and the failure reads as
 * 'element not found' rather than as a race.
 */
@ResourceLocks({
        @ResourceLock(PressroomIT.SHARED_MEDIA),
        @ResourceLock(value = PressroomIT.GLOBAL_CONFIG, mode = ResourceAccessMode.READ)
})
class EditorSeoIT extends PressroomIT {

    private static final String ENTRY_ADD = "/pressroom-ui/authoring/entryAdd.rol?weblog=" + WEBLOG_HANDLE;
    private static final String MEDIA_ADD = "/pressroom-ui/authoring/mediaFileAdd.rol?weblog=" + WEBLOG_HANDLE;
    private static final String GLOBAL_CONFIG = "/pressroom-ui/admin/globalConfig.rol";

    /** Rendered on the edit page only once the entry is actually published. */
    private static final String PERMALINK = "#entry_bean_permalink";

    @BeforeEach
    void logIn() {
        loginAsAdmin();
    }

    @Test
    void seoFieldsSetInTheEditorReachThePublicHead() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String title = "IT Seo " + suffix;
        String metaTitle = "IT Seo Meta Title " + suffix;
        String metaDescription = "A hand-written snippet description " + suffix;
        String canonicalUrl = "https://canonical.example.com/original-" + suffix;

        enableUploads();
        String imageName = uploadImage("seo-" + suffix + ".jpg");

        // --- write the entry and fill the SEO card --------------------------
        openPath(ENTRY_ADD);
        $("#entry").should(exist);
        $("[name='bean.title']").setValue(title);
        Editor.setText("Body of the SEO journey entry " + suffix + ".");

        // The SEO card starts collapsed; open it the way an author would.
        $("a[data-bs-target='#collapseSeo']").click();
        $("#seo_metaTitle").should(visible).setValue(metaTitle);
        $("#seo_metaDescription").setValue(metaDescription);
        $("#seo_canonicalUrl").setValue(canonicalUrl);
        $("#seo_noindex").click();

        // The snippet preview must follow what was just typed.
        $("#seo_snippet_title").shouldHave(Condition.exactText(metaTitle));
        $("#seo_snippet_description").shouldHave(Condition.exactText(metaDescription));

        // --- pick the featured image through the media chooser --------------
        $("button[onclick=\"openImagePicker('featuredImage')\"]").click();
        $("#mediafile_edit_lightbox").shouldBe(visible);
        switchTo().frame("mediaFileEditor");
        chooserTile(imageName).should(exist).click();
        switchTo().defaultContent();

        // The callback must have filled the hidden id and shown the preview.
        $("#seo_featuredImageId").shouldHave(Condition.attributeMatching("value", ".+"));
        $("#seo_featuredImage_preview").shouldBe(visible);
        String previewSrc = $("#seo_featuredImage_preview").getAttribute("src");
        assertNotNull(previewSrc);
        assertTrue(previewSrc.contains("t=true"),
                "the picker preview must use the thumbnail rendition, got: " + previewSrc);

        // --- publish and read the head back as an anonymous visitor ---------
        $("button[formaction$='entryAdd!publish.rol']").click();
        $(PERMALINK).should(exist);
        String permalink = $(PERMALINK).getAttribute("href");
        assertNotNull(permalink);

        String page = getAnonymously(permalink);
        assertHeadContains(page, permalink, "property=\"og:title\" content=\"" + metaTitle + "\"");
        assertHeadContains(page, permalink, "name=\"description\" content=\"" + metaDescription + "\"");
        assertHeadContains(page, permalink,
                "property=\"og:description\" content=\"" + metaDescription + "\"");
        assertHeadContains(page, permalink, "rel=\"canonical\" href=\"" + canonicalUrl + "\"");
        assertHeadContains(page, permalink, "name=\"robots\" content=\"noindex\"");
        // No dedicated og:image was picked, so the featured image must back it,
        // served from the weblog's media-resource URL space.
        assertHeadContains(page, permalink, "property=\"og:image\"");
        assertHeadContains(page, permalink, "/mediaresource/");
    }

    /**
     * The structured-data half of the same loop. The event rows stay hidden
     * until the dropdown asks for them, which is jQuery in the page bottom that
     * no server-side test can exercise; and what comes out of the permalink
     * must be the typed object ALONGSIDE the BlogPosting one the page has
     * always carried.
     */
    @Test
    void aTravelTypeChosenInTheSeoCardPublishesAsTypedJsonLd() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String title = "IT Attraction " + suffix;
        String metaDescription = "The iron lady of Paris " + suffix;

        openPath(ENTRY_ADD);
        $("#entry").should(exist);
        $("[name='bean.title']").setValue(title);
        Editor.setText("Body of the structured-data entry " + suffix + ".");

        $("a[data-bs-target='#collapseSeo']").click();
        $("#seo_metaDescription").should(visible).setValue(metaDescription);

        // Coordinates are offered on any entry (they also centre a bare [map]);
        // the event rows are not, until the type asks for them.
        $("#seo_jsonldType").shouldBe(visible);
        $("#seo_row_geo").shouldBe(visible);
        $("#seo_row_event").shouldNotBe(visible);

        $("#seo_jsonldType").selectOptionByValue("TOURIST_ATTRACTION");
        $("#seo_row_event").shouldNotBe(visible);
        $("#seo_geoLatitude").setValue("48.8584");
        $("#seo_geoLongitude").setValue("2.2945");

        $("button[formaction$='entryAdd!publish.rol']").click();
        $(PERMALINK).should(exist);
        String permalink = $(PERMALINK).getAttribute("href");
        assertNotNull(permalink);

        String page = getAnonymously(permalink);
        assertHeadContains(page, permalink, "\"@type\":\"TouristAttraction\"");
        assertHeadContains(page, permalink, "\"@type\":\"GeoCoordinates\"");
        assertHeadContains(page, permalink, "\"latitude\":48.8584");
        assertHeadContains(page, permalink, "\"longitude\":2.2945");
        assertHeadContains(page, permalink, "\"description\":\"" + metaDescription + "\"");
        // and the block the permalink has always had is still there
        assertHeadContains(page, permalink, "\"@type\": \"BlogPosting\"");
        assertTrue(headOf(page).split("application/ld\\+json", -1).length - 1 == 2,
                "the head must carry both JSON-LD blocks. Head was:\n" + headOf(page));
    }

    /**
     * The other authoring image path: inserting an image into the entry BODY
     * through the media chooser. The insert snippet no longer pastes a raw
     * {@code <img ?t=true>} but the {@code [image id=..]} shortcode, and the
     * published page must expand it into a responsive {@code <picture>} with
     * the full srcset ladder -- authors get the rendition pipeline without
     * knowing it exists.
     */
    @Test
    void anImageInsertedThroughTheChooserPublishesAsAResponsivePicture() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String title = "IT Responsive " + suffix;

        enableUploads();
        String imageName = uploadImage("responsive-" + suffix + ".jpg");

        openPath(ENTRY_ADD);
        $("#entry").should(exist);
        $("[name='bean.title']").setValue(title);
        Editor.root();

        // insert via the toolbar's image control, as an author would
        $(Editor.MEDIA_INSERT).click();
        $("#mediafile_edit_lightbox").shouldBe(visible);
        switchTo().frame("mediaFileEditor");
        chooserTile(imageName).should(exist).click();
        switchTo().defaultContent();

        // the editor now holds the shortcode, not raw img markup
        Editor.root().shouldHave(Condition.text("[image id="));

        $("button[formaction$='entryAdd!publish.rol']").click();
        $(PERMALINK).should(exist);
        String permalink = $(PERMALINK).getAttribute("href");
        assertNotNull(permalink);

        String page = getAnonymously(permalink);
        assertTrue(page.contains("<picture>"),
                "the published body must carry the expanded responsive picture. Page:\n" + page);
        assertTrue(page.contains("<source type=\"image/webp\" srcset=\""),
                "webp-capable browsers must get a source element. Page:\n" + page);
        // hawk.jpg is 500px wide, so the 480 rung of the ladder must be offered.
        // The sanitizer entity-encodes "=" inside attribute values and pads the
        // srcset commas; the browser decodes both before choosing a candidate,
        // which is why the image on screen is correct and only a literal string
        // match would fail. (BrowserHealth would have failed this test outright
        // if any image had 404'd, so a passing run is also proof the encoded
        // URLs resolve.)
        String decodedPage = page.replace("&#61;", "=").replace(" , ", ", ");
        assertTrue(decodedPage.contains("?w=480 480w"),
                "the srcset must climb the rendition ladder. Page:\n" + page);
        assertTrue(page.contains("loading=\"lazy\""), page);
        assertFalse(page.contains("[image id="),
                "the shortcode text itself must never reach readers. Page:\n" + page);
    }

    /**
     * File uploads are globally disabled by default; the picker is useless
     * without something to pick. Flipping the runtime property through the
     * admin screen also keeps this journey on real UI end to end.
     */
    private void enableUploads() {
        openPath(GLOBAL_CONFIG);
        WebElement checkbox = $("input[name='uploads.enabled']").should(exist).toWebElement();
        if (!checkbox.isSelected()) {
            checkbox.click();
        }
        $("#saveButton").click();
        // The save re-renders the config page with a success banner.
        $("#messages").should(exist);
    }

    /**
     * Uploads the bundled test image through the media-file add form under a
     * run-unique name, and returns that name. The it_weblog media library is
     * shared by every IT in the run (MediaCropIT even crops a file in place),
     * so a test must only ever pick the tile it uploaded itself — a bare
     * "first .mediaObject" click can grab another suite's file.
     */
    private String uploadImage(String uniqueName) {
        File image = uniquelyNamedCopy(testImage(), uniqueName);
        openPath(MEDIA_ADD);
        $("#uploadedFiles").should(exist).uploadFile(image);
        $("#uploadButton").click();
        // Only the success page offers to create a post from the upload; an
        // error would render the upload form again with an error banner.
        $("button[formaction$='entryAddWithMediaFile.rol']").should(exist);
        return uniqueName;
    }

    /** The chooser tile for exactly the named media file. */
    private static com.codeborne.selenide.SelenideElement chooserTile(String imageName) {
        return $("div.mediaObject img[alt='" + imageName + "']");
    }

    /** Media file names come from the uploaded file's name, so copy the fixture. */
    private static File uniquelyNamedCopy(File original, String uniqueName) {
        try {
            Path dir = Files.createTempDirectory("pressroom-it-upload");
            dir.toFile().deleteOnExit();
            Path copy = dir.resolve(uniqueName);
            Files.copy(original.toPath(), copy);
            copy.toFile().deleteOnExit();
            return copy.toFile();
        } catch (IOException e) {
            throw new IllegalStateException("Cannot stage a uniquely named upload", e);
        }
    }

    private File testImage() {
        URL resource = getClass().getResource("/hawk.jpg");
        assertNotNull(resource, "hawk.jpg must be on the it-selenium test classpath");
        try {
            return new File(resource.toURI());
        } catch (URISyntaxException e) {
            throw new IllegalStateException("Cannot resolve test image", e);
        }
    }

    private static void assertHeadContains(String page, String permalink, String fragment) {
        assertTrue(page.contains(fragment),
                "expected the public page at " + permalink + " to contain [" + fragment
                        + "] but it did not. Head was:\n" + headOf(page));
    }

    private static String headOf(String page) {
        int end = page.indexOf("</head>");
        return end == -1 ? page.substring(0, Math.min(page.length(), 2000))
                : page.substring(0, end);
    }
}
