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
package com.wikantik.pressroom.business.jpa;

import com.wikantik.pressroom.TestUtils;
import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.pojos.WeblogPage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link JPAPersistenceStrategy#hasUncommittedWork()}: whether what this
 * thread's unit of work sees may differ from what is committed. The caches of
 * remembered lookups neither read nor fill while it is true.
 */
class UnitOfWorkStateTest {

    private JPAPersistenceStrategy strategy;

    @BeforeEach
    void setUp() throws Exception {
        TestUtils.setupWeblogger();
        TestUtils.endSession(false);
        strategy = QueryRecorder.strategy();
    }

    @AfterEach
    void tearDown() throws Exception {
        TestUtils.endSession(false);
    }

    @Test
    void aReadOnlyUnitOfWorkHasNothingUncommitted() throws Exception {
        assertFalse(strategy.hasUncommittedWork(), "nothing begun yet");
        TestUtils.weblogger().getWeblogManager().getWeblogCount();
        assertFalse(strategy.hasUncommittedWork(), "reads begin no transaction");
    }

    @Test
    void aWriteIsUncommittedUntilTheCommit() throws Exception {
        strategy.getEntityManager(true);
        assertTrue(strategy.hasUncommittedWork(), "a transaction is open");

        strategy.flush();
        assertFalse(strategy.hasUncommittedWork(), "committed");
    }

    /**
     * After a failed commit the transaction is gone but the unit of work's
     * entities still hold what it tried to write, stamps included, until the
     * session is released.
     */
    @Test
    void aFailedCommitStaysUncommittedUntilReleased() throws Exception {
        strategy.store(new WeblogPage()); // violates NOT NULL at the commit
        assertThrows(WebloggerException.class, strategy::flush);

        assertTrue(strategy.hasUncommittedWork(), "the failed unit of work is still dirty");

        TestUtils.endSession(false);
        assertFalse(strategy.hasUncommittedWork(), "released");
    }
}
