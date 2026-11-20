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

import org.junit.jupiter.api.Test;

import com.wikantik.pressroom.pojos.WeblogPage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** A page's SEO text fields are trimmed, blank stored as null, like an entry's. */
class PageBeanSeoTrimTest {

    @Test
    void whitespaceOnlySeoFieldsAreStoredAsNull() {
        PageBean bean = new PageBean();
        bean.setMetaTitle("   ");
        bean.setSearchDescription("\t ");
        bean.setCanonicalUrl("  ");
        WeblogPage page = new WeblogPage();
        bean.copyTo(page);
        assertNull(page.getMetaTitle());
        assertNull(page.getSearchDescription());
        assertNull(page.getCanonicalUrl());
    }

    @Test
    void paddedSeoFieldsAreTrimmed() {
        PageBean bean = new PageBean();
        bean.setMetaTitle(" Title ");
        bean.setSearchDescription(" Desc ");
        bean.setCanonicalUrl(" https://example.org/x ");
        WeblogPage page = new WeblogPage();
        bean.copyTo(page);
        assertEquals("Title", page.getMetaTitle());
        assertEquals("Desc", page.getSearchDescription());
        assertEquals("https://example.org/x", page.getCanonicalUrl());
    }
}
