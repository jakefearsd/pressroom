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
package com.wikantik.pressroom.ui.controllers.pagers;

import java.util.List;

import com.wikantik.pressroom.pojos.MediaFile;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link MediaFilePager}, previously uncovered.
 */
class MediaFilePagerTest {

    @Test
    void firstPageWithNoMoreItemsIsJustOnePage() {
        MediaFilePager pager = new MediaFilePager(0, List.of(new MediaFile()), false);

        assertTrue(pager.isJustOnePage());
        assertFalse(pager.hasPrevious());
        assertFalse(pager.hasNext());
    }

    @Test
    void firstPageWithMoreItemsIsNotJustOnePage() {
        MediaFilePager pager = new MediaFilePager(0, List.of(new MediaFile()), true);

        assertFalse(pager.isJustOnePage());
        assertFalse(pager.hasPrevious());
        assertTrue(pager.hasNext());
    }

    @Test
    void laterPageHasPreviousAndIsNotJustOnePage() {
        MediaFilePager pager = new MediaFilePager(1, List.of(), false);

        assertFalse(pager.isJustOnePage());
        assertTrue(pager.hasPrevious());
        assertFalse(pager.hasNext());
    }
}
