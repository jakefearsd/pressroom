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

import java.lang.reflect.Field;
import java.util.Properties;

/**
 * Test fixture: sets one key of the static {@link WebloggerConfig} -- the
 * layer a {@code ROLLER_*} environment variable lands in, since
 * {@code applyEnvironmentOverrides} writes into it once per JVM -- for the
 * duration of a try-with-resources block, and on close puts back exactly what
 * was there before, absence included.
 *
 * <p>The static config is process-global and has no general setter, so this
 * reaches its {@code Properties} reflectively, the way several single-class
 * helpers in this suite already do. Restoring rather than clearing is the
 * point: a key left behind would leak into every later test in the JVM, the
 * same shape as the global absolute-URL pollution that once made CI
 * order-dependent.
 */
public final class StaticConfigOverride implements AutoCloseable {

    private final String name;
    private final String previous;

    private StaticConfigOverride(String name, String previous) {
        this.name = name;
        this.previous = previous;
    }

    /** Sets {@code name} to {@code value}; {@code null} removes the key for the block. */
    public static StaticConfigOverride set(String name, String value) {
        Properties config = staticConfig();
        String previous = config.getProperty(name);
        put(config, name, value);
        return new StaticConfigOverride(name, previous);
    }

    @Override
    public void close() {
        put(staticConfig(), name, previous);
    }

    private static void put(Properties config, String name, String value) {
        if (value == null) {
            config.remove(name);
        } else {
            config.setProperty(name, value);
        }
    }

    private static Properties staticConfig() {
        try {
            Field field = WebloggerConfig.class.getDeclaredField("config");
            field.setAccessible(true);
            return (Properties) field.get(null);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("WebloggerConfig.config is no longer reachable", e);
        }
    }
}
