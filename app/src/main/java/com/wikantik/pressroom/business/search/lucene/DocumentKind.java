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
/* Created on Jul 19, 2003 */
package com.wikantik.pressroom.business.search.lucene;

/**
 * The kind of document a {@link SearchOperation} searches: what the
 * {@link FieldConstants#KIND} term holds, which fields carry the title and
 * the body, and whether the kind has a publish time and a site flag.
 */
public enum DocumentKind {

    /** A post: title and body fields, a publish time (future-dated posts never show) and a site flag. */
    ENTRY(FieldConstants.KIND_ENTRY, FieldConstants.TITLE, FieldConstants.CONTENT, true),

    /**
     * A page: its own title and body fields. A page in the index is
     * published and not noindex, so it has no publish-time or site-flag
     * filter.
     */
    PAGE(FieldConstants.KIND_PAGE, FieldConstants.PAGE_TITLE, FieldConstants.PAGE_CONTENT, false);

    private final String kindValue;
    private final String titleField;
    private final String contentField;
    private final boolean dated;

    DocumentKind(String kindValue, String titleField, String contentField, boolean dated) {
        this.kindValue = kindValue;
        this.titleField = titleField;
        this.contentField = contentField;
        this.dated = dated;
    }

    /** The value of the {@link FieldConstants#KIND} term documents of this kind carry. */
    String kindValue() {
        return kindValue;
    }

    String titleField() {
        return titleField;
    }

    String contentField() {
        return contentField;
    }

    /** Whether documents of this kind carry a publish time and the site flag the filters read. */
    boolean isDated() {
        return dated;
    }
}
