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
import java.util.List;

import jakarta.persistence.TypedQuery;

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
     * Appends {@code condition} followed by the value's placeholder, when
     * there is a value; nothing when it is null.
     */
    void andIf(StringBuilder query, String condition, Object value) {
        if (value != null) {
            query.append(condition).append(bind(value));
        }
    }

    /** Sets every bound value on the query. */
    void setOn(TypedQuery<?> query) {
        for (int i = 0; i < values.size(); i++) {
            query.setParameter(i + 1, values.get(i));
        }
    }
}
