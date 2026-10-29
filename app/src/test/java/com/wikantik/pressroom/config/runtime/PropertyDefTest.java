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
package com.wikantik.pressroom.config.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Tests for {@link PropertyDef}, focused on {@code setRows(String)}/
 * {@code setCols(String)}: a malformed value from {@code
 * runtimeConfigDefs.xml} must not be fatal, since rows/cols only size the
 * admin textarea for the property.
 */
class PropertyDefTest {

    @Test
    void aMalformedRowsValueLeavesTheDefaultRatherThanThrowing() {
        PropertyDef def = new PropertyDef();

        assertDoesNotThrow(() -> def.setRows("not-a-number"));

        assertEquals(5, def.getRows(), "a bogus value must leave the constructor's default");
    }

    @Test
    void aMalformedColsValueLeavesTheDefaultRatherThanThrowing() {
        PropertyDef def = new PropertyDef();

        assertDoesNotThrow(() -> def.setCols("not-a-number"));

        assertEquals(25, def.getCols(), "a bogus value must leave the constructor's default");
    }

    @Test
    void aWellFormedRowsValueIsParsed() {
        PropertyDef def = new PropertyDef();

        def.setRows("10");

        assertEquals(10, def.getRows());
    }

    @Test
    void aWellFormedColsValueIsParsed() {
        PropertyDef def = new PropertyDef();

        def.setCols("40");

        assertEquals(40, def.getCols());
    }
}
