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

import com.wikantik.pressroom.pojos.SeoFields;
import org.apache.commons.lang3.StringUtils;

/**
 * The three SEO form fields an entry and a page editor both submit; applying
 * them is shared so the trimming (blank stored as null) cannot drift between
 * the two beans.
 *
 * @param metaTitle         the form's meta title
 * @param searchDescription the form's search description
 * @param canonicalUrl      the form's canonical URL
 */
record SeoForm(String metaTitle, String searchDescription, String canonicalUrl) {

    /** Writes the fields to {@code target}, a blank value becoming null. */
    void applyTo(SeoFields target) {
        target.setMetaTitle(StringUtils.trimToNull(metaTitle));
        target.setSearchDescription(StringUtils.trimToNull(searchDescription));
        target.setCanonicalUrl(StringUtils.trimToNull(canonicalUrl));
    }
}
