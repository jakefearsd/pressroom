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

import com.wikantik.pressroom.config.RuntimeConfigAttachment;
import com.wikantik.pressroom.config.WebloggerRuntimeConfig;
import com.wikantik.pressroom.pojos.RuntimeConfigProperty;
import com.wikantik.pressroom.pojos.Weblog;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.when;

/**
 * Verifies the {@link MockWeblogger} test seam, because every controller and
 * rendering test that takes a mocked facade depends on it.
 *
 * <p>Since the 2026-08-22 plan deleted the static service locator there is no
 * global to install the mock into: a test hands {@link MockWeblogger#weblogger()}
 * to the class under test. The one residual global is the runtime-config
 * attachment (spec Decision 8), and that is what {@link MockWeblogger#attached()}
 * / {@link MockWeblogger#detach()} manage -- with save/restore semantics, since
 * the database-backed tests leave the real tier's manager attached for the whole
 * JVM and must find it still there afterwards.
 */
class MockWebloggerTest {

    @Test
    void theFacadeHandsBackTheStubbableManagers() throws Exception {
        MockWeblogger mocks = MockWeblogger.create();

        Weblog expected = new Weblog();
        when(mocks.weblogManager().getWeblogByHandle("it_weblog")).thenReturn(expected);

        // The path a controller actually takes, through the facade it was given.
        Weblog actual = mocks.weblogger().getWeblogManager().getWeblogByHandle("it_weblog");

        assertSame(expected, actual,
                "Stubbing the manager returned by MockWeblogger must affect the manager "
                        + "reached via the facade, or controller tests cannot stub anything");
        assertSame(mocks.userManager(), mocks.weblogger().getUserManager());
        assertSame(mocks.propertiesManager(), mocks.weblogger().getPropertiesManager());
    }

    @Test
    void createTouchesNoGlobalState() throws Exception {
        try (RuntimeConfigAttachment _ = RuntimeConfigAttachment.preserve()) {
            WebloggerRuntimeConfig.attach(null);

            MockWeblogger mocks = MockWeblogger.create();
            when(mocks.propertiesManager().getProperty("site.name"))
                    .thenReturn(property("site.name", "mocked"));

            assertNull(WebloggerRuntimeConfig.getProperty("site.name"),
                    "create() must not attach the mock's manager; nothing was attached, so reads answer null");
            mocks.detach(); // a no-op for a mock that never attached
            assertNull(WebloggerRuntimeConfig.getProperty("site.name"));
        }
    }

    @Test
    void attachedRoutesRuntimeConfigReadsToTheMockUntilDetached() throws Exception {
        try (RuntimeConfigAttachment _ = RuntimeConfigAttachment.preserve()) {
            MockWeblogger outer = MockWeblogger.attached();
            when(outer.propertiesManager().getProperty("site.name"))
                    .thenReturn(property("site.name", "outer"));
            assertEquals("outer", WebloggerRuntimeConfig.getProperty("site.name"));

            MockWeblogger inner = MockWeblogger.attached();
            when(inner.propertiesManager().getProperty("site.name"))
                    .thenReturn(property("site.name", "inner"));
            assertEquals("inner", WebloggerRuntimeConfig.getProperty("site.name"),
                    "the second attach must take over");

            inner.detach();
            assertEquals("outer", WebloggerRuntimeConfig.getProperty("site.name"),
                    "detach must hand the attachment back to what was attached before, "
                            + "not null and not the inner mock");

            outer.detach();
        }
    }

    private static RuntimeConfigProperty property(String name, String value) {
        RuntimeConfigProperty p = new RuntimeConfigProperty();
        p.setName(name);
        p.setValue(value);
        return p;
    }
}
