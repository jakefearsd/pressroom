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
package com.wikantik.pressroom.business.jpa;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.StringJoiner;

import jakarta.persistence.Query;

/**
 * Positional parameters for a JPQL query built up condition by condition,
 * numbered from 1 in the order they are bound. {@link #bind} hands back the
 * placeholder to write into the query, so the number in the text and the
 * value's position cannot drift apart the way a hand-kept counter can.
 */
final class JpqlParameters {

    private final List<Object> values = new ArrayList<>();

    /** Records the value and returns its placeholder, {@code ?n}. */
    String bind(Object value) {
        values.add(value);
        return "?" + values.size();
    }

    /**
     * Binds every value and returns the placeholders joined for an IN list,
     * {@code ?1, ?2, ?3}; the caller writes the parentheses.
     */
    String bindAll(Collection<?> items) {
        StringJoiner placeholders = new StringJoiner(", ");
        for (Object item : items) {
            placeholders.add(bind(item));
        }
        return placeholders.toString();
    }

    /**
     * Appends {@code condition} followed by the value's placeholder, when
     * there is a value; nothing when it is null. Only for criteria that are
     * genuinely optional (a date bound, a locale): a null here widens the
     * query, so a filter that was asked for binds its value with
     * {@link #bind} even when that value is null.
     */
    void andIf(StringBuilder query, String condition, Object value) {
        if (value != null) {
            query.append(condition).append(bind(value));
        }
    }

    /** Sets every bound value on the query. */
    void setOn(Query query) {
        for (int i = 0; i < values.size(); i++) {
            query.setParameter(i + 1, values.get(i));
        }
    }

    /** Pages the query: no first result for offset 0, no cap for a maximum of -1. */
    static void page(Query query, int offset, int max) {
        if (offset != 0) {
            query.setFirstResult(offset);
        }
        if (max != -1) {
            query.setMaxResults(max);
        }
    }
}
