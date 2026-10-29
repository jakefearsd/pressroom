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
import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.codeborne.selenide.Selenide;
import com.wikantik.pressroom.it.support.Editor;
import com.wikantik.pressroom.it.support.PressroomIT;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceAccessMode;
import org.junit.jupiter.api.parallel.ResourceLocks;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.openqa.selenium.WebElement;

import static com.codeborne.selenide.Condition.exist;
import static com.codeborne.selenide.Condition.visible;
import static com.codeborne.selenide.Selenide.$;
import static com.codeborne.selenide.Selenide.executeJavaScript;
import static com.codeborne.selenide.Selenide.switchTo;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The crop journey end to end: upload an image, open it in the media editor's
 * lightbox, apply a crop through the Cropper.js UI (accepting the destructive
 * confirm), and prove the PUBLIC page serves the new, smaller dimensions.
 *
 * <p>{@code MediaFileCropTest} proves the manager re-encodes and regenerates
 * everything; the controller test proves the action wiring. What only a
 * browser can prove is the JSP + Cropper.js custom-element layer itself: the
 * selection drawn on the displayed image must reach the server as a sensible
 * pixel rectangle, and the re-rendered editor and the published page must
 * both tell the same story about the new size.
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
class MediaCropIT extends PressroomIT {

    private static final String ENTRY_ADD = "/pressroom-ui/authoring/entryAdd.rol?weblog=" + WEBLOG_HANDLE;
    private static final String MEDIA_ADD = "/pressroom-ui/authoring/mediaFileAdd.rol?weblog=" + WEBLOG_HANDLE;
    private static final String MEDIA_VIEW = "/pressroom-ui/authoring/mediaFileView.rol?weblog=" + WEBLOG_HANDLE;
    private static final String GLOBAL_CONFIG = "/pressroom-ui/admin/globalConfig.rol";

    /** Rendered on the edit page only once the entry is actually published. */
    private static final String PERMALINK = "#entry_bean_permalink";

    /** "&lt;b&gt;Image&lt;/b&gt;: 450 X 336 pixels" as rendered text. */
    private static final Pattern DIMENSIONS = Pattern.compile("Image\\s*:\\s*(\\d+) X (\\d+) pixels");

    /**
     * CI runners (Azure) are materially slower than a dev box: the cropper's
     * custom elements have to be parsed, upgraded and laid out, and the crop
     * POST re-encodes and regenerates renditions server-side before the
     * reload settles. 10s was tight even locally; give both phases real
     * headroom rather than chase flakiness with a bigger number later.
     */
    private static final Duration CI_TOLERANT_TIMEOUT = Duration.ofSeconds(30);

    @BeforeEach
    void logIn() {
        loginAsAdmin();
    }

