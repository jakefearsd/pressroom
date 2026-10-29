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
package com.wikantik.pressroom.testing;

import org.junit.platform.launcher.LauncherSession;
import org.junit.platform.launcher.LauncherSessionListener;

/**
 * Starts the PostgreSQL container and points Roller's configuration at it
 * before any test class is loaded.
 *
 * <p>This has to happen this early because {@code WebloggerConfig} reads its
 * properties in a static initializer, once per JVM. The database coordinates
 * are only known after Testcontainers has assigned a port, so if any test class
 * touches {@code WebloggerConfig} first — even one that never uses the database
 * — the whole run is stuck with the default JNDI datasource and every
 * database-backed test fails with "cannot locate JNDI DataSource".
 *
 * <p>Registered through {@code META-INF/services/
 * org.junit.platform.launcher.LauncherSessionListener}, which the JUnit
 * Platform loads before test discovery.
 */
public class PressroomTestBootstrap implements LauncherSessionListener {

    @Override
    public void launcherSessionOpened(LauncherSession session) {
        PressroomDatabaseExtension.ensureSchema();
    }
}
