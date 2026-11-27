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
package com.wikantik.pressroom.pojos;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The one character rule for a weblog handle, shared by the store and every by-handle lookup (review minor 5). */
class WeblogHandleCharactersTest {

    @Test
    void lettersDigitsAndUnderscoreOnly() {
        assertTrue(Weblog.hasHandleCharacters("rc_blog2"));
        assertTrue(Weblog.hasHandleCharacters("café"), "letters in any script");
        assertFalse(Weblog.hasHandleCharacters("not a handle"));
        assertFalse(Weblog.hasHandleCharacters("site-space"), "the site space is let through by name, not by rule");
        assertFalse(Weblog.hasHandleCharacters("a/b"));
        assertFalse(Weblog.hasHandleCharacters(""));
        assertFalse(Weblog.hasHandleCharacters(null));
    }
}
