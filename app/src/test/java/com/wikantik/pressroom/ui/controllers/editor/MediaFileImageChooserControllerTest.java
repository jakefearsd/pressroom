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

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.pojos.MediaFile;
import com.wikantik.pressroom.pojos.MediaFileDirectory;
import com.wikantik.pressroom.ui.controllers.util.KeyValueObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ui.Model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link MediaFileImageChooserController}.
 *
 * <p>This is the overlay-mode picker used from the entry editor's "insert
 * media" dialog. The three ways of choosing a directory (explicit id, name
 * lookup, or the default) have to resolve to the same place the main media
 * library uses, and the "allDirectories" filter has a genuinely surprising
 * double-negative condition in the source
 * ({@code !"default".equals(a) || !"default".equals(b)}) that is worth
 * pinning down by its actual behaviour rather than by what it looks like it
 * should do.
 */
class MediaFileImageChooserControllerTest extends EditorControllerTestSupport {

    private MediaFileImageChooserController controller;
    private Model model;
    private MediaFileDirectory defaultDirectory;
    private MediaFileDirectory targetDirectory;

    @BeforeEach
    void setUp() throws Exception {
        controller = prepare(new MediaFileImageChooserController());
        model = newModel();

        defaultDirectory = directory("dir-1", "default");
        targetDirectory = directory("dir-2", "photos");

        when(weblogger.getMediaFileManager().getDefaultMediaFileDirectory(weblog))
                .thenReturn(defaultDirectory);
        when(weblogger.getMediaFileManager().getMediaFileDirectories(weblog))
                .thenReturn(List.of(defaultDirectory, targetDirectory));
    }

    @Test
    void anExplicitDirectoryIdResolvesThatDirectory() throws Exception {
        when(weblogger.getMediaFileManager().getMediaFileDirectory("dir-2"))
                .thenReturn(targetDirectory);

        String view = controller.execute(request, model, "dir-2", null, false);

        assertEquals(".MediaFileImageChooser", view);
        assertEquals(targetDirectory, model.getAttribute("currentDirectory"));
        assertEquals("dir-2", model.getAttribute("directoryId"));
    }

    @Test
    void aDirectoryNameIsResolvedThroughGetMediaFileDirectoryByName() throws Exception {
        when(weblogger.getMediaFileManager().getMediaFileDirectoryByName(weblog, "photos"))
                .thenReturn(targetDirectory);

        controller.execute(request, model, null, "photos", false);

        assertEquals(targetDirectory, model.getAttribute("currentDirectory"));
        assertEquals("dir-2", model.getAttribute("directoryId"),
                "The resolved directory's own id must be echoed back once a name lookup succeeds");
    }

    @Test
    void withNeitherIdNorNameTheDefaultDirectoryIsShown() {
        String view = controller.execute(request, model, null, null, false);

        assertEquals(".MediaFileImageChooser", view);
        assertEquals(defaultDirectory, model.getAttribute("currentDirectory"));
        assertEquals("dir-1", model.getAttribute("directoryId"));
    }

    /**
     * The featured-image and social-image pickers open the chooser with
     * imagesOnly: a PDF offered there could only be refused after the click.
     */
    @Test
    void inImagesOnlyModeOnlyImagesAreOffered() {
        MediaFile photo = mediaFile("file-a", "alpha.jpg");
        MediaFile pdf = mediaFile("file-b", "brochure.pdf");
        pdf.setContentType("application/pdf");
        defaultDirectory.getMediaFiles().add(photo);
        defaultDirectory.getMediaFiles().add(pdf);

        controller.execute(request, model, null, null, true);
        assertEquals(List.of(photo), model.getAttribute("childFiles"));
        assertEquals(true, model.getAttribute("imagesOnly"), "the folder switcher must keep the mode");

        Model insertMode = newModel();
        controller.execute(request, insertMode, null, null, false);
        assertEquals(List.of(photo, pdf), insertMode.getAttribute("childFiles"),
                "inserting into the text, any file can be linked");
    }

    @Test
    void childFilesAreSortedByName() {
        MediaFile beta = mediaFile("file-b", "beta.jpg");
        MediaFile alpha = mediaFile("file-a", "alpha.jpg");
        defaultDirectory.getMediaFiles().add(beta);
        defaultDirectory.getMediaFiles().add(alpha);

        controller.execute(request, model, null, null, false);

        assertEquals(List.of(alpha, beta), model.getAttribute("childFiles"),
                "The overlay picker has no client-side sort, so this ordering is what is shown");
    }