    @Test
    void croppingThroughTheEditorChangesThePublishedDimensions() {
        String suffix = Long.toString(System.nanoTime(), 36);

        enableUploads();
        String imageName = uploadImage("crop-" + suffix + ".jpg");

        // --- open the freshly uploaded file in the edit lightbox ------------
        openPath(MEDIA_VIEW);
        $("div.mediaObject img[alt='" + imageName + "']").should(exist).click();
        $("#mediafile_edit_lightbox").shouldBe(visible);
        switchTo().frame("mediaFileEditor");

        // the crop section is the new admin surface; its title is the marker
        $("#cropSectionTitle").should(exist);
        $("#cropCanvas").should(exist);
        String mediaFileId = $("#mediaFileId").getAttribute("value");
        assertNotNull(mediaFileId);
        assertFalse(mediaFileId.isBlank(), "the editor must know which file it edits");

        // hawk.jpg is 500x373; remember what the editor says before the crop
        int[] before = displayedDimensions();

        // Cropper.js v2 ships as custom elements (<cropper-image>,
        // <cropper-selection>, ...): the browser has to parse and execute
        // cropper.min.js and run each element's upgrade reaction before any
        // of the cropper's own properties (like $change on a selection)
        // exist. Wait for that distinctly from geometry so a script-loading
        // regression fails with a clear signal instead of a geometry
        // timeout that looks like a layout problem.
        Selenide.Wait().withTimeout(CI_TOLERANT_TIMEOUT).until(d ->
                Boolean.TRUE.equals(executeJavaScript(
                        "return !!(window.customElements"
                        + " && customElements.get('cropper-image')"
                        + " && customElements.get('cropper-selection')"
                        + " && customElements.get('cropper-canvas'));")));

        // The cropper only has a usable geometry once the lightbox iframe has
        // a layout box of its own and the image behind the canvas has decoded.
        Selenide.Wait().withTimeout(CI_TOLERANT_TIMEOUT)
                .withMessage(() -> "the crop surface never got a geometry: " + cropperGeometry())
                .until(d -> Boolean.TRUE.equals(executeJavaScript(
                        "var c = document.getElementById('cropCanvas');"
                        + "var i = document.getElementById('cropImage');"
                        + "return !!c && !!i && c.getBoundingClientRect().width > 0"
                        + " && i.getBoundingClientRect().width > 0;")));

        // ...and a real default selection to start from, which is what a user
        // would drag: see MediaFileEdit.jsp's seedSelectionOnceLaidOut and
        // {@link #theCropperInitialisesEvenWhenTheEditorLoadsBeforeTheLightboxIsShown}.
        Selenide.Wait().withTimeout(CI_TOLERANT_TIMEOUT)
                .withMessage(() -> "the cropper never got a default selection: " + cropperGeometry())
                .until(d -> Boolean.TRUE.equals(executeJavaScript(
                        "var s = document.getElementById('cropSelection');"
                        + "return !!s && s.width > 0 && s.height > 0;")));

        // Steer the selection to a modest trim (10px off each edge in natural
        // pixels) instead of the default coverage. The file is this test's own
        // uniquely named upload, so the exact trim only has to keep the
        // dimensions assertively smaller than the original.
        //
        // Driven through the component's own API rather than by simulating a
        // pointer drag over the cropper's shadow DOM: the API call is exact
        // and reproducible, and everything this test is really about - the
        // page's selection-to-natural-pixel maths, the POST, the server-side
        // re-encode and the published dimensions - runs identically either
        // way. Dragging the handles by hand is covered by manual UX testing.
        executeJavaScript(
                "var i = document.getElementById('cropImage');"
                + "var c = document.getElementById('cropCanvas');"
                + "var s = document.getElementById('cropSelection');"
                + "var ir = i.getBoundingClientRect(), cr = c.getBoundingClientRect();"
                + "var sx = ir.width / arguments[0], sy = ir.height / arguments[1];"
                + "s.$change((ir.left - cr.left) + 10 * sx, (ir.top - cr.top) + 10 * sy,"
                + "          (arguments[0] - 20) * sx, (arguments[1] - 20) * sy);",
                before[0], before[1]);

        assertTrue(Boolean.TRUE.equals(executeJavaScript(
                        "var s = document.getElementById('cropSelection');"
                        + "return s.width > 0 && s.height > 0;")),
                "the steered selection must be a real rectangle: " + cropperGeometry());

        // --- apply the crop, accepting the destructive confirm --------------
        $("#cropButton").click();
        Selenide.confirm();

        // The form posts and reloads inside the iframe: the crop itself
        // re-encodes the file and regenerates every rendition server-side,
        // which is the slowest step in the journey on a cold CI runner.
        // Re-entering the frame immediately can also race the reload itself
        // -- the old document may already be tearing down (a stale element
        // or "no such frame" from the driver) before the new one is ready --
        // so retry the switch itself rather than switching once and trusting
        // Selenide's element polling to paper over a torn-down frame.
        reenterFrameAfterPost("mediaFileEditor", "#messages", CI_TOLERANT_TIMEOUT);

        int[] after = displayedDimensions();
        assertTrue(after[0] < before[0] && after[1] < before[1],
                "the crop must shrink the stored dimensions, got " + before[0] + "x"
                        + before[1] + " -> " + after[0] + "x" + after[1]);
        switchTo().defaultContent();

        // --- publish an entry embedding the cropped image --------------------
        openPath(ENTRY_ADD);
        $("#entry").should(exist);
        $("[name='bean.title']").setValue("IT Crop " + suffix);
        Editor.setText("<p>Cropped image entry " + suffix + " [image id=" + mediaFileId + "]</p>");

        $("button[formaction$='entryAdd!publish.rol']").click();
        $(PERMALINK).should(exist);
        String permalink = $(PERMALINK).getAttribute("href");
        assertNotNull(permalink);

        // --- the public page must serve the cropped dimensions ---------------
        String page = getAnonymously(permalink);
        assertTrue(page.contains("width=\"" + after[0] + "\" height=\"" + after[1] + "\""),
                "the published page must declare the cropped intrinsic dimensions "
                        + after[0] + "x" + after[1] + ". Page:\n" + page);
        assertFalse(page.contains("width=\"" + before[0] + "\" height=\"" + before[1] + "\""),
                "the pre-crop dimensions must be gone from the published page");
    }

