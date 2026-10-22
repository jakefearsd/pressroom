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
package org.apache.roller.weblogger.business.jpa;

import java.util.List;
import java.util.Map;

import org.apache.roller.weblogger.TestUtils;
import org.apache.roller.weblogger.business.PropertiesManager;
import org.apache.roller.weblogger.config.WebloggerRuntimeConfig;
import org.apache.roller.weblogger.pojos.RuntimeConfigProperty;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Runtime properties are read on every request, many times a render, and
 * change only when an administrator saves them. The values are remembered
 * between saves; a save through the {@link PropertiesManager} is read by the
 * very next request, and nothing a unit of work did without committing is
 * ever remembered.
 */
class RuntimeConfigCacheTest {

    private static final String NAME = "site.description";

    private String original;

    @BeforeEach
    void setUp() throws Exception {
        TestUtils.setupWeblogger();
        TestUtils.endSession(false);
        original = WebloggerRuntimeConfig.getProperty(NAME);
        TestUtils.endSession(false);
    }

    @AfterEach
    void tearDown() throws Exception {
        TestUtils.endSession(false);
        RuntimeConfigProperty prop = manager().getProperty(NAME);
        prop.setValue(original);
        manager().saveProperty(prop);
        TestUtils.endSession(true);
    }

    @Test
    void aWarmReadAsksNothingOfTheDatabase() throws Exception {
        WebloggerRuntimeConfig.getProperty(NAME);
        TestUtils.endSession(false);

        List<String> queries = QueryRecorder.during(() -> {
            WebloggerRuntimeConfig.getProperty(NAME);
            WebloggerRuntimeConfig.getProperty("site.name");
            WebloggerRuntimeConfig.getBooleanProperty("users.registration.enabled");
        });

        assertEquals(List.of(), QueryRecorder.on(queries, RuntimeConfigProperty.class),
                "a remembered value needs no find: " + queries);
    }

    /** As GlobalConfigController saves: the managed map, edited and saved. */
    @Test
    void anAdminSaveOfThePropertiesMapIsReadByTheNextRequest() throws Exception {
        assertEquals(original, WebloggerRuntimeConfig.getProperty(NAME));
        TestUtils.endSession(false);

        Map<String, RuntimeConfigProperty> props = manager().getProperties();
        props.get(NAME).setValue("saved from the admin screen");
        manager().saveProperties(props);
        TestUtils.weblogger().flush();
        TestUtils.endSession(false);

        assertEquals("saved from the admin screen", WebloggerRuntimeConfig.getProperty(NAME));
    }

    /** As AdminApi and SetupController save: one property, read, edited and saved. */
    @Test
    void anApiSaveOfOnePropertyIsReadByTheNextRequest() throws Exception {
        assertEquals(original, WebloggerRuntimeConfig.getProperty(NAME));
        TestUtils.endSession(false);

        RuntimeConfigProperty prop = manager().getProperty(NAME);
        prop.setValue("saved through the API");
        manager().saveProperty(prop);
        TestUtils.weblogger().flush();
        TestUtils.endSession(false);

        assertEquals("saved through the API", WebloggerRuntimeConfig.getProperty(NAME));
        assertEquals("saved through the API", manager().getProperties().get(NAME).getValue());
    }

    @Test
    void aSaveIsReadInsideItsOwnUnitOfWork() throws Exception {
        assertEquals(original, WebloggerRuntimeConfig.getProperty(NAME));
        TestUtils.endSession(false);

        RuntimeConfigProperty prop = manager().getProperty(NAME);
        prop.setValue("not yet committed");
        manager().saveProperty(prop);

        assertEquals("not yet committed", WebloggerRuntimeConfig.getProperty(NAME));
    }

    @Test
    void aRolledBackSaveIsNotRemembered() throws Exception {
        RuntimeConfigProperty prop = manager().getProperty(NAME);
        prop.setValue("rolled back");
        manager().saveProperty(prop);
        WebloggerRuntimeConfig.getProperty(NAME);
        TestUtils.endSession(false);

        assertEquals(original, WebloggerRuntimeConfig.getProperty(NAME));
    }

    /**
     * GlobalConfigController edits the managed map before validating it, and
     * on a validation error renders the form again without saving -- reading
     * runtime properties as it does.
     */
    @Test
    void anUnsavedEditIsNotRemembered() throws Exception {
        manager().getProperties().get(NAME).setValue("invalid and never saved");
        WebloggerRuntimeConfig.getProperty(NAME);
        TestUtils.endSession(false);

        assertEquals(original, WebloggerRuntimeConfig.getProperty(NAME));
    }

    /**
     * Many callers (and most tests) set runtime properties by editing the
     * managed map and letting a later commit write it. The edit is read
     * inside the unit of work, as it always was...
     */
    @Test
    void anEditOfTheManagedMapIsReadInItsOwnUnitOfWork() throws Exception {
        assertEquals(original, WebloggerRuntimeConfig.getProperty(NAME));

        manager().getProperties().get(NAME).setValue("edited in place");

        assertEquals("edited in place", WebloggerRuntimeConfig.getProperty(NAME));
    }

    /** ...and, once a commit writes it without {@code saveProperties}, by the next request. */
    @Test
    void anEditCommittedWithoutASaveIsReadByTheNextRequest() throws Exception {
        assertEquals(original, WebloggerRuntimeConfig.getProperty(NAME));
        TestUtils.endSession(false);

        manager().getProperties().get(NAME).setValue("committed by a flush");
        TestUtils.weblogger().flush();
        TestUtils.endSession(false);

        assertEquals("committed by a flush", WebloggerRuntimeConfig.getProperty(NAME));
    }

    @Test
    void anUnknownPropertyIsNull() throws Exception {
        assertNull(WebloggerRuntimeConfig.getProperty("no.such.runtime.property"));
        assertNull(manager().getProperty("no.such.runtime.property"));
    }

    private static PropertiesManager manager() {
        return TestUtils.weblogger().getPropertiesManager();
    }
}
