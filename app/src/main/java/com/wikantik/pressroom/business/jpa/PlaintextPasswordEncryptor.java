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
package com.wikantik.pressroom.business.jpa;

import org.eclipse.persistence.security.Securable;

/**
 * EclipseLink login encryptor ({@code eclipselink.login.encryptor}) that
 * leaves the database password exactly as configured.
 *
 * <p>EclipseLink passes {@code jakarta.persistence.jdbc.password} through
 * {@link #decryptPassword} before logging in. Its default encryptor treats
 * any value that parses as hex as one of its own encrypted passwords, so an
 * even-length all-hex plaintext (what {@code openssl rand -hex 24} prints)
 * fails decryption and the persistence unit never deploys
 * (EclipseLink-7360, "Database password was encrypted by deprecated
 * algorithm"). Pressroom's password always arrives as plaintext from
 * configuration or the environment, and EclipseLink's in-memory encryption
 * uses a key hard-coded in EclipseLink, so it protects nothing worth the
 * misreading.
 *
 * <p>EclipseLink instantiates this reflectively, so it needs the public
 * no-argument constructor.
 */
public class PlaintextPasswordEncryptor implements Securable {

    /** Returns the password unchanged. */
    @Override
    public String encryptPassword(String password) {
        return password;
    }

    /** Returns the password unchanged: it was never encrypted. */
    @Override
    public String decryptPassword(String password) {
        return password;
    }
}
