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
     * Create a lucene term for a keyword field (handle, locale). Those fields
     * are indexed verbatim (lowercase), so the term is the lowercased input;
     * running it through the text analyzer would stem it (a handle
     * "searchother" became "searchoth") or drop it as a stop word.
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

}
