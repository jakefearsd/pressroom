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
import com.codeborne.selenide.SelenideElement;
import org.apache.roller.it.support.BrowserHealth;
import org.apache.roller.it.support.Editor;
import org.apache.roller.it.support.RollerIT;
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
import static com.codeborne.selenide.Condition.disappear;
import static com.codeborne.selenide.Condition.exist;
import static com.codeborne.selenide.Condition.text;
import static com.codeborne.selenide.Condition.value;
import static com.codeborne.selenide.Condition.visible;
import static com.codeborne.selenide.Selenide.$;
import static com.codeborne.selenide.Selenide.$$;
import static com.codeborne.selenide.Selenide.executeJavaScript;
import static com.codeborne.selenide.Selenide.switchTo;
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
        @ResourceLock(RollerIT.SHARED_MEDIA),
        @ResourceLock(value = RollerIT.GLOBAL_CONFIG, mode = ResourceAccessMode.READ)
})
class GalleryIT extends RollerIT {

    private static final String ENTRY_ADD = "/roller-ui/authoring/entryAdd.rol?weblog=" + WEBLOG_HANDLE;
    private static final String MEDIA_ADD = "/roller-ui/authoring/mediaFileAdd.rol?weblog=" + WEBLOG_HANDLE;
    private static final String MEDIA_VIEW = "/roller-ui/authoring/mediaFileView.rol?weblog=" + WEBLOG_HANDLE;
    private static final String GLOBAL_CONFIG = "/roller-ui/admin/globalConfig.rol";

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
        openPath(GLOBAL_CONFIG);
        WebElement checkbox = $("input[name='uploads.enabled']").should(exist).toWebElement();
        if (!checkbox.isSelected()) {
            checkbox.click();
        }
        $("#saveButton").click();
        $("#messages").should(exist);
    }

    /**
     * Uploads the bundled test image and returns the new media file's id,
     * read off the success page's image-selection checkbox.
     */
    private String uploadImage() {
        openPath(MEDIA_ADD);
        $("#uploadedFiles").should(exist).uploadFile(testImage());
        $("#uploadButton").click();
        // Only the success page offers to create a post from the upload.
        $("button[formaction$='entryAddWithMediaFile.rol']").should(exist);
        String mediaFileId = $("input[name='selectedImages']").should(exist).getValue();
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
        openPath(MEDIA_VIEW);
        // Marker for the re-submit dance below: onEditSuccess re-submits the
        // view form, replacing this document with a new one that lacks the
        // marker -- which is the only reliable arrival signal, since the URL
        // does not change.
        executeJavaScript("window.__renameMarker = true;");
        // the page's own tile-click handler: fills the modal's iframe and shows it
        executeJavaScript("onClickEdit(arguments[0], arguments[1]);", mediaFileId, name);
        $("#mediafile_edit_lightbox").shouldBe(visible);
        switchTo().frame("mediaFileEditor");

        // Wait for the iframe to hold THIS file's form before typing into it.
        // The modal is shown and the iframe's src set in the same handler, so
        // the frame can still be blank (or showing the previous file) when the
        // switch succeeds -- and a setValue into that is silently discarded,
        // which surfaces later as a rename that simply did not happen.
        $("input[name='bean.name']").shouldHave(value(ORIGINAL_UPLOAD_NAME));

        $("input[name='bean.name']").setValue(name);
        $("textarea[name='bean.description']").setValue(caption);
        // The iframe's document declares the same Plex webfonts as every admin
        // page; submitting the form navigates the IFRAME while its font fetch
        // can still be in flight, and Chrome reports that abort against the
        // top-level page. Wait out the iframe's fonts first (we are still
        // switched into the frame, so document.fonts here is the iframe's).
        waitForFontsReady();
        $("input[name='saveButton']").click();
        switchTo().defaultContent();

        // Wait for the modal to go before touching the top window. This one is
        // not an animation to be skipped: the iframe POSTs the rename, and only
        // its success page calls parent.onEditSuccess(), which is what hides
        // the modal. So the modal disappearing IS the evidence that the write
        // completed, and navigating before it would abandon the request. Given
        // generously long, because it spans a form POST on a loaded runner.
        $("#mediafile_edit_lightbox").should(disappear, Duration.ofSeconds(30));

        // onEditSuccess also re-submits the view form. That navigation is
        // still in flight and racing anything asserted against the current
        // page. Wait for the NEW document to arrive (the marker planted above
        // vanishes with the old one -- same URL, so nothing else signals it)
        // and for ITS fonts to finish, before navigating again: polling
        // document.fonts on the OLD document reports 'loaded' and would let
        // openPath cancel the incoming page's font requests, which is exactly
        // the ERR_ABORTED flake this dance exists to close.
        Selenide.Wait().withTimeout(Duration.ofSeconds(30)).until(d ->
                Boolean.TRUE.equals(executeJavaScript("return window.__renameMarker === undefined;")));
        waitForFontsReady();
        openPath(MEDIA_VIEW);
        BrowserHealth.current().settle();
        // Explicit timeout, not Selenide's 4s default: every other wait in
        // this method is already 30s because a loaded runner stretches the
        // POST-then-reload dance above, and this assertion is the one that
        // reads the result of it. It has failed in CI ("Element not found
        // {img[alt=...]}") on a run where the write itself demonstrably
        // succeeded -- the modal disappearing, waited for above, IS that
        // evidence -- so the listing simply had not painted yet.
        $("img[alt='" + name + "']").should(exist, Duration.ofSeconds(30));

        // Flake: mediaFileView.rol's own stylesheet pulls in the Plex webfont,
        // but @font-face only triggers the woff2 fetch once the browser lays
        // out text that uses it -- which can still be in flight after
        // settle()'s quiet period (a network event, not a DOM/layout one) has
        // already elapsed. The caller navigates away from this page right
        // after returning, and Chrome reports that as ERR_ABORTED on the font
        // request, which BrowserHealth.assertNoFailedRequests() rightly does
        // not excuse (fonts are declared by the document, not cancellable by
        // page script -- see BrowserHealth.ABORT_IS_EXPECTED_FOR). Wait for
        // the font to actually settle before leaving this page, rather than
        // widen that doctrine or the shared settle() every test pays for.
        waitForFontsReady();
    }

    /**
     * Blocks until every webfont the current page requested has finished
     * loading (or failed on its own, not by cancellation). Local to this test
     * on purpose -- see the flake comment at its one call site above.
     */
    private void waitForFontsReady() {
        Selenide.Wait().withTimeout(Duration.ofSeconds(10)).until(d ->
                Boolean.TRUE.equals(executeJavaScript("return document.fonts.status === 'loaded';")));
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
