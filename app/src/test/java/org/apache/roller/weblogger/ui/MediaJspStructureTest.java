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
package org.apache.roller.weblogger.ui;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The media manager's pages, pinned where a browser test would be slow to
 * say the same thing: what an empty folder still offers, which fields are
 * labelled, which action carries the weight, and that every tile and the
 * focal-point picker work from the keyboard.
 */
class MediaJspStructureTest {

    private static final Path EDITOR = Path.of("src/main/webapp/WEB-INF/jsps/editor");

    private static String jsp(String name) throws IOException {
        return Files.readString(EDITOR.resolve(name), StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------------ empty folder

    /**
     * The folder switcher and "Delete folder" sat inside the guard that
     * renders the form only when the folder has files, so an empty folder --
     * the one most worth deleting -- could not be deleted, and the switcher
     * vanished with it.
     */
    @Test
    void anEmptyFolderStillOffersTheSwitcherAndDeleteFolder() throws IOException {
        String view = jsp("MediaFileView.jsp");

        for (String id : List.of("deleteFolderButton", "viewDirectoryMenu")) {
            List<String> guards = enclosingTests(view, "id=\"" + id + "\"");
            assertFalse(guards.stream().anyMatch(t -> t.contains("not empty childFiles")),
                    id + " must not depend on the folder having files; guarded by " + guards);
        }
    }

    // ------------------------------------------------------------------ labels

    @Test
    void everyMediaViewControlHasALabel() throws IOException {
        String view = jsp("MediaFileView.jsp");
        assertTrue(view.contains("<label for=\"viewDirectoryMenu\""), "the folder switcher has a label");
        assertTrue(view.contains("<label for=\"sortByMenu\""), "the sort select has a label");
        assertTrue(view.contains("<label for=\"moveTargetMenu\""), "the move target has a label");
        assertFalse(Pattern.compile("<span><spring:message code=\"mediaFileView.viewFolder\"/>:</span>")
                .matcher(view).find(), "\"Folder:\" is a span, not a label");
        assertFalse(view.contains("\"mediaFileView.sortBy\"/>:"), "labels sit above fields, without colons");

        String sidebar = jsp("MediaFileSidebar.jsp");
        assertTrue(sidebar.contains("name=\"bean.sizeUnit\" aria-labelledby=\"beanSizeLabel\""),
                "the size unit is named by the \"Larger than\" label");
        assertTrue(sidebar.contains("<label id=\"beanSizeLabel\" for=\"beanSize\""), sidebar);

        String add = jsp("MediaFileAdd.jsp");
        assertTrue(add.contains("<label for=\"uploadedFiles\""), "the file input has a label");
    }

    @Test
    void theTileCheckboxIsTheStyledControl() throws IOException {
        String view = jsp("MediaFileView.jsp");
        Matcher boxes = Pattern.compile("<input type=\"checkbox\"\\s+class=\"([^\"]*)\"\\s+name=\"selectedMediaFiles\"")
                .matcher(view);
        int count = 0;
        while (boxes.find()) {
            count++;
            assertTrue(boxes.group(1).contains("form-check-input"), boxes.group());
        }
        assertEquals(2, count, "one checkbox per tile, in each of the two tile loops");
    }

    // ---------------------------------------------------------------- weight

    @Test
    void uploadIsThePrimaryActionOnTheUploadPage() throws IOException {
        assertTrue(jsp("MediaFileAdd.jsp").contains("id=\"uploadButton\" class=\"btn btn-primary\""));
    }

    /** After an upload nothing is left to cancel: the way out is "Done". */
    @Test
    void theUploadSuccessPageWayOutIsDoneNotCancel() throws IOException {
        String success = jsp("MediaFileAddSuccess.jsp");
        assertFalse(success.contains("generic.cancel"), "\"Cancel\" suggests the upload is undone");
        assertTrue(success.contains("<spring:message code=\"generic.done\"/>"), success);
    }

    // --------------------------------------------------------- one visual idiom

    @Test
    void noLegacyIconsOrImageCopyButtonsAreLeft() throws IOException {
        for (String name : List.of("MediaFileView.jsp", "MediaFileAddSuccess.jsp", "MediaFileEdit.jsp")) {
            String source = jsp(name);
            assertFalse(source.contains("page.png"), name + " uses a FamFamFam icon, not Bootstrap Icons");
            assertFalse(source.contains("clippy.svg"), name + " uses the image copy button, not the text one");
        }
    }

    // --------------------------------------------------------------- keyboard

    /**
     * A tile was a {@code <div>} with only a delegated click handler: no
     * keyboard user could open a file, insert an image or pick a featured
     * image.
     */
    @Test
    void everyTileIsAKeyboardButton() throws IOException {
        for (String name : List.of("MediaFileView.jsp", "MediaFileImageChooser.jsp")) {
            String source = jsp(name);
            Matcher tiles = Pattern.compile("<div class=\"mediaObject\"([^>]*)>").matcher(source);
            int count = 0;
            while (tiles.find()) {
                count++;
                assertTrue(tiles.group(1).contains("role=\"button\""), name + ": " + tiles.group());
                assertTrue(tiles.group(1).contains("tabindex=\"0\""), name + ": " + tiles.group());
            }
            assertTrue(count > 0, name + " has no tiles?");
            assertTrue(source.contains("$(document).on('keydown', '.mediaObject'"),
                    name + " answers Enter and Space on a tile");
        }
    }

    /**
     * An inline {@code onmouseover="highlight(...)"} on a tile ran as soon as
     * the pointer rested on it, which can be before the script at the bottom
     * of the page has defined {@code highlight}: a pointer already over the
     * overlay as it loads threw "ReferenceError: highlight is not defined"
     * (EditorSeoIT, BrowserHealth). The hover binding is delegated from that
     * script instead, so it exists only once the function does.
     */
    @Test
    void tileHoverIsBoundByTheScriptNotInline() throws IOException {
        for (String name : List.of("MediaFileView.jsp", "MediaFileImageChooser.jsp")) {
            String source = jsp(name);
            assertFalse(source.contains("onmouseover="), name + " binds hover inline");
            assertFalse(source.contains("onmouseout="), name + " binds hover inline");
            assertTrue(source.contains("$(document).on('mouseenter mouseleave', '.align-images'"),
                    name + " delegates tile hover from its script");
        }
    }

    /**
     * The chooser's folder switcher posted to /roller-ui/authoring/
     * mediaFileImageChooser.rol, a path nothing serves (the controller is a
     * GET under /overlay), so changing folder in the picker failed. It also
     * has to carry imagesOnly, or a featured-image picker would start
     * offering PDFs after one folder change.
     */
    @Test
    void theChooserFolderSwitcherReloadsTheChooserInTheSameMode() throws IOException {
        String chooser = jsp("MediaFileImageChooser.jsp");
        assertTrue(chooser.contains("action=\"${pageContext.request.contextPath}/roller-ui/authoring/overlay/"
                + "mediaFileImageChooser.rol\" method=\"get\""), "a GET to the mapped overlay path");
        assertTrue(chooser.contains("<input type=\"hidden\" name=\"imagesOnly\" value=\"true\"/>"), chooser);
        assertFalse(chooser.contains("<sec:csrfInput/>"), "a GET form must not put the CSRF token in the URL");
    }

    @Test
    void theFocalPointPickerWorksFromTheKeyboardAndLoadsARendition() throws IOException {
        String edit = jsp("MediaFileEdit.jsp");
        assertTrue(Pattern.compile("<div id=\"focalPicker\"[^>]*tabindex=\"0\"").matcher(edit).find(),
                "the picker takes focus");
        assertTrue(edit.contains("ArrowLeft"), "arrow keys move the focal point");
        assertTrue(edit.contains("src='${fn:escapeXml(bean.permalink)}?w=480'"),
                "a 240px box needs no multi-megabyte original");
        assertFalse(Pattern.compile("<a href='[^']*'[^>]*>\\s*<img alt=\"\"").matcher(edit).find(),
                "the thumbnail link must have an accessible name");
    }

    /** The {@code test} of every {@code c:if} open at the first occurrence of {@code marker}. */
    private static List<String> enclosingTests(String source, String marker) {
        int at = source.indexOf(marker);
        assertTrue(at >= 0, "marker missing: " + marker);
        Deque<String> open = new ArrayDeque<>();
        Matcher tag = Pattern.compile("<c:if\\s+test=\"([^\"]*)\"\\s*>|</c:if>").matcher(source);
        while (tag.find() && tag.start() < at) {
            if (tag.group(1) != null) {
                open.push(tag.group(1));
            } else {
                open.pop();
            }
        }
        return new ArrayList<>(open);
    }
}
