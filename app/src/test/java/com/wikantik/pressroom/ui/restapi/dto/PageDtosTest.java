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
package com.wikantik.pressroom.ui.restapi.dto;

import com.wikantik.pressroom.pojos.WeblogPage;
import com.wikantik.pressroom.ui.restapi.ApiException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tests for {@link PageDtos#applyWrite}, focused on an unknown {@code
 * status} value: the {@link ApiException} it raises used to discard the
 * {@link IllegalArgumentException} that caused it (see CLAUDE.md's
 * PreserveStackTrace note).
 */
class PageDtosTest {

    @Test
    void anUnknownStatusIsRejectedWithItsCauseAttached() {
        WeblogPage page = new WeblogPage();
        PageDtos.PageWrite write = new PageDtos.PageWrite(null, null, null, "bogus", null);

        ApiException thrown = assertThrows(ApiException.class, () -> PageDtos.applyWrite(page, write));

        assertEquals(400, thrown.getStatus());
        assertNotNull(thrown.getCause(),
                "the IllegalArgumentException that caused this must survive as the cause");
    }
}
