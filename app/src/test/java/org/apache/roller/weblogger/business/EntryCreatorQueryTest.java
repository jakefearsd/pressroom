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
package org.apache.roller.weblogger.business;

import org.apache.roller.weblogger.TestUtils;
import org.apache.roller.weblogger.pojos.User;
import org.apache.roller.weblogger.pojos.Weblog;
import org.apache.roller.weblogger.pojos.WeblogEntry.PubStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code WeblogEntryManager.hasEntriesCreatedBy}: whether any entry names the
 * account as its creator. Entries name their creator by username with no
 * foreign key, so the account page refuses to delete a writer (ruling R8);
 * every status counts, the trash and drafts included, since each still
 * carries the byline.
 */
class EntryCreatorQueryTest {

    private User writer;
    private User drafter;
    private User trasher;
    private User nobody;
    private Weblog blog;

    @BeforeEach
    void setUp() throws Exception {
        TestUtils.setupWeblogger();
        writer = TestUtils.setupUser("ecqWriter");
        drafter = TestUtils.setupUser("ecqDrafter");
        trasher = TestUtils.setupUser("ecqTrasher");
        nobody = TestUtils.setupUser("ecqNobody");
        blog = TestUtils.setupWeblog("ecqblog", writer);
        TestUtils.endSession(true);
        TestUtils.setupWeblogEntry("ecq-published", TestUtils.getManagedWebsite(blog),
                TestUtils.getManagedUser(writer));
        TestUtils.setupWeblogEntry("ecq-draft", TestUtils.getManagedWebsite(blog),
                TestUtils.getManagedUser(drafter), PubStatus.DRAFT);
        TestUtils.setupWeblogEntry("ecq-trashed", TestUtils.getManagedWebsite(blog),
                TestUtils.getManagedUser(trasher), PubStatus.TRASHED);
        TestUtils.endSession(true);
    }

    @AfterEach
    void tearDown() throws Exception {
        TestUtils.endSession(false);
        TestUtils.teardownWeblog(blog.getId());
        for (User u : new User[] {writer, drafter, trasher, nobody}) {
            TestUtils.teardownUser(u.getUserName());
        }
        TestUtils.endSession(true);
    }

    @Test
    void anEntryInAnyStatusNamesItsCreator() throws Exception {
        WeblogEntryManager entries = TestUtils.weblogger().getWeblogEntryManager();

        assertTrue(entries.hasEntriesCreatedBy(writer), "a published entry");
        assertTrue(entries.hasEntriesCreatedBy(drafter), "a draft");
        assertTrue(entries.hasEntriesCreatedBy(trasher), "an entry in the trash");
        assertFalse(entries.hasEntriesCreatedBy(nobody), "an account that wrote nothing");
    }
}
