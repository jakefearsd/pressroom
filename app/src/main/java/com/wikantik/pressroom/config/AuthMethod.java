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
package com.wikantik.pressroom.config;

public enum AuthMethod {
    /**
     * Database-backed authentication, the only method Roller supports. The
     * LDAP, OpenID, db-openid and container-managed options were removed along
     * with their implementations; the enum is kept so an unsupported value in
     * pressroom.properties still fails loudly at startup rather than silently
     * behaving like "db".
     */
    ROLLERDB("db");

    private final String propertyName;

    AuthMethod(String propertyName) {
        this.propertyName = propertyName;
    }

    public String getPropertyName() {
        return propertyName;
    }

    public static AuthMethod getAuthMethod(String propertyName) {
        for (AuthMethod test : values()) {
            if (test.getPropertyName().equals(propertyName)) {
                return test;
            }
        }
        throw new IllegalArgumentException("Unknown authentication.method property value: "
                + propertyName + " defined in Roller properties file.");
    }

}
