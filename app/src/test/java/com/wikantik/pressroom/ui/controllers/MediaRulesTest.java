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

package com.wikantik.pressroom.ui.controllers;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

import com.wikantik.pressroom.pojos.MediaFile;
import com.wikantik.pressroom.pojos.MediaFileDirectory;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.ui.controllers.RuleViolation.Kind;
import com.wikantik.pressroom.ui.restapi.ProblemCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** {@link MediaRules} with no screen and no API behind it (spec 2026-11-20-jsp-rules-and-view-models-design, Task 3). */
class MediaRulesTest {

    private Weblog weblog;
    private MediaFileDirectory photos;

    @BeforeEach
    void setUp() {
        weblog = new Weblog();
        weblog.setMediaFileDirectories(new ArrayList<>());
        photos = new MediaFileDirectory(weblog, "photos", null);
        photos.setMediaFiles(new HashSet<>());
    }

    private MediaFile file(String id, String name) {
        MediaFile made = new MediaFile();
        made.setId(id);
        made.setName(name);
        photos.getMediaFiles().add(made);
        return made;
    }

    @Test
    void aFolderNameIsTrimmedAndLosesOneLeadingSlash() {
        assertEquals("x", MediaRules.directoryNameProblem(weblog, " /x ").name());
        assertNull(MediaRules.directoryNameProblem(weblog, " /x ").violation());
        assertEquals("/x", MediaRules.directoryNameProblem(weblog, "//x").name());
    }

    @Test
    void anEmptyFolderNameIsRequired() {
        for (String empty : new String[] {null, "", "   ", "/", " / "}) {
            RuleViolation v = MediaRules.directoryNameProblem(weblog, empty).violation();
            assertEquals(ProblemCode.FIELD_REQUIRED, v.code(), String.valueOf(empty));
            assertEquals("mediaFile.error.view.dirNameEmpty", v.messageKey());
            assertEquals(List.of("name"), v.codeArgs());
        }
    }

    @Test
    void defaultIsReservedWithOrWithoutALeadingSlash() {
        for (String reserved : new String[] {"default", "/default"}) {
            RuleViolation v = MediaRules.directoryNameProblem(weblog, reserved).violation();
            assertEquals(ProblemCode.DIRECTORY_NAME_RESERVED, v.code());
            assertEquals("mediaFile.error.view.dirNameReserved", v.messageKey());
            assertEquals(Kind.INVALID, v.kind());
        }
    }

    @Test
    void aSlashAnywhereElseIsInvalid() {
        for (String bad : new String[] {"a/b", "x/", "//x"}) {
            RuleViolation v = MediaRules.directoryNameProblem(weblog, bad).violation();
            assertEquals(ProblemCode.DIRECTORY_NAME_INVALID, v.code(), bad);
            assertEquals("mediaFile.error.view.dirNameInvalid", v.messageKey());
        }
    }

    @Test
    void aFolderNameOver255IsTooLong() {
        RuleViolation v = MediaRules.directoryNameProblem(weblog, "f".repeat(256)).violation();
        assertEquals(ProblemCode.FIELD_TOO_LONG, v.code());
        assertEquals("mediaFile.error.view.dirNameTooLong", v.messageKey());
        assertEquals(List.of("name", "255"), v.codeArgs());
        assertNull(MediaRules.directoryNameProblem(weblog, "f".repeat(255)).violation());
    }

    @Test
    void aTakenFolderNameIsAConflictNamingTheNormalisedName() {
        for (String taken : new String[] {"photos", "/photos", " photos "}) {
            RuleViolation v = MediaRules.directoryNameProblem(weblog, taken).violation();
            assertEquals(ProblemCode.DIRECTORY_NAME_IN_USE, v.code(), taken);
            assertEquals(Kind.CONFLICT, v.kind());
            assertEquals(List.of("photos"), v.codeArgs());
            assertEquals(List.of((Object) "photos"), v.args());
            assertEquals("mediaFile.directoryCreate.error.exists", v.messageKey());
        }
    }

    @Test
    void aFileNameIsNotBlankAndAtMost255() {
        for (String blank : new String[] {null, "", "  "}) {
            RuleViolation v = MediaRules.fileNameShapeProblem(blank);
            assertEquals(ProblemCode.FIELD_BLANK, v.code());
            assertEquals("MediaFile.error.nameBlank", v.messageKey());
        }
        RuleViolation tooLong = MediaRules.fileNameShapeProblem("f".repeat(256));
        assertEquals(ProblemCode.FIELD_TOO_LONG, tooLong.code());
        assertEquals("MediaFile.error.nameTooLong", tooLong.messageKey());
        assertEquals(List.of("name", "255"), tooLong.codeArgs());
        assertNull(MediaRules.fileNameShapeProblem("f".repeat(255)));
        // only what trim() empties is blank, as the API judged a file name
        assertNull(MediaRules.fileNameShapeProblem(" "));
    }

    @Test
    void anotherFileOfTheNameInTheDirectoryIsAConflictButTheFileItselfIsNot() {
        file("f1", "dog.jpg");
        file("f2", "cat.jpg");
        RuleViolation v = MediaRules.fileNameTaken(photos, "cat.jpg", "f1");
        assertEquals(ProblemCode.MEDIA_NAME_IN_USE, v.code());
        assertEquals(Kind.CONFLICT, v.kind());
        assertEquals(List.of("cat.jpg"), v.codeArgs());
        assertEquals(List.of((Object) "cat.jpg"), v.args());
        assertEquals("MediaFile.error.duplicateName", v.messageKey());
        assertNull(MediaRules.fileNameTaken(photos, "cat.jpg", "f2"));
        assertNull(MediaRules.fileNameTaken(photos, "bird.jpg", "f1"));
        assertNull(MediaRules.fileNameTaken(null, "cat.jpg", "f1"));
        assertNull(MediaRules.fileNameTaken(photos, null, "f1"));
    }

    @Test
    void theCompositeJudgesTheShapeBeforeTheDirectory() {
        file("f2", "cat.jpg");
        assertEquals(ProblemCode.FIELD_BLANK, MediaRules.fileNameProblem(photos, " ", "f1").code());
        assertEquals(ProblemCode.MEDIA_NAME_IN_USE, MediaRules.fileNameProblem(photos, "cat.jpg", "f1").code());
        assertNull(MediaRules.fileNameProblem(photos, "dog.jpg", "f1"));
    }
}
