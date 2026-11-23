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

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PageWidthTest {

    @Test
    void storedValueFallsBackToWide() {
        assertEquals(PageWidth.WIDE, PageWidth.of(null));
        assertEquals(PageWidth.WIDE, PageWidth.of(""));
        assertEquals(PageWidth.WIDE, PageWidth.of("HUGE"));
        assertEquals(PageWidth.STANDARD, PageWidth.of("standard"));
        assertEquals(PageWidth.STANDARD, PageWidth.of("STANDARD"));
        assertEquals(PageWidth.WIDE, PageWidth.of("wide"));
        assertEquals(PageWidth.WIDE, PageWidth.of("WIDE"));
    }

    @Test
    void parseIsStrictAndCaseInsensitive() {
        assertEquals(Optional.of(PageWidth.WIDE), PageWidth.parse(" Wide "));
        assertEquals(Optional.of(PageWidth.STANDARD), PageWidth.parse("standard"));
        assertEquals(Optional.empty(), PageWidth.parse("huge"));
        assertEquals(Optional.empty(), PageWidth.parse(""));
        assertEquals(Optional.empty(), PageWidth.parse(null));
    }

    @Test
    void aWeblogIsWideUntilToldOtherwise() {
        Weblog weblog = new Weblog();
        assertEquals("WIDE", weblog.getPageWidth());
        assertEquals(PageWidth.WIDE, weblog.pageWidth());
        weblog.setPageWidth("garbage");
        assertEquals(PageWidth.WIDE, weblog.pageWidth(), "a hand-edited value reads as wide");
        weblog.setPageWidth("STANDARD");
        assertEquals(PageWidth.STANDARD, weblog.pageWidth());

        Weblog copy = new Weblog();
        copy.setData(weblog);
        assertEquals("STANDARD", copy.getPageWidth(), "setData copies it");
    }
}
