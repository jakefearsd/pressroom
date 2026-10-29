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
package com.wikantik.pressroom.ui.controllers.core;

import com.wikantik.pressroom.pojos.User;
import com.wikantik.pressroom.TestUtils;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Tests for {@link ProfileBean}, the form object behind the "your profile"
 * page.
 */
class ProfileBeanTest {

    @Test
    void copyToWritesTheEditableProfileFields() {
        ProfileBean bean = new ProfileBean();
        bean.setScreenName("Jake");
        bean.setFullName("Jake Fear");
        bean.setEmailAddress("jake@example.com");
        bean.setLocale("en_GB");
        bean.setTimeZone("Europe/Berlin");

        User user = new User();
        bean.copyTo(user);

        assertEquals("Jake", user.getScreenName());
        assertEquals("Jake Fear", user.getFullName());
        assertEquals("jake@example.com", user.getEmailAddress());
        assertEquals("en_GB", user.getLocale());
        assertEquals("Europe/Berlin", user.getTimeZone());
    }

    @Test
    void copyToLeavesTheUserNameAndPasswordAlone() {
        // The controller encodes the password separately, and the username is
        // the account's identity -- neither may be overwritten by form input.
        ProfileBean bean = new ProfileBean();
        bean.setUserName("someone-else");
        bean.setPassword("plaintext");
        bean.setPasswordText("plaintext");

        User user = new User();
        user.setUserName("jake");
        user.setPassword(TestUtils.TEST_PASSWORD_HASH);
        bean.copyTo(user);

        assertEquals("jake", user.getUserName());
        assertEquals(TestUtils.TEST_PASSWORD_HASH, user.getPassword());
    }

    @Test
    void copyFromLoadsTheProfileWithoutTheStoredPassword() {
        // The form must never echo the stored password hash back to the browser.
        User user = new User();
        user.setId("u1");
        user.setUserName("jake");
        user.setPassword(TestUtils.TEST_PASSWORD_HASH);
        user.setScreenName("Jake");
        user.setFullName("Jake Fear");
        user.setEmailAddress("jake@example.com");
        user.setLocale("en_GB");
        user.setTimeZone("Europe/Berlin");

        ProfileBean bean = new ProfileBean();
        bean.copyFrom(user);

        assertEquals("u1", bean.getId());
        assertEquals("jake", bean.getUserName());
        assertEquals("Jake", bean.getScreenName());
        assertEquals("Jake Fear", bean.getFullName());
        assertEquals("jake@example.com", bean.getEmailAddress());
        assertEquals("en_GB", bean.getLocale());
        assertEquals("Europe/Berlin", bean.getTimeZone());
        assertNull(bean.getPassword());
        assertNull(bean.getPasswordText());
        assertNull(bean.getPasswordConfirm());
    }
}
