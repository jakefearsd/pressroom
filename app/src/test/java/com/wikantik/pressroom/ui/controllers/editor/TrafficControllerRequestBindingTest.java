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

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.RequestParam;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * The build has no {@code -parameters}, so a bare {@code @RequestParam} passes
 * a unit test that calls the method directly and throws at runtime. Every
 * parameter of the Traffic page is named, optional, and defaulted.
 */
class TrafficControllerRequestBindingTest {

    @Test
    void everyRequestParamIsNamedOptionalAndDefaulted() {
        Method execute = null;
        for (Method m : TrafficController.class.getDeclaredMethods()) {
            if ("execute".equals(m.getName())) {
                execute = m;
            }
        }
        assertNotNull(execute);
        Map<String, String> defaults = new LinkedHashMap<>();
        for (Parameter p : execute.getParameters()) {
            RequestParam rp = p.getAnnotation(RequestParam.class);
            if (rp != null) {
                assertFalse(rp.name().isEmpty(), "unnamed @RequestParam: " + p);
                assertFalse(rp.required(), rp.name() + " must be optional");
                defaults.put(rp.name(), rp.defaultValue());
            }
        }
        assertEquals(Map.of("period", "30d", "tab", "posts", "sort", "views"), defaults);
    }
}
