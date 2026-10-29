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

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

import com.wikantik.pressroom.util.UUIDGenerator;
import com.wikantik.pressroom.TestUtils;
import com.wikantik.pressroom.business.Weblogger;
import com.wikantik.pressroom.pojos.User;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.pojos.WeblogCategory;
import com.wikantik.pressroom.pojos.WeblogEntry;
import com.wikantik.pressroom.pojos.WeblogEntrySearchCriteria;
import com.wikantik.pressroom.pojos.WeblogPermission;
import org.eclipse.persistence.descriptors.ClassDescriptor;
import org.eclipse.persistence.sessions.Session;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;

/**
 * Materialising a stored row generates no random UUID.
 *
 * <p>Every entity assigns itself a UUID in its field initialiser so that a
 * {@code new} object has an id before anything can read it. EclipseLink builds
 * the objects that hold loaded rows (and its unit-of-work clones and backup
 * clones) through the same no-argument constructor, so each of those ran
 * {@code UUID.randomUUID()} -- a shared, locked {@code SecureRandom} -- only to
 * overwrite the value with the stored id a moment later. Profiling put that at
 * ~7% of CPU under reader load.
 *
 * <p>The generator is observed with {@code mockStatic} in call-real-methods
 * mode, which is thread-local: EclipseLink runs the queries on the test
 * thread, and nothing in production is changed to make it countable.
 */
class EntityLoadIdGenerationTest {

    private static final int ENTRIES = 5;

    private User user;
    private Weblog weblog;

    @BeforeEach
    void setUp() throws Exception {
        TestUtils.setupWeblogger();
        user = TestUtils.setupUser("uuidonload");
        weblog = TestUtils.setupWeblog("uuidonload", user);
        for (int i = 0; i < ENTRIES; i++) {
            TestUtils.setupWeblogEntry("uuid-on-load-" + i, weblog, user);
        }
        TestUtils.endSession(true);
    }

    @AfterEach
    void tearDown() throws Exception {
        TestUtils.teardownWeblog(weblog.getId());
        TestUtils.teardownUser(user.getUserName());
        TestUtils.endSession(true);
    }

    @Test
    void loadingStoredEntitiesGeneratesNoIds() throws Exception {
        Weblogger roller = TestUtils.weblogger();
        int loaded = 0;

        try (MockedStatic<UUIDGenerator> generator = mockStatic(UUIDGenerator.class, CALLS_REAL_METHODS)) {
            User storedUser = roller.getUserManager().getUserByUserName(user.getUserName());
            Weblog storedWeblog = roller.getWeblogManager().getWeblogByHandle(weblog.getHandle());
            loaded += 2;

            for (WeblogCategory category : roller.getWeblogEntryManager().getWeblogCategories(storedWeblog)) {
                assertNotNull(category.getId());
                loaded++;
            }
            for (WeblogPermission permission : roller.getUserManager().getWeblogPermissions(storedUser)) {
                assertNotNull(permission.getId());
                loaded++;
            }
            WeblogEntrySearchCriteria criteria = new WeblogEntrySearchCriteria();
            criteria.setWeblog(storedWeblog);
            List<WeblogEntry> entries = roller.getWeblogEntryManager().getWeblogEntries(criteria);
            assertEquals(ENTRIES, entries.size(), "the fixture's entries must load, or this test is vacuous");
            for (WeblogEntry entry : entries) {
                assertNotNull(entry.getId());
                assertNotNull(entry.getCategory().getId());
                loaded++;
            }

            generator.verify(UUIDGenerator::generateUUID, never());
        }
        assertTrue(loaded > ENTRIES + 2, "expected users, weblogs, categories, permissions and entries");
    }

    @Test
    void everyMappedEntityIsMaterialisedWithoutGeneratingAnId() throws Exception {
        List<String> checked = new ArrayList<>();

        for (ClassDescriptor descriptor : session().getDescriptors().values()) {
            Class<?> type = descriptor.getJavaClass();
            if (Modifier.isAbstract(type.getModifiers()) || descriptor.isAggregateDescriptor()) {
                continue;
            }
            Object built;
            try (MockedStatic<UUIDGenerator> generator = mockStatic(UUIDGenerator.class, CALLS_REAL_METHODS)) {
                built = descriptor.getObjectBuilder().buildNewInstance();
                generator.verify(UUIDGenerator::generateUUID, never());
            }
            assertEquals(type, built.getClass());
            Method getId = idGetter(type);
            if (getId != null) {
                assertNull(getId.invoke(built), type.getSimpleName()
                        + " must leave its id for the stored row to fill in");
            }
            checked.add(type.getSimpleName());
        }

        assertTrue(checked.containsAll(List.of("Weblog", "WeblogEntry", "User", "WeblogCategory",
                "WeblogEntryTag", "WeblogPermission", "ReaderComment", "ApiToken")),
                "the core entities must be among the mapped descriptors: " + checked);
    }

    @Test
    void entitiesBuiltByTheApplicationStillGenerateTheirIdAfterALoad() throws Exception {
        // The suppression is scoped to EclipseLink's own instantiation; it must
        // not leak to the next object the application builds on this thread.
        TestUtils.weblogger().getWeblogManager().getWeblogByHandle(weblog.getHandle());

        WeblogEntry first = new WeblogEntry();
        WeblogEntry second = new WeblogEntry();

        assertNotNull(first.getId());
        assertFalse(first.getId().isBlank());
        assertNotEquals(first.getId(), second.getId());
    }

    /**
     * Characterisation: passes against the code before this change too. A new
     * entity's constructor-assigned id is the one that is stored and loaded.
     */
    @Test
    void aNewEntityKeepsTheIdItWasBornWithThroughAStoreAndReload() throws Exception {
        WeblogCategory category = new WeblogCategory(
                TestUtils.getManagedWebsite(weblog), "uuid-round-trip", null, null);
        String bornWith = category.getId();
        TestUtils.weblogger().getWeblogEntryManager().saveWeblogCategory(category);
        TestUtils.endSession(true);

        WeblogCategory reloaded = TestUtils.weblogger().getWeblogEntryManager().getWeblogCategory(bornWith);

        assertNotNull(reloaded, "the category must be stored under the id it was constructed with");
        assertEquals("uuid-round-trip", reloaded.getName());
        assertEquals(bornWith, reloaded.getId());
    }

    private static Method idGetter(Class<?> type) {
        try {
            Method getter = type.getMethod("getId");
            return getter.getReturnType() == String.class ? getter : null;
        } catch (NoSuchMethodException e) {
            return null;
        }
    }

    private static Session session() throws ReflectiveOperationException {
        Field field = JPAUserManagerImpl.class.getDeclaredField("strategy");
        field.setAccessible(true);
        JPAPersistenceStrategy strategy =
                (JPAPersistenceStrategy) field.get(TestUtils.weblogger().getUserManager());
        return strategy.getEntityManager(false).unwrap(Session.class);
    }
}