    @Test
    void browsingTheDefaultDirectoryExcludesItFromAllDirectories() {
        // Both halves of the "!default.equals(a) || !default.equals(b)" test are
        // false only when the directory being listed AND the directory currently
        // being browsed are both named "default" - i.e. the default folder does
        // not list itself as a place to navigate to when you're already there.
        controller.execute(request, model, null, null, false);

        @SuppressWarnings("unchecked")
        List<MediaFileDirectory> allDirectories =
                (List<MediaFileDirectory>) model.getAttribute("allDirectories");
        assertEquals(List.of(targetDirectory), allDirectories,
                "Browsing the default directory must not list \"default\" as a navigation target");
    }

    @Test
    void browsingANonDefaultDirectoryIncludesTheDefaultEntry() throws Exception {
        // Once you are looking at any folder other than "default", the condition
        // is true for every directory including "default" itself, so the default
        // folder becomes reachable again.
        when(weblogger.getMediaFileManager().getMediaFileDirectoryByName(weblog, "photos"))
                .thenReturn(targetDirectory);

        controller.execute(request, model, null, "photos", false);

        @SuppressWarnings("unchecked")
        List<MediaFileDirectory> allDirectories =
                (List<MediaFileDirectory>) model.getAttribute("allDirectories");
        assertEquals(List.of(defaultDirectory, targetDirectory), allDirectories,
                "Browsing a non-default folder must offer the default folder as a navigation target too");
    }

    @Test
    void currentDirectoryHierarchyIsAOneEntryBreadcrumbForTheDirectoryName() throws Exception {
        when(weblogger.getMediaFileManager().getMediaFileDirectoryByName(weblog, "photos"))
                .thenReturn(targetDirectory);

        controller.execute(request, model, null, "photos", false);

        @SuppressWarnings("unchecked")
        List<KeyValueObject> hierarchy =
                (List<KeyValueObject>) model.getAttribute("currentDirectoryHierarchy");
        assertEquals(1, hierarchy.size(), "A directory with an unsegmented name has one breadcrumb entry");
        assertEquals("/photos", hierarchy.get(0).getKey());
        assertEquals("photos", hierarchy.get(0).getValue());
    }

    @Test
    void aFailureLoadingTheDirectoryIsReportedRatherThanBlowingUpThePage() throws Exception {
        when(weblogger.getMediaFileManager().getDefaultMediaFileDirectory(weblog))
                .thenThrow(new WebloggerException("database down"));

        String view = controller.execute(request, model, null, null, false);

        assertEquals(".MediaFileImageChooser", view,
                "The overlay view must always be returned, success or failure");
        assertTrue(errors(model).contains("MediaFile.error.view"),
                "Expected a view error, got: " + errors(model));
    }

    @Test
    void aDirectoryFromAnotherWeblogIsNotBrowsable() throws Exception {
        // The picker lists every file in the chosen directory; directoryId is
        // client input and getMediaFileDirectory is a global by-id lookup.
        com.wikantik.pressroom.pojos.Weblog other =
                new com.wikantik.pressroom.pojos.Weblog();
        other.setId("weblog-2");
        other.setHandle("otherblog");
        MediaFileDirectory foreign = directory("dir-x", "theirs");
        foreign.setWeblog(other);
        foreign.getMediaFiles().add(mediaFile("file-x", "their-photo.jpg"));
        when(weblogger.getMediaFileManager().getMediaFileDirectory("dir-x")).thenReturn(foreign);

        String view = controller.execute(request, model, "dir-x", null, false);

        assertEquals(".MediaFileImageChooser", view);
        assertNull(model.getAttribute("currentDirectory"));
        assertNull(model.getAttribute("childFiles"),
                "a foreign directory's file listing must not be rendered");
        assertTrue(errors(model).contains("MediaFile.error.view"),
                "Expected the browse to be refused, got: " + errors(model));
    }

    // --- helpers ---

    private MediaFileDirectory directory(String id, String name) {
        MediaFileDirectory dir = new MediaFileDirectory();
        dir.setId(id);
        dir.setName(name);
        dir.setWeblog(weblog);
        Set<MediaFile> files = new LinkedHashSet<>(Collections.emptySet());
        dir.setMediaFiles(files);
        return dir;
    }

    private MediaFile mediaFile(String id, String name) {
        MediaFile file = new MediaFile();
        file.setId(id);
        file.setName(name);
        file.setWeblog(weblog);
        file.setContentType("image/jpeg");
        return file;
    }
}
