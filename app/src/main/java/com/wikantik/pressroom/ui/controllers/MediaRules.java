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

import java.util.List;

import org.apache.commons.lang3.StringUtils;
import com.wikantik.pressroom.pojos.MediaFile;
import com.wikantik.pressroom.pojos.MediaFileDirectory;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.ui.restapi.ProblemCode;

/**
 * What a media-folder name and a media-file name must satisfy, shared by the
 * media screens and the media API (spec
 * 2026-11-20-jsp-rules-and-view-models-design, Task 3). The rules touch no
 * screen: each returns a {@link RuleViolation}, or null when the value
 * passes. Folder names are path segments under the weblog's upload root, so
 * a name holds no slash (one leading slash is dropped) and may not be the
 * reserved {@code default}.
 */
public final class MediaRules {

    /** The API's name for the name field. */
    public static final String NAME = "name";

    /** The reserved name of every weblog's default folder. */
    public static final String DEFAULT_DIRECTORY = "default";

    private MediaRules() {
    }

    /**
     * A folder name as it will be stored, and what is wrong with it.
     *
     * @param name      the name trimmed and with one leading slash dropped; what to create, and what the
     *                  violation's arguments name
     * @param violation the first rule broken, null when none
     */
    public record DirectoryName(String name, RuleViolation violation) {
    }

    /**
     * Normalises {@code requested} (trimmed, then one leading {@code /}
     * dropped) and judges the result: not blank, not {@code default}, no
     * slash, at most {@link ColumnWidths#DIRECTORY_NAME} characters, and not
     * the name of a folder {@code weblog} already has.
     */
    public static DirectoryName directoryNameProblem(Weblog weblog, String requested) {
        String trimmed = StringUtils.trimToEmpty(requested);
        String name = trimmed.startsWith("/") ? trimmed.substring(1) : trimmed;
        return new DirectoryName(name, directoryViolation(weblog, trimmed, name));
    }

    private static RuleViolation directoryViolation(Weblog weblog, String trimmed, String name) {
        if (name.isEmpty() || StringUtils.isBlank(trimmed)) {
            return RuleViolation.invalid(NAME, "mediaFile.error.view.dirNameEmpty", List.of(),
                    ProblemCode.FIELD_REQUIRED, NAME);
        }
        if (DEFAULT_DIRECTORY.equals(name)) {
            return RuleViolation.invalid(NAME, "mediaFile.error.view.dirNameReserved", List.of(),
                    ProblemCode.DIRECTORY_NAME_RESERVED);
        }
        if (name.contains("/")) {
            return RuleViolation.invalid(NAME, "mediaFile.error.view.dirNameInvalid", List.of(),
                    ProblemCode.DIRECTORY_NAME_INVALID);
        }
        if (name.length() > ColumnWidths.DIRECTORY_NAME) {
            return RuleViolation.invalid(NAME, "mediaFile.error.view.dirNameTooLong", List.of(),
                    ProblemCode.FIELD_TOO_LONG, NAME, Integer.toString(ColumnWidths.DIRECTORY_NAME));
        }
        if (weblog.hasMediaFileDirectory(name)) {
            return RuleViolation.conflict(NAME, "mediaFile.directoryCreate.error.exists", List.of(name),
                    ProblemCode.DIRECTORY_NAME_IN_USE, name);
        }
        return null;
    }

    /** A file name is not blank and at most {@link ColumnWidths#MEDIA_NAME} characters; null when it is. */
    public static RuleViolation fileNameShapeProblem(String name) {
        // empty once trimmed, as the API judged a file name (not Unicode-blank)
        if (name == null || name.trim().isEmpty()) {
            return RuleViolation.invalid(NAME, "MediaFile.error.nameBlank", List.of(), ProblemCode.FIELD_BLANK, NAME);
        }
        if (name.length() > ColumnWidths.MEDIA_NAME) {
            return RuleViolation.invalid(NAME, "MediaFile.error.nameTooLong", List.of(), ProblemCode.FIELD_TOO_LONG,
                    NAME, Integer.toString(ColumnWidths.MEDIA_NAME));
        }
        return null;
    }

    /**
     * A conflict when a file other than the one with {@code excludingId} has
     * {@code name} in {@code directory} (two files of one name in a folder
     * would share a public address). Null when {@code directory} is null.
     */
    public static RuleViolation fileNameTaken(MediaFileDirectory directory, String name, String excludingId) {
        MediaFile holder = directory == null || name == null ? null : directory.getMediaFile(name);
        if (holder != null && !holder.getId().equals(excludingId)) {
            return RuleViolation.conflict(NAME, "MediaFile.error.duplicateName", List.of(name),
                    ProblemCode.MEDIA_NAME_IN_USE, name);
        }
        return null;
    }

    /** {@link #fileNameShapeProblem} then {@link #fileNameTaken}. */
    public static RuleViolation fileNameProblem(MediaFileDirectory directory, String name, String excludingId) {
        RuleViolation shape = fileNameShapeProblem(name);
        return shape != null ? shape : fileNameTaken(directory, name, excludingId);
    }
}
