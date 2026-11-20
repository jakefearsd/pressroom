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
package com.wikantik.pressroom.ui.controllers.editor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.wikantik.pressroom.pojos.WeblogEntry;
import com.wikantik.pressroom.pojos.WeblogPage;
import org.junit.jupiter.api.Test;

/** {@link SeoForm} applies the same trim-or-null rule to an entry and a page. */
class SeoFormTest {

    @Test
    void trimsAndBlanksBecomeNullOnAnEntryAndAPage() {
        SeoForm form = new SeoForm("  Title ", "   ", null);
        WeblogEntry entry = new WeblogEntry();
        WeblogPage page = new WeblogPage();
        form.applyTo(entry);
        form.applyTo(page);
        assertEquals("Title", entry.getMetaTitle());
        assertEquals("Title", page.getMetaTitle());
        assertNull(entry.getSearchDescription());
        assertNull(page.getSearchDescription());
        assertNull(entry.getCanonicalUrl());
        assertNull(page.getCanonicalUrl());
    }
}
