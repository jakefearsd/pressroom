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

package com.wikantik.pressroom.business;

import java.sql.Timestamp;

import com.wikantik.pressroom.TestUtils;
import com.wikantik.pressroom.pojos.User;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.pojos.WeblogCategory;
import com.wikantik.pressroom.pojos.WeblogEntry;
import com.wikantik.pressroom.pojos.WeblogEntry.PubStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Duplicating a post.
 *
 * <p>The interesting part is not what a copy carries -- {@code setData} has
 * done that for years -- but what it must NOT carry: the original's identity,
 * its anchor, and its published state. Getting any of those wrong silently
 * damages the post being copied, or publishes an unreviewed one.
 */
public class WeblogEntryDuplicateTest {

    private User testUser;
    private Weblog testWeblog;

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.setupWeblogger();
        testUser = TestUtils.setupUser("dupTestUser");
        testWeblog = TestUtils.setupWeblog("dupTestWeblog", testUser);
        TestUtils.endSession(true);
    }

    @AfterEach
    public void tearDown() throws Exception {
        TestUtils.teardownWeblog(testWeblog.getId());
        TestUtils.teardownUser(testUser.getUserName());
        TestUtils.endSession(true);
    }

    @Test
    public void aCopyCarriesTheContentAndTheSeoBlock() throws Exception {
        WeblogEntry original = saveEntry("The Loire by bicycle", "loire-by-bicycle");
        original.setSummary("Six days, mostly downhill.");
        original.setSearchDescription("A week cycling the Loire valley");
        original.setMetaTitle("Loire by bicycle | Guides");
        original.setCanonicalUrl("https://example.com/loire");
        original.setFeaturedImageId("image-1");
        original.setOgImageId("image-2");
        original.setNoindex(Boolean.TRUE);
        entryManager().saveWeblogEntry(original);
        TestUtils.endSession(true);

        original = entryManager().getWeblogEntry(original.getId());
        WeblogEntry copy = original.copyAsDraft("Copy of The Loire by bicycle");

        assertEquals(original.getText(), copy.getText());
        assertEquals("Six days, mostly downhill.", copy.getSummary());
        assertEquals("A week cycling the Loire valley", copy.getSearchDescription());
        assertEquals("Loire by bicycle | Guides", copy.getMetaTitle());
        assertEquals("https://example.com/loire", copy.getCanonicalUrl());
        assertEquals("image-1", copy.getFeaturedImageId());
        assertEquals("image-2", copy.getOgImageId());
        assertEquals(Boolean.TRUE, copy.getNoindex());
        assertEquals(original.getCategory(), copy.getCategory());
        assertEquals(original.getWebsite(), copy.getWebsite());
        assertEquals("Copy of The Loire by bicycle", copy.getTitle());
    }

    @Test
    public void aCopyIsAnUnpublishedDraftWithItsOwnIdentity() throws Exception {
        WeblogEntry original = saveEntry("Published post", "published-post");
        TestUtils.endSession(true);
        original = entryManager().getWeblogEntry(original.getId());
        assertEquals(PubStatus.PUBLISHED, original.getStatus());

        WeblogEntry copy = original.copyAsDraft("Copy of Published post");

        assertNotNull(copy.getId());
        assertNotEquals(original.getId(), copy.getId(),
                "A shared id would make saving the copy overwrite the original");
        assertNull(copy.getAnchor(), "The anchor is left for saveWeblogEntry to make unique");
        assertEquals(PubStatus.DRAFT, copy.getStatus());
        assertNull(copy.getPubTime(), "A copy has never been published");
    }

    @Test
    public void savingACopyLeavesTheOriginalUntouched() throws Exception {
        WeblogEntry original = saveEntry("Original title", "original-anchor");
        String originalId = original.getId();
        TestUtils.endSession(true);

        WeblogEntry copy = entryManager().getWeblogEntry(originalId)
                .copyAsDraft("Copy of Original title");
        entryManager().saveWeblogEntry(copy);
        TestUtils.endSession(true);

        WeblogEntry reloaded = entryManager().getWeblogEntry(originalId);
        assertEquals("Original title", reloaded.getTitle());
        assertEquals("original-anchor", reloaded.getAnchor());
        assertEquals(PubStatus.PUBLISHED, reloaded.getStatus());

        // and clean up the copy the same way the weblog teardown would
        entryManager().removeWeblogEntry(entryManager().getWeblogEntry(copy.getId()));
        TestUtils.endSession(true);
    }

    /**
     * Anchors are the permalink, so two entries in one weblog cannot share one.
     * Duplicating twice is the case that catches a naive "derive from title"
     * implementation, because both copies are handed the same title.
     */
    @Test
    public void duplicatingTwiceProducesTwoDistinctAnchors() throws Exception {
        WeblogEntry original = saveEntry("Same title", "same-title");
        String originalId = original.getId();
        TestUtils.endSession(true);

        WeblogEntry first = entryManager().getWeblogEntry(originalId).copyAsDraft("Copy of Same title");
        entryManager().saveWeblogEntry(first);
        TestUtils.endSession(true);

        WeblogEntry second = entryManager().getWeblogEntry(originalId).copyAsDraft("Copy of Same title");
        entryManager().saveWeblogEntry(second);
        TestUtils.endSession(true);

        String firstAnchor = entryManager().getWeblogEntry(first.getId()).getAnchor();
        String secondAnchor = entryManager().getWeblogEntry(second.getId()).getAnchor();

        assertNotNull(firstAnchor);
        assertNotNull(secondAnchor);
        assertFalse(firstAnchor.isBlank());
        assertNotEquals(firstAnchor, secondAnchor,
                "Two copies of one post collided on a single permalink");

        entryManager().removeWeblogEntry(entryManager().getWeblogEntry(first.getId()));
        entryManager().removeWeblogEntry(entryManager().getWeblogEntry(second.getId()));
        TestUtils.endSession(true);
    }

    /**
     * Tags are re-added by name rather than by sharing rows: a shared tag row
     * would belong to two entries at once, and deleting either would take the
     * other's tag with it.
     */
    @Test
    public void aCopyOwnsItsOwnTagRows() throws Exception {
        WeblogEntry original = saveEntry("Tagged post", "tagged-post");
        original.addTag("cycling");
        original.addTag("loire");
        entryManager().saveWeblogEntry(original);
        TestUtils.endSession(true);

        original = entryManager().getWeblogEntry(original.getId());
        WeblogEntry copy = original.copyAsDraft("Copy of Tagged post");
        entryManager().saveWeblogEntry(copy);
        TestUtils.endSession(true);

        WeblogEntry savedCopy = entryManager().getWeblogEntry(copy.getId());
        assertEquals(2, savedCopy.getTags().size());
        assertTrue(savedCopy.getTagsAsString().contains("cycling"));
        assertTrue(savedCopy.getTagsAsString().contains("loire"));
        savedCopy.getTags().forEach(tag -> assertEquals(savedCopy.getId(),
                tag.getWeblogEntry().getId(), "A copy's tag row points at the original"));

        entryManager().removeWeblogEntry(savedCopy);
        TestUtils.endSession(true);
    }

    private WeblogEntryManager entryManager() {
        return TestUtils.weblogger().getWeblogEntryManager();
    }

    private WeblogEntry saveEntry(String title, String anchor) throws Exception {
        // Re-attached, not the instance from setUp: that one belongs to a
        // session that has since been released, and saving an entry against it
        // makes EclipseLink treat the weblog as new and insert it again.
        Weblog managedWeblog = TestUtils.getManagedWebsite(testWeblog);

        WeblogEntry entry = new WeblogEntry();
        entry.setTitle(title);
        entry.setAnchor(anchor);
        entry.setText("Body of " + title);
        entry.setWebsite(managedWeblog);
        entry.setCreatorUserName(TestUtils.getManagedUser(testUser).getUserName());
        entry.setCategory(TestUtils.categoryNamed(managedWeblog, "General"));
        entry.setStatus(PubStatus.PUBLISHED);
        entry.setPubTime(new Timestamp(System.currentTimeMillis()));
        entry.setUpdateTime(new Timestamp(System.currentTimeMillis()));
        entryManager().saveWeblogEntry(entry);
        return entry;
    }
}
