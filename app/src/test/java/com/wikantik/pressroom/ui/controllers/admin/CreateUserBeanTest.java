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
package com.wikantik.pressroom.ui.controllers.admin;

import java.util.List;

import com.wikantik.pressroom.business.MockWeblogger;
import com.wikantik.pressroom.pojos.GlobalPermission;
import com.wikantik.pressroom.pojos.User;
import com.wikantik.pressroom.TestUtils;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link CreateUserBean}, the form object behind the admin user
 * screens. What it copies -- and just as importantly what it refuses to copy --
 * decides which fields an admin can change on an existing account.
 */
class CreateUserBeanTest {

    private MockWeblogger weblogger;

    @BeforeEach
    void setUp() {
        weblogger = MockWeblogger.attached();
    }

    @AfterEach
    void tearDown() {
        weblogger.detach();
    }

    @Test
    void copyToWritesTheProfileFieldsOntoTheUser() {
        CreateUserBean bean = new CreateUserBean();
        bean.setScreenName("Jake");
        bean.setFullName("Jake Fear");
        bean.setEmailAddress("jake@example.com");
        bean.setLocale("en_GB");
        bean.setTimeZone("Europe/Berlin");
        bean.setEnabled(Boolean.TRUE);

        User user = new User();
        bean.copyTo(user);

        assertEquals("Jake", user.getScreenName());
        assertEquals("Jake Fear", user.getFullName());
        assertEquals("jake@example.com", user.getEmailAddress());
        assertEquals("en_GB", user.getLocale());
        assertEquals("Europe/Berlin", user.getTimeZone());
        assertEquals(Boolean.TRUE, user.getEnabled());
    }

    @Test
    void copyToLeavesIdentityAndCredentialsAlone() {
        // Username and password are deliberately not part of copyTo: the
        // username identifies an existing account and the password has to go
        // through the encoder, so letting the form overwrite either directly
        // would be a way to hijack an account.
        CreateUserBean bean = new CreateUserBean();
        bean.setUserName("attacker");
        bean.setPassword("plaintext");

        User user = new User();
        user.setUserName("victim");
        user.setPassword(TestUtils.TEST_PASSWORD_HASH);
        bean.copyTo(user);

        assertEquals("victim", user.getUserName());
        assertEquals(TestUtils.TEST_PASSWORD_HASH, user.getPassword());
    }

    @Test
    void copyFromReadsTheProfile() {
        User user = new User();
        user.setId("u1");
        user.setUserName("jake");
        user.setPassword(TestUtils.TEST_PASSWORD_HASH);
        user.setScreenName("Jake");
        user.setFullName("Jake Fear");
        user.setEmailAddress("jake@example.com");
        user.setLocale("en_GB");
        user.setTimeZone("Europe/Berlin");
        user.setEnabled(Boolean.FALSE);

        CreateUserBean bean = new CreateUserBean();
        bean.copyFrom(user);

        assertEquals("u1", bean.getId());
        assertEquals("jake", bean.getUserName());
        assertEquals(TestUtils.TEST_PASSWORD_HASH, bean.getPassword());
        assertEquals("Jake", bean.getScreenName());
        assertEquals("Jake Fear", bean.getFullName());
        assertEquals("jake@example.com", bean.getEmailAddress());
        assertEquals("en_GB", bean.getLocale());
        assertEquals("Europe/Berlin", bean.getTimeZone());
        assertEquals(Boolean.FALSE, bean.getEnabled());
    }

    /**
     * The form bean is pure: the data binder instantiates it, so it has no way
     * to be handed a collaborator, and it must therefore not go looking for one.
     * The admin checkbox is the controller's to set (see
     * {@code UserEditControllerTest}); before the DI wave {@code copyFrom}
     * reached the static locator for a user manager to ask,
     * which is why this test stands one up and asserts it was never consulted.
     */
    @Test
    void copyFromNeverConsultsTheUserManager() throws Exception {
        User user = new User();
        user.setUserName("jake");
        when(weblogger.userManager().checkPermission(any(GlobalPermission.class), eq(user))).thenReturn(true);

        CreateUserBean bean = new CreateUserBean();
        bean.copyFrom(user);

        assertFalse(bean.isAdministrator(),
                "copyFrom must not decide the admin flag; that is the controller's job");
        verify(weblogger.userManager(), never()).checkPermission(any(), any());
    }

    @Test
    void aFreshBeanIsDisabledAndNotAnAdmin() {
        // The create form starts from this, so the defaults decide what an admin
        // gets if they save without touching the checkboxes.
        CreateUserBean bean = new CreateUserBean();

        assertEquals(Boolean.FALSE, bean.getEnabled());
        assertFalse(bean.isAdministrator());
        assertNull(bean.getUserName());
        assertNull(bean.getPassword());
        assertTrue(bean.getList().isEmpty());
    }

    @Test
    void theEnabledFlagIsCarriedBothWays() {
        // It is the difference between an account that can sign in and one that
        // cannot, so the getter has to report what was set.
        CreateUserBean bean = new CreateUserBean();
        bean.setEnabled(Boolean.TRUE);

        assertEquals(Boolean.TRUE, bean.getEnabled());
    }

    @Test
    void theListPropertyIsCarriedBothWays() {
        CreateUserBean bean = new CreateUserBean();
        List<String> values = List.of("one", "two");
        bean.setList(values);

        assertEquals(values, bean.getList());
    }
}