    /**
     * The lightbox points the editor iframe at its URL and only then fades the
     * modal in, so the editor document can finish loading - and upgrade its
     * cropper custom elements - while that iframe still has no layout box at
     * all. Cropper.js v2 sizes its {@code initial-coverage} selection exactly
     * once, at upgrade time, from {@code cropper-canvas.offsetWidth}, and never
     * recomputes it: losing that race left the user looking at a permanently
     * 0x0 selection and a Crop button that silently did nothing, because the
     * page's own submit guard drops a zero-area rectangle.
     *
     * <p>Which side wins is pure scheduling luck - about 100ms of margin on a
     * dev box, and CI lost it - so this test forces the losing order instead of
     * hoping for it: load the editor with the modal still {@code display:none},
     * and only reveal it afterwards.
     */
    @Test
    void theCropperInitialisesEvenWhenTheEditorLoadsBeforeTheLightboxIsShown() {
        String suffix = Long.toString(System.nanoTime(), 36);

        enableUploads();
        String imageName = uploadImage("crophidden-" + suffix + ".jpg");

        openPath(MEDIA_VIEW);
        String mediaFileId = mediaFileIdOf(imageName);

        executeJavaScript(
                "window.__editorLoaded = false;"
                + "var frame = document.getElementById('mediaFileEditor');"
                + "frame.onload = function () { window.__editorLoaded = true; };"
                + "frame.src = arguments[0];",
                baseUrl() + "/pressroom-ui/authoring/mediaFileEdit.rol?weblog=" + WEBLOG_HANDLE
                        + "&mediaFileId=" + mediaFileId);
        Selenide.Wait().withTimeout(CI_TOLERANT_TIMEOUT)
                .withMessage(() -> "the hidden editor iframe never loaded")
                .until(d -> Boolean.TRUE.equals(
                        executeJavaScript("return window.__editorLoaded === true;")));

        // Only now does the crop canvas get a size at all.
        executeJavaScript("bootstrap.Modal.getOrCreateInstance("
                + "document.getElementById('mediafile_edit_lightbox')).show();");
        $("#mediafile_edit_lightbox").shouldBe(visible);

        switchTo().frame("mediaFileEditor");
        $("#cropCanvas").should(exist);
        Selenide.Wait().withTimeout(CI_TOLERANT_TIMEOUT)
                .withMessage(() -> "the cropper never recovered a default selection after the"
                        + " lightbox appeared: " + cropperGeometry())
                .until(d -> Boolean.TRUE.equals(executeJavaScript(
                        "var s = document.getElementById('cropSelection');"
                        + "return !!s && s.width > 0 && s.height > 0;")));
        switchTo().defaultContent();
    }

