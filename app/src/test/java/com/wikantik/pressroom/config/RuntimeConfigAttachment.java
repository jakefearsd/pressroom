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
package com.wikantik.pressroom.config;

import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.business.PropertiesManager;
import com.wikantik.pressroom.pojos.RuntimeConfigProperty;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Test fixture: points {@link WebloggerRuntimeConfig} at a {@link PropertiesManager}
 * for the duration of a try-with-resources block and restores whatever was
 * attached before on close.
 *
 * <p>This replaces the static-locator mocks the DI wave left behind for its
 * Task 19: the runtime-config facade no longer reaches the tier through the
 * static locator, it reads the one manager attached at bootstrap (spec
 * Decision 8). The attachment is process-global -- a DB-backed
 * test class that bootstrapped the real tier earlier in the JVM has its manager
 * attached -- which is why this always restores rather than clearing.
 */
public final class RuntimeConfigAttachment implements AutoCloseable {

    private final PropertiesManager previous;

    private RuntimeConfigAttachment(PropertiesManager previous) {
        this.previous = previous;
    }

    /** Attaches {@code manager}; {@code null} leaves the facade answering null, as before bootstrap. */
    public static RuntimeConfigAttachment of(PropertiesManager manager) {
        return new RuntimeConfigAttachment(WebloggerRuntimeConfig.attach(manager));
    }

    /**
     * Changes nothing now, but restores whatever is attached at this moment on
     * close -- for a test that bootstraps throwaway providers (each of which
     * attaches its own manager) and must leave the suite's real tier attached.
     */
    public static RuntimeConfigAttachment preserve() {
        PropertiesManager current = WebloggerRuntimeConfig.attach(null);
        WebloggerRuntimeConfig.attach(current);
        return new RuntimeConfigAttachment(current);
    }

    /**
     * Attaches a mock manager that answers exactly the given runtime
     * properties (pairs of name, value) and {@code null} for everything else.
     */
    public static RuntimeConfigAttachment answering(String... nameValuePairs) {
        if (nameValuePairs.length % 2 != 0) {
            throw new IllegalArgumentException("name/value pairs expected");
        }
        PropertiesManager manager = mock(PropertiesManager.class);
        try {
            for (int i = 0; i < nameValuePairs.length; i += 2) {
                String name = nameValuePairs[i];
                when(manager.getProperty(name))
                        .thenReturn(new RuntimeConfigProperty(name, nameValuePairs[i + 1]));
            }
        } catch (WebloggerException impossible) {
            // getProperty declares it; stubbing a mock never actually throws.
            throw new AssertionError("stubbing a mock must not throw", impossible);
        }
        return of(manager);
    }

    @Override
    public void close() {
        WebloggerRuntimeConfig.attach(previous);
    }
}
