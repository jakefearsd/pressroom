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

import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import jakarta.mail.Address;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.MimeMessage;

import com.wikantik.pressroom.business.MailProvider;
import org.mockito.Mockito;

/**
 * A mail provider that records what would have been sent instead of sending it.
 *
 * <p>Lives in this package because {@link WebloggerStartup#installMailProvider}
 * is package-private, in the same way {@code MockWeblogger} sits beside
 * the static locator. Tests elsewhere reach it through the public
 * {@link #install()}.
 *
 * <p>The session is a real JavaMail one -- {@code MimeMessage} needs it and
 * composing against a mock would prove nothing about headers -- while the
 * transport is a mock, so nothing opens a socket.
 */
public final class MockMailProvider {

    /** Every message handed to the transport, in order. */
    private final List<MimeMessage> sent = new ArrayList<>();

    /** The recipients each message was actually addressed to on the wire. */
    private final List<Address[]> recipients = new ArrayList<>();

    private static MailProvider previousProvider;

    private MockMailProvider() {
    }

    public static MockMailProvider install() {
        MockMailProvider recorder = new MockMailProvider();

        Session session = Session.getInstance(new Properties());
        Transport transport = Mockito.mock(Transport.class);
        try {
            Mockito.doAnswer(invocation -> {
                recorder.sent.add((MimeMessage) invocation.getArgument(0));
                recorder.recipients.add(invocation.getArgument(1));
                return null;
            }).when(transport).sendMessage(Mockito.any(), Mockito.any());
        } catch (Exception e) {
            throw new IllegalStateException("could not stub the transport", e);
        }

        MailProvider provider = Mockito.mock(MailProvider.class);
        Mockito.when(provider.getSession()).thenReturn(session);
        try {
            Mockito.when(provider.getTransport()).thenReturn(transport);
        } catch (Exception e) {
            throw new IllegalStateException("could not stub the provider", e);
        }

        previousProvider = WebloggerStartup.currentMailProvider();
        WebloggerStartup.installMailProvider(provider);
        return recorder;
    }

    /**
     * A provider whose transport always throws on send -- for proving a
     * caller's failure handling when mail is configured but delivery itself
     * fails (as opposed to {@link #install()}'s always-succeeds transport).
     * Restore with {@link #uninstall()}, same as {@link #install()}.
     */
    public static MockMailProvider installFailing() {
        return installFailing(new MessagingException("simulated delivery failure"));
    }

    /** As {@link #installFailing()}, but the transport throws {@code failure}. */
    public static MockMailProvider installFailing(Exception failure) {
        MockMailProvider recorder = new MockMailProvider();

        Session session = Session.getInstance(new Properties());
        Transport transport = Mockito.mock(Transport.class);
        try {
            Mockito.doThrow(failure)
                    .when(transport).sendMessage(Mockito.any(), Mockito.any());
        } catch (Exception e) {
            throw new IllegalStateException("could not stub the transport", e);
        }

        MailProvider provider = Mockito.mock(MailProvider.class);
        Mockito.when(provider.getSession()).thenReturn(session);
        try {
            Mockito.when(provider.getTransport()).thenReturn(transport);
        } catch (Exception e) {
            throw new IllegalStateException("could not stub the provider", e);
        }

        previousProvider = WebloggerStartup.currentMailProvider();
        WebloggerStartup.installMailProvider(provider);
        return recorder;
    }

    /** Restores whatever provider was installed before, which is usually none. */
    public static void uninstall() {
        WebloggerStartup.installMailProvider(previousProvider);
        previousProvider = null;
    }

    public List<MimeMessage> sent() {
        return List.copyOf(sent);
    }

    public MimeMessage onlyMessage() {
        if (sent.size() != 1) {
            throw new AssertionError("expected exactly one message, got " + sent.size());
        }
        return sent.get(0);
    }

    public List<Address[]> recipients() {
        return List.copyOf(recipients);
    }
}
