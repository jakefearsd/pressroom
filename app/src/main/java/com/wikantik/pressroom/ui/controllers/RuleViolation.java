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

package com.wikantik.pressroom.ui.controllers;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

import com.wikantik.pressroom.ui.restapi.ProblemCode;

/**
 * One rule a request breaks, in the terms both surfaces need (spec
 * 2026-11-20-jsp-rules-and-view-models-design, "Result shape", as amended
 * 2026-11-21). A rule class returns these and touches no screen; each
 * surface turns them into its own answer:
 * <ul>
 *   <li>the JSP controller shows {@code messageKey} with {@code args}, each
 *       argument HTML-escaped (they are raw values, user input among them,
 *       and the message sinks do not escape) except a {@link Label}, which
 *       it resolves to the label's text, marking {@code field}'s control
 *       where the form has one;</li>
 *   <li>the API raises {@code code} with {@code codeArgs} on {@code field}
 *       through the keyed problem factories: {@link Kind#INVALID} is a 400,
 *       {@link Kind#CONFLICT} a 409.</li>
 * </ul>
 * {@link ProblemCode} is a plain enum with no web type behind it, so carrying
 * it here keeps the rule classes free of the API's machinery.
 *
 * @param field    the field as the API names it
 * @param messageKey the screen's message key
 * @param args     the screen message's arguments, raw (unescaped)
 * @param code     the API's problem code
 * @param codeArgs the problem code's arguments, formatted as they should read
 * @param kind     whether the API answers 400 or 409
 */
public record RuleViolation(String field, String messageKey, List<Object> args, ProblemCode code,
        List<String> codeArgs, Kind kind) {

    /**
     * A screen-message argument that is itself a message: a field's label, which the screen shows
     * in the reader's language. It is the bundle's own text, so the screen does not escape it; the
     * API never sees it (its {@code codeArgs} name the field instead).
     *
     * @param key the label's message key
     */
    public record Label(String key) {
        public Label {
            Objects.requireNonNull(key, "key");
        }
    }

    /** How the API answers a violation. */
    public enum Kind {
        /** The request is malformed or breaks a rule: 400. */
        INVALID,
        /** The request collides with what is stored: 409. */
        CONFLICT
    }

    public RuleViolation {
        Objects.requireNonNull(field, "field");
        Objects.requireNonNull(messageKey, "messageKey");
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(kind, "kind");
        // A raw argument may be null (a value that was not sent), so List.copyOf will not do.
        args = Collections.unmodifiableList(new ArrayList<>(args));
        codeArgs = List.copyOf(codeArgs);
    }

    /** A 400-kind violation. */
    public static RuleViolation invalid(String field, String messageKey, List<?> args, ProblemCode code,
            String... codeArgs) {
        return new RuleViolation(field, messageKey, new ArrayList<>(args), code, List.of(codeArgs), Kind.INVALID);
    }

    /** A 409-kind violation. */
    public static RuleViolation conflict(String field, String messageKey, List<?> args, ProblemCode code,
            String... codeArgs) {
        return new RuleViolation(field, messageKey, new ArrayList<>(args), code, List.of(codeArgs), Kind.CONFLICT);
    }
}
