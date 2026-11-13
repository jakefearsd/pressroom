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
package com.wikantik.pressroom.business.search;

/**
 * One reader search: the words as typed (untrusted), where it was made, and
 * the window of ranked hits wanted.
 *
 * @param words        the reader's query
 * @param scope        which posts are eligible
 * @param weblogHandle the blog searched, for {@link Scope#BLOG}; ignored otherwise
 * @param category     a category name to narrow to, or null
 * @param offset       index of the first hit wanted
 * @param limit        most hits wanted
 * @param locale       an entry locale to narrow to, or null
 */
public record SearchSpec(String words, Scope scope, String weblogHandle, String category,
        int offset, int limit, String locale) {

    /** Which posts a search may return. */
    public enum Scope {
        /** Every post the site lists. */
        SITE,
        /** Posts of {@link SearchSpec#weblogHandle()}. */
        BLOG,
        /** Posts the site lists that are not in {@link SearchSpec#weblogHandle()}. */
        OTHER_BLOGS
    }

    /** A search with no locale narrowing. */
    public SearchSpec(String words, Scope scope, String weblogHandle, String category,
            int offset, int limit) {
        this(words, scope, weblogHandle, category, offset, limit, null);
    }
}
