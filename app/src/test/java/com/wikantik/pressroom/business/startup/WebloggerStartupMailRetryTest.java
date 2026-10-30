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
package com.wikantik.pressroom.business.startup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;

import java.net.ConnectException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.logging.log4j.Level;
import com.wikantik.pressroom.business.MailProvider;
import com.wikantik.pressroom.config.WebloggerConfig;
import com.wikantik.pressroom.ui.core.security.sso.SsoLogCapture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

/**
 * A mail relay that does not answer while Pressroom boots is tried again later.
 *
 * <p>The mail provider used to be built once, at boot. A relay that was down
 * for that moment left mail "not configured" until the next restart, which
 * quietly broke password reset; the only trace was a WARN whose line breaks
 * printed as {@code \n}. Now a configured relay is retried on the next use, at
 * most once per {@link WebloggerStartup#MAIL_RETRY_INTERVAL_MILLIS}, so a
 * relay that stays down costs one connection attempt a minute, not one per
 * request. A missing or JNDI configuration is not retried: no later attempt
 * can succeed.
 */
class WebloggerStartupMailRetryTest {

    private static final long BOOT = 1_000_000L;

    private final AtomicLong now = new AtomicLong(BOOT);
    private final AtomicInteger attempts = new AtomicInteger();
    private final Map<String, String> config = new HashMap<>();
    private MailProvider saved;

    @BeforeEach
    void save() {
        saved = WebloggerStartup.currentMailProvider();
        config.put("mail.configurationType", "properties");
        config.put("mail.hostname", "smtp.example.test");
        config.put("mail.port", "587");
    }

    @AfterEach
    void restore() {
        WebloggerStartup.useMailProviderFactory(null, null);
        WebloggerStartup.installMailProvider(saved);
    }

    @Test
    void aRelayThatWasDownAtBootIsUsedOnceItAnswers() {
        MailProvider provider = mock(MailProvider.class);
        bootWith(() -> {
            if (attempts.incrementAndGet() == 1) {
                throw refused();
            }
            return provider;
        });
        assertNull(WebloggerStartup.currentMailProvider(), "the boot attempt failed");

        now.set(BOOT + WebloggerStartup.MAIL_RETRY_INTERVAL_MILLIS - 1);
        assertNull(WebloggerStartup.getMailProvider(), "too soon to try again");
        assertEquals(1, attempts.get());

        now.set(BOOT + WebloggerStartup.MAIL_RETRY_INTERVAL_MILLIS);
        assertSame(provider, WebloggerStartup.getMailProvider(), "the retry connected");
        assertEquals(2, attempts.get());

        now.set(BOOT + 10 * WebloggerStartup.MAIL_RETRY_INTERVAL_MILLIS);
        assertSame(provider, WebloggerStartup.getMailProvider());
        assertEquals(2, attempts.get(), "a provider that connected is kept, not rebuilt");
    }

    @Test
    void aRelayThatStaysDownIsTriedAtMostOncePerInterval() {
        bootWith(() -> {
            attempts.incrementAndGet();
            throw refused();
        });
        long interval = WebloggerStartup.MAIL_RETRY_INTERVAL_MILLIS;

        now.set(BOOT + interval);
        assertNull(WebloggerStartup.getMailProvider());
        now.set(BOOT + interval + 1);
        assertNull(WebloggerStartup.getMailProvider());
        now.set(BOOT + 2 * interval - 1);
        assertNull(WebloggerStartup.getMailProvider());
        assertEquals(2, attempts.get(), "boot, then one retry in the first interval");

        now.set(BOOT + 2 * interval);
        assertNull(WebloggerStartup.getMailProvider());
        assertEquals(3, attempts.get());
    }

    @Test
    void noRelayConfiguredIsNeverRetried() {
        config.clear();
        config.put("mail.configurationType", "jndi");
        config.put("mail.jndi.name", "mail/Session");
        bootWith(() -> {
            attempts.incrementAndGet();
            throw new StartupException("ERROR looking up mail-session with JNDI name: java:comp/env/mail/Session");
        });

        now.set(BOOT + 1000 * WebloggerStartup.MAIL_RETRY_INTERVAL_MILLIS);
        assertNull(WebloggerStartup.getMailProvider());
        assertEquals(1, attempts.get(), "no later attempt can find a session that is not configured");
    }

    @Test
    void theBootFailureIsOneWarningLineNamingTheRelay() {
        List<String> warnings;
        try (SsoLogCapture capture = SsoLogCapture.everyLogger()) {
            bootWith(() -> {
                throw refused();
            });
            warnings = capture.lines(Level.WARN);
        }

        assertEquals(1, warnings.size(), "one warning: " + warnings);
        String warning = warnings.get(0);
        assertTrue(warning.contains("smtp.example.test:587"), warning);
        assertTrue(warning.contains("Connection refused"), "the cause is named: " + warning);
        assertFalse(warning.contains("\n"), "one line: " + warning);
    }

    private void bootWith(WebloggerStartup.MailProviderFactory factory) {
        WebloggerStartup.useMailProviderFactory(factory, now::get);
        try (MockedStatic<WebloggerConfig> mocked = mockStatic(WebloggerConfig.class)) {
            mocked.when(() -> WebloggerConfig.getProperty(anyString()))
                    .thenAnswer(invocation -> config.get(invocation.<String>getArgument(0)));
            WebloggerStartup.setUpMail();
        }
    }

    private static StartupException refused() {
        return new StartupException("ERROR connecting to mail server", new ConnectException("Connection refused"));
    }
}
