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
import com.codeborne.selenide.WebDriverRunner;
import com.codeborne.selenide.SelenideElement;
import com.wikantik.pressroom.it.support.Editor;
import com.wikantik.pressroom.it.support.PressroomIT;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceAccessMode;
import org.junit.jupiter.api.parallel.ResourceLocks;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.io.TempDir;
import org.openqa.selenium.WebElement;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;

import static com.codeborne.selenide.CollectionCondition.size;
import static com.codeborne.selenide.Condition.disappear;
import static com.codeborne.selenide.Condition.exist;
import static com.codeborne.selenide.Condition.text;
import static com.codeborne.selenide.Condition.value;
import static com.codeborne.selenide.Condition.visible;
import static com.codeborne.selenide.Selenide.$;
import static com.codeborne.selenide.Selenide.$$;
import static com.codeborne.selenide.Selenide.executeJavaScript;
import static com.codeborne.selenide.Selenide.switchTo;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Browser proof for the W4 media wave: bulk upload (one POST, several files
 * through the {@code multiple} {@code #uploadedFiles} input and its drop
 * zone) and alt text (stored on {@code MediaFile}, edited on
 * {@code MediaFileEdit.jsp}'s {@code bean.altText} field, rendered into both
 * shortcode emission points, and flagged on the grid when missing).
 *
 * <p>{@code GalleryIT} and {@code MediaCropIT} already close the
 * upload-to-public-page loop for captions and crops; this test follows the
 * same conventions (fixtures, login, the {@code onClickEdit}/iframe dance)
 * for the two features Tasks 3 and 4 added. {@code [image]} and
 * {@code [gallery]} are separate emission points ({@link
 * com.wikantik.pressroom.business.shortcodes.ImageShortcode} vs.
 * {@link com.wikantik.pressroom.business.shortcodes.GalleryMarkup}), so
 * alt text reaching one says nothing about the other -- both are proved here.
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
class MediaBulkUploadIT extends PressroomIT {

    private static final String ENTRY_ADD = "/roller-ui/authoring/entryAdd.rol?weblog=" + WEBLOG_HANDLE;
    private static final String MEDIA_ADD = "/roller-ui/authoring/mediaFileAdd.rol?weblog=" + WEBLOG_HANDLE;
    private static final String MEDIA_VIEW = "/roller-ui/authoring/mediaFileView.rol?weblog=" + WEBLOG_HANDLE;
    private static final String GLOBAL_CONFIG = "/roller-ui/admin/globalConfig.rol";

    /** Rendered on the edit page only once the entry is actually published. */
    private static final String PERMALINK = "#entry_bean_permalink";

    @TempDir
    Path tempDir;

    @BeforeEach
    void logIn() {
        loginAsAdmin();
    }

    @Test
    void threeFilesUploadInOnePost() throws IOException {
        String suffix = uniqueSuffix();
        String nameA = "bulk-a-" + suffix + ".png";
        String nameB = "bulk-b-" + suffix + ".jpg";
        String nameC = "bulk-c-" + suffix + ".png";

        enableUploads();
        File a = newImageFile(nameA, 40, 30, "png");
        File b = newImageFile(nameB, 40, 30, "jpg");
        File c = newImageFile(nameC, 40, 30, "png");

        openPath(MEDIA_ADD);
        // One sendKeys of a newline-separated path list to the `multiple`
        // input -- what Selenide's uploadFile(File...) does under the hood --
        // then a single click of #uploadButton: one POST, three files.
        $("#uploadedFiles").should(exist).uploadFile(a, b, c);
        $("#uploadButton").click();

        // The success page offers one checkbox per uploaded image; three
        // rows for one POST is the proof this was a bulk upload, not three
        // separate round trips.
        $$("input[name='selectedImages']").shouldHave(size(3));

        openPath(MEDIA_VIEW);
        $("img[alt='" + nameA + "']").should(exist);
        $("img[alt='" + nameB + "']").should(exist);
        $("img[alt='" + nameC + "']").should(exist);
    }

    /**
     * The success page's way out ("Done"; it read "Cancel" until the upload
     * having already happened made that word wrong) must return to the media
     * view.
     *
     * <p>It used to be a typeless {@code <button onclick='window.load(url)'>}
     * inside an action-less POST form, so it threw a TypeError and then
     * submitted the form back to the upload endpoint -- the opposite of
     * cancelling. Only a click proves this; the page renders fine either way,
     * which is why the bug survived a passing IT suite.
     */
    @Test
    void doneOnTheSuccessPageReturnsToTheMediaView() throws IOException {
        String name = "done-" + System.nanoTime() + ".png";

        enableUploads();
        openPath(MEDIA_ADD);
        $("#uploadedFiles").should(exist).uploadFile(newImageFile(name, 20, 20, "png"));
        $("#uploadButton").click();
        $$("input[name='selectedImages']").shouldHave(size(1));

        // Found by TEXT among both element types inside the form, deliberately.
        // An earlier version of this test selected a[href*='mediaFileView.rol']
        // page-wide, which matched a nav link in the surrounding chrome -- it
        // passed against the broken page by clicking something else entirely.
        // Matching either <a> or <button> keeps the assertion about behaviour
        // (does Done navigate?) rather than markup, which the unit test pins.
        clickAndAwaitNewPage($$("#entry a, #entry button").findBy(text("Done")).should(exist));

        assertTrue(WebDriverRunner.url().contains("mediaFileView.rol"),
                "Done must land on the media view, not re-post the upload; was: "
                        + WebDriverRunner.url());
        $("img[alt='" + name + "']").should(exist);
    }

    @Test
    void altTextReachesTheRenderedPage() throws IOException {
        String suffix = uniqueSuffix();
        String imageName = "alt-image-" + suffix + ".jpg";
        String altText = "A red kite riding the thermal " + suffix;

        enableUploads();
        String mediaFileId = uploadOneImage(imageName);
        setAltText(mediaFileId, imageName, altText);

        // --- author and publish an entry carrying [image id=...] -----------
        openPath(ENTRY_ADD);
        $("#entry").should(exist);
        $("[name='bean.title']").setValue("IT Alt Image " + suffix);
        Editor.setText("<p>Alt text entry " + suffix + "</p><p>[image id=" + mediaFileId + "]</p>");
        $("button[formaction$='entryAdd!publish.rol']").click();
        $(PERMALINK).should(exist);
        String permalink = $(PERMALINK).getAttribute("href");
        assertNotNull(permalink);

        // --- the public page's <img> must carry the alt text, not the name --
        String page = getAnonymously(permalink);
        assertTrue(page.contains("alt=\"" + altText + "\""),
                "the published page must carry the stored alt text. Page:\n" + page);
        assertFalse(page.contains(imageName),
                "the published page must not fall back to the filename once alt text is set. Page:\n" + page);
    }

    /**
     * Same journey as {@link #altTextReachesTheRenderedPage()}, through
     * {@code [gallery dir="default"]} instead of {@code [image id=...]}.
     * {@code GalleryMarkup} is a second, independent emitter of the {@code
     * <img alt="...">} attribute and has been wrong on its own before, so
     * this is not redundant with the {@code [image]} case above.
     */
    @Test
    void altTextReachesAGallery() throws IOException {
        String suffix = uniqueSuffix();
        String imageName = "gallery-alt-" + suffix + ".jpg";
        String altText = "A gallery view of the coastline " + suffix;

        enableUploads();
        String mediaFileId = uploadOneImage(imageName);
        setAltText(mediaFileId, imageName, altText);

        openPath(ENTRY_ADD);
        $("#entry").should(exist);
        $("[name='bean.title']").setValue("IT Gallery Alt " + suffix);
        Editor.setText("<p>Gallery alt entry " + suffix + "</p><p>[gallery dir=\"default\"]</p>");
        $("button[formaction$='entryAdd!publish.rol']").click();
        $(PERMALINK).should(exist);
        String permalink = $(PERMALINK).getAttribute("href");
        assertNotNull(permalink);

        String page = getAnonymously(permalink);
        assertTrue(page.contains("alt=\"" + altText + "\""),
                "the gallery markup must carry the stored alt text. Page:\n" + page);
        assertFalse(page.contains(imageName),
                "the gallery markup must not fall back to the filename once alt text is set. Page:\n" + page);
    }

    @Test
    void theGridMarksAnImageWithNoAltText() throws IOException {
        String suffix = uniqueSuffix();
        String imageName = "altmissing-" + suffix + ".jpg";
        String altText = "A ridge line at dusk " + suffix;

        enableUploads();
        String mediaFileId = uploadOneImage(imageName);

        // --- fresh upload, no alt text yet: the grid must flag it ----------
        openPath(MEDIA_VIEW);
        gridRowFor(mediaFileId).$(".media-alt-missing").should(exist);

        // --- once alt text is saved, the marker must go away ----------------
        setAltText(mediaFileId, imageName, altText);
        openPath(MEDIA_VIEW);
        gridRowFor(mediaFileId).$(".media-alt-missing").shouldNot(exist);
    }

    /**
     * A filename carrying an apostrophe used to make a grid tile permanently
     * unclickable: the tile's onclick was built by string-concatenating
     * {@code fn:escapeXml(mediaFile.name)} into
     * {@code onclick="onClickEdit('id','name')"}, and {@code fn:escapeXml}
     * renders {@code '} as {@code &#039;} -- which the HTML parser decodes
     * back to {@code '} before the attribute is compiled as JavaScript,
     * producing a syntax error. {@code onClickEdit} is the only route to the
     * alt-text field, so an image with this kind of name could never be
     * described. This is an entirely ordinary filename (a possessive), not a
     * crafted payload.
     */
    @Test
    void aFilenameContainingAnApostropheCanStillBeGivenAltText() throws IOException {
        String suffix = uniqueSuffix();
        String imageName = "Maiia's portrait " + suffix + ".jpg";
        String altText = "A portrait with soft window light " + suffix;

        enableUploads();
        String mediaFileId = uploadOneImage(imageName);
        setAltText(mediaFileId, imageName, altText);

        openPath(MEDIA_VIEW);
        gridRowFor(mediaFileId).$(".media-alt-missing").shouldNot(exist);
    }

    /**
     * The {@code <li>} row of the media grid holding the tile with this media
     * file id. Keyed off {@code data-media-file-id} (see the click binding in
     * {@code MediaFileView.jsp}) rather than the rendered {@code alt} text: an
     * {@code img[alt='...']} selector built by string concatenation cannot
     * name a filename containing an apostrophe (it terminates the CSS
     * attribute value early, exactly the class of bug this file's apostrophe
     * test exists to catch) and would not disambiguate two tiles that happen
     * to share a name. {@code div.mediaObject} carries the id and is itself a
     * child of the row {@code <li>}.
     */
    private SelenideElement gridRowFor(String mediaFileId) {
        return $(".mediaObject[data-media-file-id='" + mediaFileId + "']").should(exist).parent();
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
     * Uploads one run-uniquely-named image and returns the new media file's
     * id, read off the success page's image-selection checkbox -- same
     * convention as {@code GalleryIT.uploadImage()}.
     */
    private String uploadOneImage(String fileName) throws IOException {
        File image = newImageFile(fileName, 60, 40, "jpg");
        openPath(MEDIA_ADD);
        $("#uploadedFiles").should(exist).uploadFile(image);
        $("#uploadButton").click();
        $("button[formaction$='entryAddWithMediaFile.rol']").should(exist);
        String mediaFileId = $("input[name='selectedImages']").should(exist).getValue();
        assertNotNull(mediaFileId, "the upload success page must carry the new file's id");
        return mediaFileId;
    }

    /**
     * Sets the media file's alt text through the media-view edit modal,
     * exactly as an author would: clicking the grid tile itself (not
     * {@code executeJavaScript("onClickEdit(...)")}, which would bypass the
     * tile's click wiring entirely and could not have caught the
     * apostrophe-breaks-the-handler bug this method now exercises for every
     * caller) via its {@code data-media-file-id} attribute, which is exact
     * regardless of what the file is named.
     */
    private void setAltText(String mediaFileId, String imageName, String altText) {
        openPath(MEDIA_VIEW);
        // Marker for the re-submit dance below: onEditSuccess re-submits the
        // view form, replacing this document with a new one that lacks the
        // marker -- which is the only reliable arrival signal, since the URL
        // does not change.
        executeJavaScript("window.__altMarker = true;");
        $(".mediaObject[data-media-file-id='" + mediaFileId + "']").should(exist).click();
        $("#mediafile_edit_lightbox").shouldBe(visible);
        switchTo().frame("mediaFileEditor");

        // Wait for the iframe to hold THIS file's form before typing into it;
        // see GalleryIT.renameAndDescribe() for why this matters.
        $("input[name='bean.name']").shouldHave(value(imageName));
        $("input[name='bean.altText']").setValue(altText);

        waitForFontsReady();
        $("input[name='saveButton']").click();
        switchTo().defaultContent();

        $("#mediafile_edit_lightbox").should(disappear, Duration.ofSeconds(30));

        Selenide.Wait().withTimeout(Duration.ofSeconds(30)).until(d ->
                Boolean.TRUE.equals(executeJavaScript("return window.__altMarker === undefined;")));
        waitForFontsReady();
    }

    /**
     * Blocks until every webfont the current page requested has finished
     * loading (or failed on its own, not by cancellation). See {@code
     * GalleryIT.waitForFontsReady()} for why this matters around the
     * edit-modal iframe's navigation.
     */
    private void waitForFontsReady() {
        Selenide.Wait().withTimeout(Duration.ofSeconds(10)).until(d ->
                Boolean.TRUE.equals(executeJavaScript("return document.fonts.status === 'loaded';")));
    }

    /**
     * A small, real PNG/JPEG under a run-unique name in the JUnit temp dir --
     * a zero-byte file would either be rejected by the upload pipeline or
     * skip the image path (dimension extraction, thumbnail, BlurHash)
     * entirely, which would make the alt-text and grid-marker tests assert
     * nothing.
     */
    private File newImageFile(String fileName, int width, int height, String formatName) throws IOException {
        BufferedImage image = new BufferedImage(width, height,
                "png".equals(formatName) ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(Color.BLUE);
        g.fillRect(0, 0, width, height);
        g.dispose();
        File file = tempDir.resolve(fileName).toFile();
        ImageIO.write(image, formatName, file);
        return file;
    }

    private static String uniqueSuffix() {
        return Long.toString(System.nanoTime(), 36);
    }
}
