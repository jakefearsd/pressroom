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
package com.wikantik.pressroom.pojos.wrapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.business.URLStrategy;
import com.wikantik.pressroom.business.WeblogEntryManager;
import com.wikantik.pressroom.business.Weblogger;
import com.wikantik.pressroom.pojos.WeblogEntry;
import com.wikantik.pressroom.pojos.WeblogPage;

/** {@link WeblogPageWrapper#getParts()}: a strand's reader-visible parts, nothing for other pages. */
class WeblogPageWrapperTest {

    private final Weblogger weblogger = mock(Weblogger.class);
    private final WeblogEntryManager entries = mock(WeblogEntryManager.class);

    WeblogPageWrapperTest() {
        when(weblogger.getWeblogEntryManager()).thenReturn(entries);
    }

    private WeblogPageWrapper wrapped(boolean strand) {
        WeblogPage page = new WeblogPage();
        page.setStrand(strand);
        return WeblogPageWrapper.wrap(page, mock(URLStrategy.class), weblogger);
    }

    @Test
    void aPageThatIsNotAStrandHasNoParts() {
        assertTrue(wrapped(false).getParts().isEmpty());
    }

    @Test
    void aStrandListsItsReaderVisibleParts() throws Exception {
        WeblogEntry a = new WeblogEntry();
        a.setAnchor("a");
        when(entries.getStrandParts(any(), eq(true))).thenReturn(List.of(a));

        List<WeblogEntryWrapper> parts = wrapped(true).getParts();

        assertEquals(1, parts.size());
        assertEquals("a", parts.get(0).getAnchor());
    }

    @Test
    void aFailedLookupIsNotAnEmptyStrand() throws Exception {
        when(entries.getStrandParts(any(), eq(true))).thenThrow(new WebloggerException("down"));

        assertThrows(IllegalStateException.class, () -> wrapped(true).getParts());
    }
}
