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
package com.wikantik.pressroom.ui.controllers;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Characterisation test: pins the fully-resolved layout and tile attributes of
 * every view {@link PressroomViewResolver#init()} registers, as written by the
 * code before its definitions were rewritten with the fluent builder. It
 * passed against the old code; it is the proof that the rewrite changed no
 * view. Regenerate deliberately with {@code -Dviews.golden.write=true}.
 */
class PressroomViewDefinitionsCharacterisationTest {

    private static final String GOLDEN = "view-definitions.golden";

    @Test
    void everyDefinitionResolvesToTheSameLayoutAndAttributes() throws Exception {
        PressroomViewResolver resolver = new PressroomViewResolver();
        resolver.init();
        String actual = snapshot(resolver);

        if (Boolean.getBoolean("views.golden.write")) {
            Files.writeString(Path.of("src/test/resources/com/wikantik/pressroom/ui/controllers/" + GOLDEN),
                    actual, StandardCharsets.UTF_8);
        }
        try (InputStream in = getClass().getResourceAsStream(GOLDEN)) {
            assertNotNull(in, GOLDEN);
            assertEquals(new String(in.readAllBytes(), StandardCharsets.UTF_8), actual);
        }
    }

    @SuppressWarnings("unchecked")
    private static String snapshot(PressroomViewResolver resolver)
            throws ReflectiveOperationException, IOException {
        Field field = PressroomViewResolver.class.getDeclaredField("definitions");
        field.setAccessible(true);
        Map<String, ViewDefinition> definitions = (Map<String, ViewDefinition>) field.get(resolver);
        StringBuilder out = new StringBuilder();
        for (Map.Entry<String, ViewDefinition> e : new TreeMap<>(definitions).entrySet()) {
            out.append(e.getKey()).append(" -> ").append(e.getValue().layout()).append('\n');
            new TreeMap<>(e.getValue().attributes())
                    .forEach((k, v) -> out.append("    ").append(k).append(" = ").append(v).append('\n'));
        }
        return out.toString();
    }
}
