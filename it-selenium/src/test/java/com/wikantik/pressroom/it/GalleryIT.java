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

import com.codeborne.selenide.Selenide;
import com.codeborne.selenide.SelenideElement;
import com.wikantik.pressroom.it.support.BrowserHealth;
import com.wikantik.pressroom.it.support.Editor;
import com.wikantik.pressroom.it.support.PagesMediaApp;
import com.wikantik.pressroom.it.support.PressroomIT;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceAccessMode;
import org.junit.jupiter.api.parallel.ResourceLocks;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.openqa.selenium.WebElement;

import java.io.File;
import java.time.Duration;
import java.net.URISyntaxException;
import java.net.URL;

import static com.codeborne.selenide.CollectionCondition.sizeGreaterThan;
import static com.codeborne.selenide.Condition.appear;
import static com.codeborne.selenide.Condition.attribute;
import static com.codeborne.selenide.Condition.exist;
import static com.codeborne.selenide.Condition.text;
import static com.codeborne.selenide.Condition.value;
import static com.codeborne.selenide.Condition.visible;
import static com.codeborne.selenide.Selenide.$;
import static com.codeborne.selenide.Selenide.$$;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Closes the gallery loop end to end in a real browser: upload an image,
 * caption it through the media-file editor, publish an entry containing the
 * {@code [gallery]} shortcode, and prove that the public permalink renders
 * the justified grid and that clicking a tile opens the PhotoSwipe lightbox
 * -- the one deliverable no server-side rendering test can cover, because
 * the lightbox is entirely {@code #showGalleryAssets} JavaScript executing
 * against the shortcode's markup contract ({@code .jgrid} anchors carrying
 * {@code data-pswp-*}/{@code data-caption}).
 *
 * <p>{@code GalleryRenderingTest} proves the markup reaches an anonymous
 * reader through the sanitizer; {@code GalleryAssetsRenderingTest} proves
 * every theme head ships the assets exactly once. This test is the browser
 * half: the ES modules actually load under the theme CSP, the click binds,
 * and the caption panel shows the author's description.
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
class GalleryIT extends PressroomIT {

    private static final String ENTRY_ADD = "/pressroom-ui/authoring/entryAdd.rol?weblog=" + WEBLOG_HANDLE;
    private static final String MEDIA_ADD = PagesMediaApp.mediaUpload(WEBLOG_HANDLE);
    private static final String MEDIA_VIEW = PagesMediaApp.media(WEBLOG_HANDLE);

    /** Rendered on the edit page only once the entry is actually published. */
    private static final String PERMALINK = "#entry_bean_permalink";

    /** The bundled fixture's own filename, before this test renames it. */
    private static final String ORIGINAL_UPLOAD_NAME = "hawk.jpg";

    @BeforeEach
    void logIn() {
        loginAsAdmin();
    }

    @Test
    void aPublishedGalleryOpensThePhotoSwipeLightboxWithItsCaption() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String imageName = "gallery-hawk-" + suffix + ".jpg";
        String caption = "A red-tailed hawk over the valley " + suffix;

        enableUploads();
        String mediaFileId = uploadImage();
        renameAndDescribe(mediaFileId, imageName, caption);

        // --- author and publish the gallery entry ---------------------------
        openPath(ENTRY_ADD);
        $("#entry").should(exist);
        $("[name='bean.title']").setValue("IT Gallery " + suffix);
        Editor.setText("<p>Shot log " + suffix + "</p><p>[gallery dir=\"default\"]</p>");
        $("button[formaction$='entryAdd!publish.rol']").click();
        $(PERMALINK).should(exist);
        String permalink = $(PERMALINK).getAttribute("href");
        assertNotNull(permalink);

        // --- the public page renders the justified grid ---------------------
        Selenide.open(permalink);
        BrowserHealth.current().settle();
        $$(".jgrid figure").shouldHave(sizeGreaterThan(0));

        // this run's image, found by its unique caption payload; the default
        // directory accumulates uploads from other tests in the same suite
        SelenideElement tile = $$(".jgrid a")
                .findBy(attribute("data-caption", caption))
                .should(exist);
        tile.$("img").should(exist);
        tile.parent().$("figcaption").shouldHave(text(caption));

        // --- clicking the tile opens PhotoSwipe -----------------------------
        tile.click();
        $(".pswp").should(appear);
        $(".pswp__img").should(exist);
        $(".pswp__custom-caption").shouldBe(visible).shouldHave(text(caption));
    }

    /**
     * File uploads are globally disabled by default; flipping the runtime
     * property through the admin screen keeps this journey on real UI.
     */
    private void enableUploads() {
        openGlobalConfig();
        WebElement checkbox = $("input[name='uploads.enabled']").should(exist).toWebElement();
        if (!checkbox.isSelected()) {
            checkbox.click();
        }
        clickAndAwaitSavedMessage($("#saveButton"));
    }

    /**
     * Uploads the bundled test image and returns the new media file's id,
     * read off the success page's image-selection checkbox.
     */
    private String uploadImage() {
        openPath(MEDIA_ADD);
        $("#uploadedFiles").should(exist).uploadFile(testImage());
        $("#uploadButton").click();
        // Only the success step offers to create a post from the upload.
        $("#createPostButton").should(exist);
        String mediaFileId = $("#entry input[type='checkbox']").should(exist).getValue();
        assertNotNull(mediaFileId, "the upload success page must carry the new file's id");
        return mediaFileId;
    }

    /**
     * Renames and captions the uploaded file through the media-view edit
     * modal, exactly as an author would. The rename matters twice over: the
     * save-time duplicate-name validation would reject a second "hawk.jpg"
     * in the default directory (other suites upload the same fixture), and
     * the unique caption is what later identifies this run's tile in the
     * public grid. The description is what {@code GalleryShortcode} turns
     * into the figcaption and {@code data-caption} the lightbox displays.
     */
    private void renameAndDescribe(String mediaFileId, String name, String caption) {
        openPath(MEDIA_VIEW + "/" + mediaFileId);
        // The edit screen is a page of its own: wait for THIS file's form before typing into it.
        $("#mfedit_bean_name").shouldHave(value(ORIGINAL_UPLOAD_NAME));
        $("#mfedit_bean_name").setValue(name);
        $("#mfedit_bean_description").setValue(caption);
        $("button[name='saveButton']").click();

        // The save returns to the library with the news; the renamed tile is in it.
        $("#messages").should(exist, Duration.ofSeconds(30));
        $("img[alt='" + name + "']").should(exist, Duration.ofSeconds(30));
        BrowserHealth.current().settle();
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
}
