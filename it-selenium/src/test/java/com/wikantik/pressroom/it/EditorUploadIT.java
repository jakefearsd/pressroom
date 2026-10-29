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

import java.time.Duration;

import com.codeborne.selenide.Selenide;
import com.wikantik.pressroom.it.support.Editor;
import com.wikantik.pressroom.it.support.PressroomIT;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceAccessMode;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.ResourceLocks;
import org.openqa.selenium.WebElement;

import static com.codeborne.selenide.Condition.exist;
import static com.codeborne.selenide.Condition.visible;
import static com.codeborne.selenide.Selenide.$;
import static com.codeborne.selenide.Selenide.executeJavaScript;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Paste/drop upload (Task A7): the editor's own {@code rollerUploadImages}
 * seam, driven the way {@code editor.js}'s {@code fileHandler} calls it for
 * a real paste or drop -- a {@code File} array, not a form submit.
 *
 * <p>Writes the shared weblog's media directory (a successful upload lands a
 * real {@code MediaFile} there), so this carries the same two resource locks
 * {@code MediaBulkUploadIT} does, for the same reason: the write lock on
 * {@code SHARED_MEDIA} because it uploads into the shared fixture weblog's
 * default directory, and the READ lock on {@code GLOBAL_CONFIG} because it
 * depends on {@code uploads.enabled} staying true while it runs (see that
 * class's javadoc for why a flipped flag reads as a wrong element-not-found
 * rather than an obvious race).
 */
@ResourceLocks({
        @ResourceLock(PressroomIT.SHARED_MEDIA),
        @ResourceLock(value = PressroomIT.GLOBAL_CONFIG, mode = ResourceAccessMode.READ)
})
class EditorUploadIT extends PressroomIT {

    private static final String ENTRY_ADD = "/roller-ui/authoring/entryAdd.rol?weblog=" + WEBLOG_HANDLE;
    private static final String GLOBAL_CONFIG = "/roller-ui/admin/globalConfig.rol";

    /** A 1x1 PNG -- the smallest real image the upload pipeline accepts. */
    private static final String TINY_PNG_BASE64 =
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==";

    @BeforeEach
    void logIn() {
        loginAsAdmin();
        enableUploads();
    }

    /**
     * File upload is globally disabled by default; flipping the runtime
     * property through the admin screen keeps this on real UI, same as
     * {@code MediaBulkUploadIT.enableUploads()}.
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

    @Test
    void aDroppedImageBecomesAnImageShortcode() {
        openPath(ENTRY_ADD);
        Editor.root();

        // Simulate the drop through the page's own seam: rollerUploadImages(files).
        executeJavaScript(
                "var bytes = Uint8Array.from(atob(arguments[0]), c => c.charCodeAt(0));"
                        + "var f = new File([bytes], 'drop.png', {type: 'image/png'});"
                        + "rollerUploadImages([f]);",
                TINY_PNG_BASE64);

        Selenide.Wait().withTimeout(Duration.ofSeconds(15))
                .until(d -> Editor.getText().matches("(?s).*\\[image id=\"[^\"]+\"\\].*"));
        assertFalse(Editor.getText().contains("uploading"));
    }

    /**
     * {@code uploads.types.forbid} defaults to {@code exe}
     * ({@code runtimeConfigDefs.xml}), so a {@code .exe} upload is refused by
     * the manager without this class needing to touch that setting (and
     * therefore without needing a write lock on {@code GLOBAL_CONFIG}).
     */
    @Test
    void aRefusedFileLeavesAStatusLineMessageAndNoShortcode() {
        openPath(ENTRY_ADD);
        Editor.root();

        executeJavaScript(
                "var bytes = Uint8Array.from(atob(arguments[0]), c => c.charCodeAt(0));"
                        + "var f = new File([bytes], 'x.exe', {type: 'image/png'});"
                        + "rollerUploadImages([f]);",
                TINY_PNG_BASE64);

        $("#editorStatus .is-error").shouldBe(visible, Duration.ofSeconds(15));
        assertFalse(Editor.getText().contains("[image"));
    }
}
