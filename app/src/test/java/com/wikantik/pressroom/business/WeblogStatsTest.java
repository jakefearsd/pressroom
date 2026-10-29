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

import com.wikantik.pressroom.util.PressroomConstants;
import com.wikantik.pressroom.TestUtils;
import com.wikantik.pressroom.pojos.*;
import org.junit.jupiter.api.*;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;


@TestMethodOrder(MethodOrderer.MethodName.class)
public class WeblogStatsTest  {

    private User user1, user2;

    private Weblog website1;
        private WeblogEntry entry11;
        private WeblogEntry entry12;

    private Weblog website2;
        private WeblogEntry entry21;

    @BeforeEach
    public void setUp() throws Exception {

        TestUtils.setupWeblogger();

        // create weblog with three entries
        user1 = TestUtils.setupUser("a_commentCountTestUser");
        user2 = TestUtils.setupUser("b_commentCountTestUser");

        website1 = TestUtils.setupWeblog("a_testWebsite1", user1);
        entry11 = TestUtils.setupWeblogEntry(
                "anchor11", website1, user1);
        entry12 = TestUtils.setupWeblogEntry(
                "anchor12", website1, user1);

        website2 = TestUtils.setupWeblog("b_testWebsite2", user1);
        entry21 = TestUtils.setupWeblogEntry(
                "anchor21", website2, user1);
        TestUtils.endSession(true);

        Thread.sleep(PressroomConstants.SEC_IN_MS);
    }

    @Test
    public void testGetUserNameLetterMap() throws Exception {
        UserManager mgr = TestUtils.weblogger().getUserManager();      
        Map<String, Long> map = mgr.getUserNameLetterMap();    
        assertNotNull(map.get("A"));
        assertNotNull(map.get("B"));
        assertNotNull(map.get("C"));
    }

    @Test
    public void testGetWeblogLetterMap() throws Exception {        
        WeblogManager mgr = TestUtils.weblogger().getWeblogManager();
        Map<String, Long> map = mgr.getWeblogHandleLetterMap();    
        assertNotNull(map.get("A"));
        assertNotNull(map.get("B"));
        assertNotNull(map.get("C"));
    }

    @AfterEach
    public void tearDown() throws Exception {

        TestUtils.teardownWeblog(website1.getId());
        TestUtils.teardownWeblog(website2.getId());

        TestUtils.teardownUser(user1.getUserName());
        TestUtils.teardownUser(user2.getUserName());

        TestUtils.endSession(true);
    }
}
