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
package com.wikantik.pressroom.ui.restapi.v1;

import com.wikantik.pressroom.ui.restapi.ApiException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@code directoryId}/{@code dir} and {@code entryId}/{@code entry}: one
 * parameter under two names. An empty value is a parameter not sent -- a
 * form or a script that always writes {@code &dir=} is not naming a second,
 * different directory -- so only two non-empty values that differ are a 400.
 */
class ParameterAliasTest {

    @Test
    void anEmptyAliasBesideAValueIsIgnored() {
        assertEquals("X", BaseApiController.eitherName("directoryId", "X", "dir", ""));
        assertEquals("X", BaseApiController.eitherName("directoryId", "X", "dir", "  "));
    }

    @Test
    void anEmptyValueBesideAnAliasLetsTheAliasSpeak() {
        assertEquals("X", BaseApiController.eitherName("directoryId", "", "dir", "X"));
    }

    @Test
    void bothEmptyIsNeitherSent() {
        assertNull(BaseApiController.eitherName("entryId", "", "entry", ""));
        assertNull(BaseApiController.eitherName("entryId", null, "entry", ""));
    }

    @Test
    void twoDifferentValuesAreStillA400NamingTheAlias() {
        assertThrows(ApiException.class, () -> BaseApiController.eitherName("directoryId", "X", "dir", "Y"));
        assertEquals("X", BaseApiController.eitherName("directoryId", "X", "dir", "X"));
        assertEquals("Y", BaseApiController.eitherName("directoryId", null, "dir", "Y"));
    }
}
