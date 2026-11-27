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

import java.util.List;
import java.util.Map;

import com.wikantik.pressroom.pojos.RuntimeConfigProperty;
import com.wikantik.pressroom.util.TokenGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Secrets the site makes for itself the first time it starts, so a deployer
 * has nothing to set: each is a {@code pressroom_properties} row seeded when
 * the properties manager initializes, beside the rows seeded from
 * {@code runtimeConfigDefs.xml}.
 *
 * <p>They are deliberately not property definitions: the settings screens and
 * the settings API list only definitions, so a secret never reaches either,
 * and the definitions' seeding logs every value it writes, which this does
 * not. A value in the startup configuration ({@code pressroom-custom.properties}
 * or its {@code PRESSROOM_} variable) seeds the row instead of a random one;
 * once the row exists it is the secret, and a restart keeps it. Changing the
 * row rotates the secret, and everything signed with the old one stops
 * verifying.
 */
public final class GeneratedSecrets {

    private static final Logger log = LoggerFactory.getLogger(GeneratedSecrets.class);

    /** Signs the one-click unsubscribe links in reader mail ({@code UnsubscribeTokens}). */
    public static final String MAIL_UNSUBSCRIBE_SECRET = "mail.unsubscribe.secret";

    private static final List<String> NAMES = List.of(MAIL_UNSUBSCRIBE_SECRET);

    private GeneratedSecrets() {
    }

    /** Every generated secret's property name. */
    public static List<String> names() {
        return NAMES;
    }

    /**
     * Fills each generated secret that is missing or blank in {@code props}:
     * with the startup configuration's value when it has one, otherwise with
     * 256 random bits. A blank row is filled in place. Logs the name, never
     * the value.
     */
    public static void seedMissing(Map<String, RuntimeConfigProperty> props) {
        for (String name : NAMES) {
            RuntimeConfigProperty row = props.get(name);
            if (row != null && row.getValue() != null && !row.getValue().isBlank()) {
                continue;
            }
            String configured = WebloggerConfig.getProperty(name);
            boolean fromConfig = configured != null && !configured.isBlank();
            String value = fromConfig ? configured.trim() : TokenGenerator.newToken();
            if (row == null) {
                props.put(name, new RuntimeConfigProperty(name, value));
            } else {
                row.setValue(value);
            }
            log.info("Secret {} stored in pressroom_properties, {}", name,
                    fromConfig ? "from the startup configuration" : "generated");
        }
    }
}
