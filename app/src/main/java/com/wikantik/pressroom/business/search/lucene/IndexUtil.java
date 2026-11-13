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
/* Created on Jul 20, 2003 */
package com.wikantik.pressroom.business.search.lucene;


import java.util.Locale;

import org.apache.lucene.index.Term;

/**
 * Class containing helper methods.
 *
 * @author Mindaugas Idzelis (min@idzelis.com)
 */
public final class IndexUtil {

    private IndexUtil() {}

    /**
     * Create a lucene term for a keyword field indexed lowercased (the
     * locale). Keyword fields never go through the text analyzer, which would
     * stem them (a handle "searchother" became "searchoth") or drop them as
     * stop words. Not for the handle, which keeps its case: use
     * {@link #handleTerm}.
     *
     * @param field
     *            The lucene document field to create a term with
     * @param input
     *            The input you wish to convert into a term
     *
     * @return Lucene search term, or null for a null field or blank input
     */
    public static Term getTerm(String field, String input) {
        if (input == null || field == null || input.isBlank()) {
            return null;
        }
        return new Term(field, input.strip().toLowerCase(Locale.ROOT));
    }

    /**
     * The handle as the index holds it: exactly as stored, case kept. Handles
     * are case-sensitive (the unique constraint and the lookup by handle
     * compare them exactly, and they may use A-Z), so two blogs may differ
     * only by case and must stay apart. Every writer and reader of the
     * {@code handle} field goes through this rule (final review, A1: a
     * lowercased query term found no blog with a capital in its handle).
     *
     * @return the handle, stripped, or null when blank
     */
    static String handleKey(String handle) {
        return handle == null || handle.isBlank() ? null : handle.strip();
    }

    /** The term for {@link #handleKey}, or null when blank. */
    static Term handleTerm(String handle) {
        String key = handleKey(handle);
        return key == null ? null : new Term(FieldConstants.WEBSITE_HANDLE, key);
    }

}
