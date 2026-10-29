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

package com.wikantik.pressroom.util;

import com.wikantik.pressroom.business.WeblogEntryManager;
import com.wikantik.pressroom.business.WeblogManager;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins that the W1 comment-subsystem removal actually reached the manager
 * interfaces: no method whose name contains "comment", in either casing,
 * survives on either manager.
 *
 * <p>{@code ReflectionTest} (same package) is about {@code Reflection}, the
 * plugin/cache-factory instantiation helper -- unrelated to what this pins --
 * so this lives in its own file rather than being folded into that one.
 */
public class CommentSurfaceGoneTest {

    @Test
    void weblogEntryManagerHasNoCommentSurface() {
        List<String> offenders = Arrays.stream(WeblogEntryManager.class.getMethods())
                .map(Method::getName)
                .filter(n -> n.toLowerCase(Locale.ROOT).contains("comment"))
                .sorted().toList();
        assertTrue(offenders.isEmpty(), "comment methods survive: " + offenders);
    }

    @Test
    void weblogManagerHasNoCommentSurface() {
        // getMostCommentedWeblogs ranked weblogs by comment count; it went
        // with the rest of the comment surface (W1), even though it was never
        // named by WeblogEntryManager's own comment-method list.
        List<String> offenders = Arrays.stream(WeblogManager.class.getMethods())
                .map(Method::getName)
                .filter(n -> n.toLowerCase(Locale.ROOT).contains("comment"))
                .sorted().toList();
        assertTrue(offenders.isEmpty(), "comment methods survive: " + offenders);
    }
}
