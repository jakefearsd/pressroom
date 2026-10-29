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

package com.wikantik.pressroom.business.startup;

import java.io.InputStream;
import java.net.URL;

/**
 * Reads schema migrations from the classpath.
 *
 * <p>Migrations are copied by the build from {@code bin/db/migrations} into
 * {@code /dbmigrations} so they ship inside the WAR. Use {@link MigrationCatalog}
 * to discover which migrations exist.
 */
public class ClasspathDatabaseScriptProvider implements DatabaseScriptProvider {

    private static String resourcePath(String path) {
        return "/" + MigrationCatalog.MIGRATIONS_PATH + "/" + path;
    }

    /**
     * @see com.wikantik.pressroom.business.startup.DatabaseScriptProvider#getDatabaseScript(java.lang.String)
     */
    @Override
    public InputStream getDatabaseScript(String path) {
        // ClasspathDatabaseScriptProvider.class, not this.getClass() (an
        // UI_INHERITANCE_UNSAFE_GETRESOURCE risk if ever subclassed) --
        // matching getScriptURL just below, which already uses the class
        // literal for the same lookup.
        return ClasspathDatabaseScriptProvider.class.getResourceAsStream(resourcePath(path));
    }

    /**
     * Gets the script url, for logging which file was actually resolved.
     *
     * @param path
     *            the migration file name, e.g. {@code V002__baseline_schema.sql}
     *
     * @return the script url, or null if not on the classpath
     */
    public URL getScriptURL(String path) {
        return ClasspathDatabaseScriptProvider.class.getResource(resourcePath(path));
    }

}
