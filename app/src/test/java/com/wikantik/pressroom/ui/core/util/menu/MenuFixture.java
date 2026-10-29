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
package com.wikantik.pressroom.ui.core.util.menu;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

/**
 * Parses a menu definition written inline in a test.
 *
 * <p>The shipped menus are the right fixture for "does the real navigation come
 * out right", but a rule such as "the tab links to the first item the user can
 * see" needs a definition built to provoke it. Writing that XML in the test
 * keeps the case and its fixture in one place.
 */
final class MenuFixture {

    /** A menu id distinct from the shipped "editor" and "admin" menus. */
    static final String MENU_ID = "test-menu";

    private MenuFixture() {
    }

    static ParsedMenu parse(String xml) throws Exception {
        return MenuHelper.unmarshall(
                new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    }
}
