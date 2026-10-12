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
package org.apache.roller.weblogger.business;

import org.apache.roller.weblogger.TestUtils;
import org.apache.roller.weblogger.business.jpa.JPAUserManagerImpl;
import org.apache.roller.weblogger.pojos.GlobalPermission;
import org.apache.roller.weblogger.pojos.User;
import org.apache.roller.weblogger.ui.core.RollerContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Reader accounts: bound to an SSO identity, granted only the {@code reader}
 * role, never found by the local-account email lookup.
 */
public class ReaderAccountStoreTest {

    private static final String ISSUER = "https://issuer.test";

    private final List<String> created = new ArrayList<>();

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.setupWeblogger();
    }

    @AfterEach
    public void tearDown() throws Exception {
        for (String name : created) {
            TestUtils.teardownUser(name);
        }
        TestUtils.endSession(true);
    }

    @Test
    void addReaderGrantsOnlyReaderAndLookupFindsDisabledToo() throws Exception {
        UserManager mgr = TestUtils.weblogger().getUserManager();
        User u = newBoundUser("reader-aaaaaaaaaa", ISSUER, "sub-" + UUID.randomUUID());
        mgr.addReader(u);
        created.add(u.getUserName());
        TestUtils.endSession(true);

        User found = mgr.getUserBySsoIdentity(ISSUER, u.getSsoSubject());
        assertEquals(u.getUserName(), found.getUserName());
        assertEquals(List.of("reader"), mgr.getRoles(found));

        found.setEnabled(Boolean.FALSE);
        mgr.saveUser(found);
        TestUtils.endSession(true);
        assertNotNull(mgr.getUserBySsoIdentity(ISSUER, u.getSsoSubject()),
                "a disabled bound account must still be found, or a new one would replace it");
    }

    @Test
    void addReaderIgnoresFirstUserAdminEvenOnAnEmptyTable() throws Exception {
        // users.firstUserAdmin=true is the shipped default; simulate an empty table
        JPAUserManagerImpl real = (JPAUserManagerImpl) TestUtils.weblogger().getUserManager();
        JPAUserManagerImpl spy = Mockito.spy(real);
        Mockito.doReturn(List.of()).when(spy).getUsers(Mockito.any(), Mockito.any(), Mockito.any(),
                Mockito.anyInt(), Mockito.anyInt());
        User u = newBoundUser("reader-bbbbbbbbbb", ISSUER, "sub-" + UUID.randomUUID());
        spy.addReader(u);
        created.add(u.getUserName());
        TestUtils.endSession(true);
        assertEquals(List.of("reader"), real.getRoles(u));
    }

    @Test
    void getUserByEmailNeverReturnsABoundAccount() throws Exception {
        // a local "zz..." account sorts AFTER "reader-..." by username, the order getUserByEmail uses
        String email = "shared-" + UUID.randomUUID() + "@example.com";
        User local = TestUtils.setupUser("zzlocal" + System.nanoTime());
        created.add(local.getUserName());
        local.setEmailAddress(email);
        UserManager mgr = TestUtils.weblogger().getUserManager();
        mgr.saveUser(local);
        User reader = newBoundUser("reader-cccccccccc", ISSUER, "sub-" + UUID.randomUUID());
        reader.setEmailAddress(email);
        mgr.addReader(reader);
        created.add(reader.getUserName());
        TestUtils.endSession(true);
        assertEquals(local.getUserName(), mgr.getUserByEmail(email).getUserName());
    }

    @Test
    void aBoundAccountCannotBeGivenAPassword() {
        User u = newBoundUser("reader-dddddddddd", ISSUER, "sub-x");
        assertThrows(IllegalStateException.class, () -> u.resetPassword("anything-long-enough"));
    }

    @Test
    void theMarkerMatchesNoPasswordAndNeverThrows() {
        PasswordEncoder enc = RollerContext.createPasswordEncoder();
        for (String raw : new String[] {"", "!", "sso-bound", User.SSO_PASSWORD_MARKER, "{bcrypt}!sso-bound"}) {
            assertFalse(enc.matches(raw, User.SSO_PASSWORD_MARKER), raw);
        }
    }

    @Test
    void readerImpliesNoGlobalAction() throws Exception {
        User u = newBoundUser("reader-eeeeeeeeee", ISSUER, "sub-y");
        GlobalPermission readerPerm = GlobalPermission.impliedByRoles(u, List.of("reader"));
        for (String action : List.of(GlobalPermission.LOGIN, GlobalPermission.WEBLOG, GlobalPermission.ADMIN)) {
            assertFalse(readerPerm.implies(new GlobalPermission(List.of(action))), action);
        }
    }

    private static User newBoundUser(String userName, String iss, String sub) {
        User u = new User();
        u.setUserName(userName);
        u.setScreenName("Reader");
        u.setFullName("Reader");
        u.setEmailAddress(userName + "@example.com");
        u.setPassword(User.SSO_PASSWORD_MARKER);
        u.setDateCreated(new Date());
        u.setEnabled(Boolean.TRUE);
        u.setSsoIssuer(iss);
        u.setSsoSubject(sub);
        return u;
    }
}
