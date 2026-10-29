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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.wikantik.pressroom.TestUtils;
import com.wikantik.pressroom.business.runnable.ThreadManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Test TaskLock related business operations.
 */
public class TaskLockTest  {
    
    private static final Logger log = LoggerFactory.getLogger(TaskLockTest.class);

    @BeforeEach
    public void setUp() throws Exception {
        // setup weblogger
        TestUtils.setupWeblogger();
    }

    @AfterEach
    public void tearDown() throws Exception {
    }
    
    
    /**
     * Test basic persistence operations ... Create, Update, Delete.
     * @throws Exception if one is raised
     */
    @Test
    public void testTaskLockCRUD() throws Exception {
        
        ThreadManager mgr = TestUtils.weblogger().getThreadManager();
        
        // need a test task to play with
        TestTask task = new TestTask();
        task.init(TestUtils.weblogger());
        
        // try to acquire a lock
        assertTrue(mgr.registerLease(task), "Failed to acquire lease.");
        // We don't flush here because registerLease should flush on its own
        TestUtils.endSession(false);
        
        // make sure task is locked
        assertFalse(mgr.registerLease(task),"Acquired lease a second time when we shouldn't have been able to.");
        TestUtils.endSession(false);
        
        // try to release a lock
        assertTrue(mgr.unregisterLease(task), "Release of lease failed.");
        // We don't flush here because unregisterLease should flush on its own
        TestUtils.endSession(false);

        // Current unregisterLease semantics are idempotent.  Double release should
        // actually succeed.
        assertTrue( mgr.unregisterLease(task), "Second release failed.");
        TestUtils.endSession(false);
    }
    
}
