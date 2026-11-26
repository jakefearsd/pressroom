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
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What remains of the 2026-10 fit-and-finish audit's source scans of the authoring list screens: the
 * message the React entries list's bulk-delete dialog shows (the screens' markup is Vitest's now).
 */
class ListScreensJspTest {

    private static final Path BUNDLE = Path.of("src/main/resources/ApplicationResources.properties");

    private static Properties bundle() throws IOException {
        Properties props = new Properties();
        try (Reader reader = Files.newBufferedReader(BUNDLE, StandardCharsets.UTF_8)) {
            props.load(reader);
        }
        return props;
    }

    // ------------------------------------------------------------ the bugs

    /** Bulk delete moves entries to the Trash; the warning said they were gone for good. */
    @Test
    void theBulkDeleteWarningDoesNotClaimTheEntriesAreGoneForGood() throws IOException {
        String warning = bundle().getProperty("weblogEntryQuery.bulkDeleteWarning");
        assertNotNull(warning, "weblogEntryQuery.bulkDeleteWarning is missing");
        assertFalse(warning.toLowerCase(java.util.Locale.ROOT).contains("cannot be undone"),
                "the bulk-delete modal says the move to the Trash cannot be undone: " + warning);
        assertTrue(warning.contains("Trash"), "the bulk-delete warning does not mention the Trash: " + warning);
    }
}
