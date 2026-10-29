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
package com.wikantik.pressroom.business.jsonld;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import org.apache.commons.text.StringEscapeUtils;

/**
 * Serializes the nested maps/lists/strings/numbers the JSON-LD builders
 * produce. A hand-rolled writer beats pulling a JSON library into the render
 * path for a handful of object shapes, and it cannot be handed anything it
 * does not understand: every value is produced by the builders.
 *
 * <p>Every string goes through {@link StringEscapeUtils#escapeJson}, which
 * also escapes {@code /} so a value containing {@code </script>} cannot
 * terminate the inline script element it is emitted into.
 */
final class JsonLdWriter {

    private JsonLdWriter() {
        // static use only
    }

    /** Puts {@code value} under {@code key} unless it is null. */
    static void put(Map<String, Object> node, String key, Object value) {
        if (value != null) {
            node.put(key, value);
        }
    }

    static String write(Object value) {
        StringBuilder json = new StringBuilder(256);
        write(json, value);
        return json.toString();
    }

    private static void write(StringBuilder json, Object value) {
        if (value instanceof Map<?, ?> map) {
            json.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> field : map.entrySet()) {
                if (!first) {
                    json.append(',');
                }
                first = false;
                writeString(json, String.valueOf(field.getKey()));
                json.append(':');
                write(json, field.getValue());
            }
            json.append('}');
        } else if (value instanceof List<?> list) {
            json.append('[');
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) {
                    json.append(',');
                }
                write(json, list.get(i));
            }
            json.append(']');
        } else if (value instanceof BigDecimal decimal) {
            json.append(decimal.toPlainString());
        } else if (value instanceof Number number) {
            json.append(number);
        } else {
            writeString(json, String.valueOf(value));
        }
    }

    /**
     * escapeJson already writes '/' as "\/", so "</script>" cannot end the
     * block. '<' is also written as its JSON escape, because "<!--<script"
     * inside a script element puts the HTML parser into the double-escaped
     * state, where the real closing tag no longer ends the block and the rest
     * of the page is swallowed. Both are valid JSON for the same string.
     */
    private static void writeString(StringBuilder json, String value) {
        json.append('"').append(StringEscapeUtils.escapeJson(value).replace("<", "\\u003c")).append('"');
    }
}
