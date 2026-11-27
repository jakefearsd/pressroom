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
package com.wikantik.pressroom.business.reader;

import java.sql.Timestamp;
import java.time.Instant;

import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.pojos.WeblogEntry;
import com.wikantik.pressroom.pojos.WeblogEntry.PubStatus;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The visible-entries rule (spec Part E, Review Focus 5), without a database. */
class ReaderVisibilityTest {

    private static final Instant NOW = Instant.parse("2026-11-27T12:00:00Z");

    @Test
    void visibilityRuleExcludesScheduledDraftTrashedHidden() {
        assertTrue(ReaderVisibility.isVisible(entry(PubStatus.PUBLISHED, NOW, blog(true, true)), NOW),
                "published, due now, on an active visible blog");
        assertTrue(ReaderVisibility.isVisible(entry(PubStatus.PUBLISHED, NOW.minusSeconds(60), blog(true, true)),
                NOW));

        assertFalse(ReaderVisibility.isVisible(entry(PubStatus.PUBLISHED, NOW.plusSeconds(1), blog(true, true)),
                NOW), "scheduled: published with a future pubTime");
        assertFalse(ReaderVisibility.isVisible(entry(PubStatus.SCHEDULED, NOW.minusSeconds(60), blog(true, true)),
                NOW), "scheduled status");
        assertFalse(ReaderVisibility.isVisible(entry(PubStatus.DRAFT, NOW.minusSeconds(60), blog(true, true)), NOW));
        assertFalse(ReaderVisibility.isVisible(entry(PubStatus.PENDING, NOW.minusSeconds(60), blog(true, true)),
                NOW));
        assertFalse(ReaderVisibility.isVisible(entry(PubStatus.TRASHED, NOW.minusSeconds(60), blog(true, true)),
                NOW));
        WeblogEntry trashedButPublished = entry(PubStatus.PUBLISHED, NOW.minusSeconds(60), blog(true, true));
        trashedButPublished.setTrashedAt(Timestamp.from(NOW));
        assertFalse(ReaderVisibility.isVisible(trashedButPublished, NOW), "a trash stamp hides it too");
        assertFalse(ReaderVisibility.isVisible(entry(PubStatus.PUBLISHED, null, blog(true, true)), NOW),
                "no pubTime: never published");

        assertFalse(ReaderVisibility.isVisible(entry(PubStatus.PUBLISHED, NOW, blog(false, true)), NOW),
                "a hidden blog's entries");
        assertFalse(ReaderVisibility.isVisible(entry(PubStatus.PUBLISHED, NOW, blog(true, false)), NOW),
                "an inactive blog's entries");
        assertFalse(ReaderVisibility.isVisible(entry(PubStatus.PUBLISHED, NOW, blog(null, null)), NOW));
        assertFalse(ReaderVisibility.isVisible(entry(PubStatus.PUBLISHED, NOW, null), NOW), "no blog");
        assertFalse(ReaderVisibility.isVisible((WeblogEntry) null, NOW));
        assertFalse(ReaderVisibility.isVisible((WeblogEntry) null));
        assertTrue(ReaderVisibility.isVisible(entry(PubStatus.PUBLISHED, Instant.now().minusSeconds(5),
                blog(true, true))), "against the clock");
    }

    @Test
    void aBlogIsVisibleWhenActiveAndPublic() {
        assertTrue(ReaderVisibility.isVisible(blog(true, true)));
        assertFalse(ReaderVisibility.isVisible(blog(false, true)));
        assertFalse(ReaderVisibility.isVisible(blog(true, false)));
        assertFalse(ReaderVisibility.isVisible((Weblog) null));
    }

    private static Weblog blog(Boolean visible, Boolean active) {
        Weblog w = new Weblog();
        w.setVisible(visible);
        w.setActive(active);
        return w;
    }

    private static WeblogEntry entry(PubStatus status, Instant pubTime, Weblog weblog) {
        WeblogEntry e = new WeblogEntry();
        e.setStatus(status);
        e.setPubTime(pubTime == null ? null : Timestamp.from(pubTime));
        e.setWebsite(weblog);
        return e;
    }
}
