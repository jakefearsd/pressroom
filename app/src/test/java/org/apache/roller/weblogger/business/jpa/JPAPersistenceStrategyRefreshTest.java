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
package org.apache.roller.weblogger.business.jpa;

import java.lang.reflect.Field;

import org.apache.roller.weblogger.TestUtils;
import org.apache.roller.weblogger.pojos.User;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link JPAPersistenceStrategy#refresh}: it discards an entity's unsaved
 * in-memory edits in favour of the stored row, and is
 * a no-op for null.
 */
class JPAPersistenceStrategyRefreshTest {

    private User user;
    private JPAPersistenceStrategy strategy;

    @BeforeEach
    void setUp() throws Exception {
        TestUtils.setupWeblogger();
        user = TestUtils.setupUser("strategyrefresh");
        TestUtils.endSession(true);
        Field field = JPAUserManagerImpl.class.getDeclaredField("strategy");
        field.setAccessible(true);
        strategy = (JPAPersistenceStrategy) field.get(TestUtils.weblogger().getUserManager());
    }

    @AfterEach
    void tearDown() throws Exception {
        TestUtils.teardownUser(user.getUserName());
        TestUtils.endSession(true);
    }

    @Test
    void refreshReplacesUnsavedEditsWithTheStoredValues() throws Exception {
        User managed = TestUtils.weblogger().getUserManager().getUserByUserName(user.getUserName());
        managed.setFullName("edited but never saved");

        strategy.refresh(managed);

        assertEquals("Test User", managed.getFullName());
    }

    @Test
    void refreshingNullChangesNothingAndLeavesTheSessionFlushable() throws Exception {
        User managed = TestUtils.weblogger().getUserManager().getUserByUserName(user.getUserName());
        managed.setFullName("edited");

        strategy.refresh(null);

        assertEquals("edited", managed.getFullName(), "null is a no-op, not a refresh of everything");
        TestUtils.endSession(true);
        assertEquals("edited", TestUtils.weblogger().getUserManager()
                .getUserByUserName(user.getUserName()).getFullName(), "the session was not poisoned");
    }
}
