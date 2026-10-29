/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  The ASF licenses this file to You
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
package com.wikantik.pressroom.ui.controllers.editor;

import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.pojos.MediaFile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ui.Model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link EntryAddWithMediaFileController}.
 *
 * <p>This controller never renders its own page; it builds a snippet from
 * the media files selected in the picker, stuffs it into {@code bean.text},
 * and forwards straight into {@link EntryAddController}'s template
 * ({@code entryAdd.rol}). An image contributes an {@code [image id="..."]}
 * shortcode -- the same one the editor's own media insert pastes, so it picks
 * up srcset/renditions/alt text through the normal render chain -- while a
 * non-image file (no shortcode covers it) still gets a hand-built plain link,
 * built by string substitution, so its exact markup is worth pinning
 * literally: a stray typo in a placeholder like {@code <size>} would silently
 * leave garbage in the entry text instead of failing anything visibly.
 */
class EntryAddWithMediaFileControllerTest extends EditorControllerTestSupport {

    private static final String FORWARD = "forward:/roller-ui/authoring/entryAdd.rol";

    private EntryAddWithMediaFileController controller;
    private EntryBean bean;
    private Model model;

    @BeforeEach
    void setUp() {
        controller = prepare(new EntryAddWithMediaFileController());
        bean = new EntryBean();
        model = newModel();
    }

    @Test
    void aSingleImageProducesAnImageShortcodeByMediaFileId() throws Exception {
        // The [image] shortcode -- not a hand-built <img> -- is what expands
        // at render time into the full responsive <figure><picture> block
        // with srcset, renditions, and alt text pulled from the media file
        // automatically. No thumbnail URL is needed here at all: the
        // shortcode resolves the media file by id at render time.
        MediaFile photo = imageFile("file-1", "photo.jpg", 100, 80);
        when(weblogger.getMediaFileManager().getMediaFile("file-1")).thenReturn(photo);

        String view = controller.execute(request, model, bean, null, "file-1");

        assertEquals(FORWARD, view);
        assertEquals("[image id=\"file-1\"]", bean.getText());
    }

    @Test
    void aSingleNonImageFileProducesAPlainLinkWithSizeAndType() throws Exception {
        MediaFile doc = fileOf("file-2", "report.pdf", "application/pdf", 2048);
        when(weblogger.getMediaFileManager().getMediaFile("file-2")).thenReturn(doc);
        when(weblogger.getUrlStrategy().getMediaFileURL(weblog, "file-2", true))
                .thenReturn("http://example.com/media/report.pdf");

        String view = controller.execute(request, model, bean, null, "file-2");

        assertEquals(FORWARD, view);
        assertEquals("<a href='http://example.com/media/report.pdf'>report.pdf</a> "
                + "(2048 bytes, application/pdf)", bean.getText());
    }

    @Test
    void aSingleSelectedImageParamIsTreatedAsAOneElementArray() throws Exception {
        // selectedImages (plural, from a multi-select overlay) and selectedImage
        // (singular, from a single-image insert) arrive from different pages;
        // when only the singular one is present it must be promoted rather than
        // silently ignored.
        MediaFile doc = fileOf("file-3", "notes.txt", "text/plain", 10);
        when(weblogger.getMediaFileManager().getMediaFile("file-3")).thenReturn(doc);
        when(weblogger.getUrlStrategy().getMediaFileURL(weblog, "file-3", true))
                .thenReturn("http://example.com/media/notes.txt");

        controller.execute(request, model, bean, null, "file-3");

        assertEquals("<a href='http://example.com/media/notes.txt'>notes.txt</a> "
                + "(10 bytes, text/plain)", bean.getText());
    }

    @Test
    void multipleSelectedImagesAreConcatenatedInOrder() throws Exception {
        MediaFile first = imageFile("file-1", "one.jpg", 50, 50);
        MediaFile second = imageFile("file-2", "two.jpg", 60, 60);
        when(weblogger.getMediaFileManager().getMediaFile("file-1")).thenReturn(first);
        when(weblogger.getMediaFileManager().getMediaFile("file-2")).thenReturn(second);

        controller.execute(request, model, bean, new String[]{"file-1", "file-2"}, null);

        assertEquals("[image id=\"file-1\"]\n\n[image id=\"file-2\"]", bean.getText(),
                "Multiple selections must be appended in the order they were selected, "
                        + "separated by a blank line so the author has somewhere to put a "
                        + "caption or split them onto their own lines");
    }

    @Test
    void theViewIsAlwaysTheForwardToEntryAddRegardlessOfOutcome() throws Exception {
        when(weblogger.getMediaFileManager().getMediaFile("missing"))
                .thenThrow(new WebloggerException("no such file"));

        String view = controller.execute(request, model, bean, null, "missing");

        assertEquals(FORWARD, view, "A failure building the snippet must still forward to entryAdd.rol");
    }

    @Test
    void aFailureResolvingAMediaFileIsSwallowedAndLeavesTheBeanTextUntouched() throws Exception {
        // getMediaFile throws before the StringBuilder is ever assigned back onto
        // the bean, so whatever text the bean already carried must survive
        // untouched rather than being blanked out or half-written.
        bean.setText("original text");
        when(weblogger.getMediaFileManager().getMediaFile("missing"))
                .thenThrow(new WebloggerException("no such file"));

        String view = controller.execute(request, model, bean, null, "missing");

        assertEquals(FORWARD, view);
        assertEquals("original text", bean.getText(),
                "The exception must be swallowed, not crash the request, and must not overwrite "
                        + "the bean's existing text since the failure happens before setText is reached");
    }

    @Test
    void aSelectedFileFromAnotherWeblogContributesNothingToTheSnippet() throws Exception {
        // The generated snippet embeds the file's id (as an [image] shortcode)
        // or its name and permalink (plain link), so a global by-id lookup
        // with no ownership check would leak another weblog's media into this
        // weblog's draft -- and, for the image case, would let this weblog's
        // entry render an image it does not own the moment the shortcode
        // expands.
        com.wikantik.pressroom.pojos.Weblog other =
                new com.wikantik.pressroom.pojos.Weblog();
        other.setId("weblog-2");
        other.setHandle("otherblog");
        com.wikantik.pressroom.pojos.MediaFileDirectory theirDirectory =
                new com.wikantik.pressroom.pojos.MediaFileDirectory();
        theirDirectory.setId("dir-foreign");
        theirDirectory.setName("default");
        theirDirectory.setWeblog(other);
        MediaFile foreign = fileOf("file-x", "their-photo.jpg", "image/jpeg", 100);
        foreign.setWeblog(other);
        foreign.setDirectory(theirDirectory);
        when(weblogger.getMediaFileManager().getMediaFile("file-x")).thenReturn(foreign);

        String view = controller.execute(request, model, bean, null, "file-x");

        assertEquals(FORWARD, view);
        assertEquals("", bean.getText(),
                "a foreign file's name and permalink must not reach this weblog's entry");
    }

    // --- helpers ---

    private MediaFile imageFile(String id, String name, int width, int height) {
        MediaFile file = fileOf(id, name, "image/jpeg", 0);
        file.setWidth(width);
        file.setHeight(height);
        return file;
    }

    private MediaFile fileOf(String id, String name, String contentType, long length) {
        MediaFile file = new MediaFile();
        file.setId(id);
        file.setName(name);
        file.setWeblog(weblog);
        file.setContentType(contentType);
        file.setLength(length);
        return file;
    }
}
