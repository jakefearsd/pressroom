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

package com.wikantik.pressroom.ui.controllers;

import java.util.List;

import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.business.EntryPins;
import com.wikantik.pressroom.business.WeblogEntryManager;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.ui.restapi.ProblemCode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** {@link EntryPinRules}: the pin limit with no controller in the test. */
class EntryPinRulesTest {

    private final Weblog weblog = new Weblog();
    private final WeblogEntryManager entries = mock(WeblogEntryManager.class);

    @Test
    void aNewPinOnAFullBlogIsRefusedWithTheLimit() throws Exception {
        when(entries.getPinnedEntryCount(weblog)).thenReturn((long) EntryPins.MAX_PER_WEBLOG);
        RuleViolation refused = EntryPinRules.roomProblem(entries, weblog, false, true);
        assertEquals("pinned", refused.field());
        assertEquals("weblogEdit.error.pinLimit", refused.messageKey());
        assertEquals(List.of(EntryPins.MAX_PER_WEBLOG), refused.args());
        assertEquals(ProblemCode.PINNED_TOO_MANY, refused.code());
        assertEquals(List.of(Integer.toString(EntryPins.MAX_PER_WEBLOG)), refused.codeArgs());
        assertEquals(RuleViolation.Kind.INVALID, refused.kind());
    }

    @Test
    void aNewPinWithRoomPasses() throws Exception {
        when(entries.getPinnedEntryCount(weblog)).thenReturn(EntryPins.MAX_PER_WEBLOG - 1L);
        assertNull(EntryPinRules.roomProblem(entries, weblog, false, true));
    }

    @Test
    void resavingAPinnedEntryAndUnpinningNeverCount() throws Exception {
        when(entries.getPinnedEntryCount(weblog)).thenThrow(new WebloggerException("down"));
        assertNull(EntryPinRules.roomProblem(entries, weblog, true, true));
        assertNull(EntryPinRules.roomProblem(entries, weblog, true, false));
        assertNull(EntryPinRules.roomProblem(entries, weblog, false, false));
        verify(entries, never()).getPinnedEntryCount(weblog);
    }

    @Test
    void aCountThatCannotBeReadThrows() throws Exception {
        when(entries.getPinnedEntryCount(weblog)).thenThrow(new WebloggerException("down"));
        assertThrows(WebloggerException.class, () -> EntryPinRules.roomProblem(entries, weblog, false, true));
    }
}
