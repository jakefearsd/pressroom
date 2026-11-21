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

import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.business.MockWeblogger;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.pojos.WeblogEntry;
import com.wikantik.pressroom.pojos.WeblogPage;
import com.wikantik.pressroom.ui.restapi.ProblemCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.when;

/** {@link StrandChoice}: which strand an entry may join, with no controller in the test. */
class StrandChoiceTest {

    private MockWeblogger mock;
    private Weblog mine;
    private WeblogEntry entry;

    @BeforeEach
    void setUp() {
        mock = MockWeblogger.create();
        mine = new Weblog();
        mine.setId("w1");
        mine.setHandle("myblog");
        entry = new WeblogEntry();
        entry.setId("e1");
    }

    private WeblogPage page(String id, boolean strand, Weblog owner, WeblogPage.PubStatus status)
            throws WebloggerException {
        WeblogPage page = new WeblogPage();
        page.setId(id);
        page.setWeblog(owner);
        page.setStrand(strand);
        page.setStatus(status);
        when(mock.weblogPageManager().getPage(id)).thenReturn(page);
        return page;
    }

    private StrandChoice.Choice resolve(String id) {
        return StrandChoice.resolve(mock.weblogger(), mine, entry, id);
    }

    private static void refused(StrandChoice.Choice choice) {
        assertNull(choice.page());
        RuleViolation v = choice.violation();
        assertEquals("strand", v.field());
        assertEquals("weblogEdit.error.notAStrand", v.messageKey());
        assertEquals(ProblemCode.STRAND_ID_NOT_STRAND_PAGE, v.code());
        assertEquals(RuleViolation.Kind.INVALID, v.kind());
    }

    @Test
    void noStrandNamedOrTheOneTheEntryIsInIsNoChange() throws Exception {
        entry.setStrandPageId("s1");
        for (String id : new String[] {null, "s1"}) {
            StrandChoice.Choice choice = resolve(id);
            assertNull(choice.page());
            assertNull(choice.violation());
        }
    }

    @Test
    void oneOfTheBlogsStrandPagesIsTheChoice() throws Exception {
        WeblogPage strand = page("s1", true, mine, WeblogPage.PubStatus.PUBLISHED);
        StrandChoice.Choice choice = resolve("s1");
        assertSame(strand, choice.page());
        assertNull(choice.violation());
    }

    @Test
    void aPlainPageAnotherBlogsPageATrashedPageAnUnknownAndABlankIdAreRefused() throws Exception {
        Weblog other = new Weblog();
        other.setId("w2");
        other.setHandle("elsewhere");
        page("plain", false, mine, WeblogPage.PubStatus.PUBLISHED);
        page("foreign", true, other, WeblogPage.PubStatus.PUBLISHED);
        page("binned", true, mine, WeblogPage.PubStatus.TRASHED);
        for (String id : new String[] {"plain", "foreign", "binned", "nope", "", "  "}) {
            refused(resolve(id));
        }
    }

    @Test
    void aLookupThatFailsIsRefused() throws Exception {
        when(mock.weblogPageManager().getPage("boom")).thenThrow(new WebloggerException("down"));
        refused(resolve("boom"));
    }
}