    /**
     * Reads the media file's id off the gallery tile.
     *
     * <p>It used to be scraped out of an {@code onclick="onClickEdit('id','name')"}
     * attribute. That attribute is gone: building a JS call out of a filename
     * meant a name containing an apostrophe produced a syntax error and a dead
     * tile, so the id and name moved to {@code data-} attributes with a
     * delegated listener. Reading the data attribute is also simply what this
     * helper always wanted.
     */
    private String mediaFileIdOf(String imageName) {
        String id = $("div.mediaObject img[alt='" + imageName + "']")
                .should(exist).parent().getAttribute("data-media-file-id");
        assertNotNull(id, "the gallery tile must carry data-media-file-id");
        return id;
    }

    /**
     * A live snapshot of the crop surface, reported when a readiness wait times
     * out. A bare "condition was false after 30s" says nothing about whether
     * the canvas, the image or the selection is the one that never arrived,
     * which is precisely what a CI-only failure needs to say for itself.
     */
    private String cropperGeometry() {
        try {
            return executeJavaScript(
                    "var c = document.getElementById('cropCanvas');"
                    + "var i = document.getElementById('cropImage');"
                    + "var s = document.getElementById('cropSelection');"
                    + "var ir = i ? i.getBoundingClientRect() : null;"
                    + "return 'canvas=' + (c ? c.offsetWidth + 'x' + c.offsetHeight : 'missing')"
                    + " + ' image=' + (ir ? Math.round(ir.width) + 'x' + Math.round(ir.height) : 'missing')"
                    + " + ' selection=' + (s ? s.x + ',' + s.y + ' ' + s.width + 'x' + s.height : 'missing')"
                    + " + ' upgraded=' + !!(window.customElements"
                    + "     && customElements.get('cropper-selection'));");
        } catch (RuntimeException e) {
            return "(could not be read: " + e + ")";
        }
    }

    /** Parses the editor's "Image: W X H pixels" file-info line. */
    private int[] displayedDimensions() {
        String text = $("#entry").should(exist).getText();
        Matcher matcher = DIMENSIONS.matcher(text);
        assertTrue(matcher.find(), "expected image dimensions in the editor text, got:\n" + text);
        return new int[] {Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2))};
    }

    /**
     * Switches into {@code frameName} and waits for {@code readySelector} to
     * exist, retrying the switch itself rather than switching once and
     * relying on Selenide's element polling. A frame that is mid-reload -
     * exactly the state right after an in-frame POST - can make even the
     * switch itself throw ({@code NoSuchFrameException} while the old
     * document is torn down, {@code StaleElementReferenceException} against
     * a reference from before the reload), and those must be treated as
     * "not ready yet", not as a hard failure.
     */
    private void reenterFrameAfterPost(String frameName, String readySelector, Duration timeout) {
        Selenide.Wait().withTimeout(timeout).until(d -> {
            try {
                switchTo().defaultContent();
                switchTo().frame(frameName);
                return $(readySelector).exists();
            } catch (org.openqa.selenium.NoSuchFrameException | org.openqa.selenium.StaleElementReferenceException
                    | org.openqa.selenium.NoSuchElementException e) {
                return false;
            }
        });
        $(readySelector).should(exist);
    }

    /**
     * File uploads are globally disabled by default; there is nothing to crop
     * until one succeeds. Flipping the runtime property through the admin
     * screen keeps the journey on real UI end to end (same as EditorSeoIT).
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
     * Uploads the bundled test image under a run-unique name and returns that
     * name. The crop destroys the file's original pixels, and the it_weblog
     * media library is shared by every IT in the run — cropping a bare
     * "hawk.jpg" could shrink the file another suite is about to pick from
     * the chooser (EditorSeoIT asserts the 480w srcset rung, which a 480px
     * crop of the 500px fixture no longer produces).
     */
    private String uploadImage(String uniqueName) {
        File image = uniquelyNamedCopy(testImage(), uniqueName);
        openPath(MEDIA_ADD);
        $("#uploadedFiles").should(exist).uploadFile(image);
        $("#uploadButton").click();
        $("button[formaction$='entryAddWithMediaFile.rol']").should(exist);
        return uniqueName;
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
}
