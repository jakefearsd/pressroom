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

package com.wikantik.pressroom.ui;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the coupling between {@code MediaFileAdd.jsp}'s bulk-upload drop zone
 * and the DOM the server and the browser IT depend on.
 *
 * <p>A sibling of {@code MediaAltTextWiringTest} rather than an extension of
 * it: that class is specifically about {@code MediaFile.altText}'s wiring
 * into {@code MediaFileEdit.jsp}/{@code MediaFileView.jsp}, and this is a
 * different JSP covering a different feature (bulk upload on the Add form),
 * so folding the two together would make one class's name describe only half
 * of what it tests.
 *
 * <p>None of this fails at compile time. The server binds
 * {@code uploadedFiles} by control NAME, not id -- a rename there silently
 * stops the multipart binding and every upload 400s or vanishes depending on
 * how Spring resolves the missing parameter. The existing browser ITs
 * (GalleryIT, MediaCropIT, EditorSeoIT, ThemeMatrixIT,
 * GlobalConfigMatrixIT) drive the file input by id, so the id matters too --
 * it used to be {@code #fileControl0} and is now {@code #uploadedFiles},
 * updated in those files in the same commit as this test.
 */
class MediaFileAddWiringTest {

    private static final Path MEDIA_FILE_ADD =
            Paths.get("src/main/webapp/WEB-INF/jsps/editor/MediaFileAdd.jsp");

    private static String read(Path jsp) throws IOException {
        return Files.readString(jsp, StandardCharsets.UTF_8);
    }

    /**
     * The JSP with its comments removed.
     *
     * <p>An assertion that matches raw JSP source is really an assertion
     * about the file's prose. Strip the commentary; assert on the code.
     */
    private static String codeOnly(String jsp) {
        return jsp.replaceAll("(?s)<%--.*?--%>", "")
                .replaceAll("(?m)^\\s*//.*$", "");
    }

    @Test
    void thereIsExactlyOneFileInput() throws IOException {
        String jsp = codeOnly(read(MEDIA_FILE_ADD));
        Matcher matches = Pattern.compile("<input\\s+type=\"file\"").matcher(jsp);
        int count = 0;
        while (matches.find()) {
            count++;
        }
        assertEquals(1, count,
                "MediaFileAdd.jsp must carry exactly one file input, not the old five "
                        + "fixed slots -- found " + count);
    }

    @Test
    void theFileInputCarriesMultiple() throws IOException {
        String jsp = codeOnly(read(MEDIA_FILE_ADD));
        int start = jsp.indexOf("<input type=\"file\"");
        assertTrue(start >= 0, "MediaFileAdd.jsp must render a file input");
        String tag = jsp.substring(start, jsp.indexOf('>', start) + 1);
        assertTrue(tag.contains("multiple"),
                "the file input must carry the multiple attribute -- without it the "
                        + "browser only ever offers one file at a time, defeating the point "
                        + "of the bulk upload: " + tag);
    }

    @Test
    void theFileInputIsStillNamedUploadedFiles() throws IOException {
        String jsp = codeOnly(read(MEDIA_FILE_ADD));
        assertTrue(jsp.contains("name=\"uploadedFiles\""),
                "the file input must still be named uploadedFiles -- the server binds "
                        + "the multipart parameter by control NAME, not id, and a rename here "
                        + "breaks every upload silently");
        assertTrue(jsp.contains("id=\"uploadedFiles\""),
                "the file input's id must be uploadedFiles -- the browser ITs and the "
                        + "page's own drag/drop script both look it up by that id");
    }

    @Test
    void beanNameNoLongerAppearsInTheForm() throws IOException {
        String jsp = codeOnly(read(MEDIA_FILE_ADD));
        assertFalse(jsp.contains("bean.name"),
                "the removed Name field (and its now-inert value) must not still appear "
                        + "in the form -- save() overwrites the name from the uploaded "
                        + "filename regardless of what this field held, so a lingering "
                        + "field is pure confusion");
    }

    @Test
    void theDropZoneAndChosenFilesListAreThereForTheDragAndDropScript() throws IOException {
        String jsp = codeOnly(read(MEDIA_FILE_ADD));
        assertTrue(jsp.contains("id=\"mediaDropZone\""),
                "the drop zone must exist for the browser IT to drive and for the "
                        + "page's own script to bind dragover/dragleave/drop against");
        assertTrue(jsp.contains("id=\"mediaChosenFiles\""),
                "the chosen-files list must exist so the author can see what is about "
                        + "to be uploaded before committing");
    }

    @Test
    void dropAndDragoverBothPreventDefault() throws IOException {
        // Missing either one lets the browser navigate away to the dropped
        // file, losing everything typed into Description/Tags so far.
        String jsp = codeOnly(read(MEDIA_FILE_ADD));
        int dragoverStart = jsp.indexOf("addEventListener(\"dragover\"");
        int dropStart = jsp.indexOf("addEventListener(\"drop\"");
        assertTrue(dragoverStart >= 0, "a dragover handler must be registered");
        assertTrue(dropStart >= 0, "a drop handler must be registered");

        String dragoverBody = jsp.substring(dragoverStart,
                jsp.indexOf("});", dragoverStart));
        String dropBody = jsp.substring(dropStart, jsp.indexOf("});", dropStart));

        assertTrue(dragoverBody.contains("preventDefault"),
                "dragover must call preventDefault(), or dropping a file navigates the "
                        + "whole page away to it");
        assertTrue(dropBody.contains("preventDefault"),
                "drop must call preventDefault(), or the browser navigates away to the "
                        + "dropped file");
    }

    private static final Path MEDIA_FILE_ADD_SUCCESS =
            Paths.get("src/main/webapp/WEB-INF/jsps/editor/MediaFileAddSuccess.jsp");

    /**
     * The success page's "upload more" and "cancel" controls must navigate, not
     * submit.
     *
     * <p>They were {@code <button onclick='window.load(url)'>} with no
     * {@code type}, inside {@code <form id="entry" method="post">} which carries
     * no {@code action}. Three things compounded: {@code window.load} is not a
     * function, so the handler threw; an uncaught handler exception does not
     * prevent the default action; and a typeless button in a form defaults to
     * {@code type="submit"}. So Cancel threw a TypeError and then re-POSTed to
     * the upload endpoint instead of returning to the media view -- it did the
     * opposite of cancelling.
     *
     * <p>Anchors styled as buttons are the idiom already used for in-form
     * navigation (see {@code UserEdit.jsp}'s cancel link); they cannot submit
     * and work without JavaScript.
     */
    @Test
    void theSuccessPageNavigationControlsAreLinksNotFormSubmits() throws IOException {
        // JSP comments are stripped first: the fix's own comment explains what
        // window.load() did wrong, and a naive scan flags that explanation.
        String jsp = read(MEDIA_FILE_ADD_SUCCESS)
                .replaceAll("(?s)<%--.*?--%>", "");

        assertFalse(jsp.contains("window.load("),
                "window.load is not a function; these controls must navigate via an anchor");

        Matcher m = Pattern.compile("<button(?![^>]*\\btype\\s*=)[^>]*>", Pattern.CASE_INSENSITIVE)
                .matcher(jsp);
        assertFalse(m.find(),
                "a <button> inside this form with no type= defaults to submit: " + (m.reset().find() ? m.group() : ""));

        assertTrue(jsp.contains("href=\"${mediaFileAddURL}\""),
                "the 'upload more' control must link to mediaFileAddURL");
        assertTrue(jsp.contains("href=\"${mediaFileViewURL}\""),
                "the 'cancel' control must link to mediaFileViewURL");
    }
}
