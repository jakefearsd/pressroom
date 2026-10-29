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
package com.wikantik.pressroom.business;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.lang.reflect.Field;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Hashtable;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import javax.naming.Context;
import javax.naming.NameNotFoundException;
import javax.naming.spi.InitialContextFactory;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManagerFactory;

import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.Transport;

import com.wikantik.pressroom.business.startup.StartupException;
import com.wikantik.pressroom.config.WebloggerConfig;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link MailProvider}: where its mail session comes from (a JNDI lookup, or
 * built from {@code mail.*} startup properties), which credentials it
 * presents to the SMTP server, which transport security it negotiates, and
 * that it fails startup loudly -- a {@link StartupException} -- when it
 * cannot get a session or cannot connect.
 *
 * <p>Characterisation tests: written against the existing behaviour and
 * expected to pass immediately.
 *
 * <p>The SMTP server is a minimal in-process fake on a loopback port that
 * records every command it receives; nothing leaves the machine. Three
 * instances run for the whole class, distinguished by how much TLS they
 * support: {@link #smtp} offers STARTTLS and completes the handshake with a
 * throwaway self-signed certificate (the default {@code mail.security} for
 * every test that configures credentials); {@link #plainSmtp} never
 * advertises STARTTLS, standing in for a relay with no TLS support at all
 * (used by the {@code mail.security=none} opt-out test and by the test
 * proving a STARTTLS-requiring connection refuses to fall back to the
 * clear); {@link #implicitTlsSmtp} wraps every connection in TLS from the
 * first byte, standing in for a port-465-style relay ({@code
 * mail.security=ssl}).
 *
 * <p><b>The self-signed certificate.</b> Generated once per class via the
 * JDK's own {@code keytool} (no new test dependency), for {@code CN=} and
 * {@code SAN=ip:127.0.0.1} so hostname verification
 * ({@code mail.smtp.ssl.checkserveridentity=true}, which every {@code
 * starttls}/{@code ssl} session now sets) succeeds against the loopback
 * address the tests connect to. The JVM-wide default {@link SSLContext} is
 * replaced for the duration of the class with one that trusts exactly that
 * certificate ({@link SSLContext#setDefault}), which is what lets {@link
 * MailProvider}'s own, unmodified {@code Session.getInstance(props, null)}
 * validate the fake server without any test-only trust property leaking
 * into production code; the previous default is restored afterwards.
 *
 * <p><b>{@code @Timeout(10)} on the class.</b> Every test here talks to a
 * fake server over a real socket; a bug in the connect/handshake path (e.g.
 * {@link MailProvider} setting no SMTP socket timeouts, so a relay that
 * accepts but never answers hangs startup forever) must fail the test
 * instead of hanging the build.
 *
 * <p><b>{@code Session.getInstance}, not {@code getDefaultInstance}.</b>
 * {@code properties} mode used to build its session with {@code
 * Session.getDefaultInstance}, which returns the FIRST default session the
 * JVM ever created and silently ignores the properties of every later call
 * -- so a later call's STARTTLS/SSL settings would have been dropped without
 * warning. {@link MailProvider} now uses {@code Session.getInstance}, which
 * always honours the properties it is given, so no default-session priming
 * is needed here any more.
 */
@Timeout(value = 10, unit = TimeUnit.SECONDS)
class MailProviderTest {

    private static final String[] MAIL_KEYS = {
        "mail.configurationType", "mail.jndi.name", "mail.hostname",
        "mail.port", "mail.username", "mail.password", "mail.security"};

    private static final char[] KEYSTORE_PASSWORD = "changeit".toCharArray();

    private static FakeSmtpServer smtp;
    private static FakeSmtpServer plainSmtp;
    private static FakeSmtpServer implicitTlsSmtp;
    private static FakeSmtpServer silentSmtp;
    private static SSLContext previousDefaultSslContext;
    private static Path certWorkDir;

    private final Map<String, String> savedConfig = new HashMap<>();

    @BeforeAll
    static void startFakeSmtpServers() throws Exception {
        KeyStore keyStore = generateSelfSignedKeyStore();
        SSLContext serverTls = sslContext(keyStore, true);
        SSLContext clientTls = sslContext(keyStore, false);

        previousDefaultSslContext = SSLContext.getDefault();
        SSLContext.setDefault(clientTls);

        smtp = FakeSmtpServer.start(FakeSmtpServer.Mode.OFFER_STARTTLS, serverTls);
        plainSmtp = FakeSmtpServer.start(FakeSmtpServer.Mode.PLAIN, null);
        implicitTlsSmtp = FakeSmtpServer.start(FakeSmtpServer.Mode.IMPLICIT_TLS, serverTls);
        silentSmtp = FakeSmtpServer.start(FakeSmtpServer.Mode.SILENT, null);
    }

    @AfterAll
    static void stopFakeSmtpServers() throws IOException {
        smtp.close();
        plainSmtp.close();
        implicitTlsSmtp.close();
        silentSmtp.close();
        SSLContext.setDefault(previousDefaultSslContext);
        deleteRecursively(certWorkDir);
    }

    @BeforeEach
    void saveMailConfig() throws Exception {
        for (String key : MAIL_KEYS) {
            savedConfig.put(key, config().getProperty(key));
        }
        smtp.commands.clear();
        plainSmtp.commands.clear();
        implicitTlsSmtp.commands.clear();
        silentSmtp.commands.clear();
    }

    @AfterEach
    void restoreMailConfig() throws Exception {
        for (String key : MAIL_KEYS) {
            setConfig(key, savedConfig.get(key));
        }
    }

    // ------------------------------------------------------ properties mode

    @Test
    void propertiesModeWithCredentialsAndAPortAuthenticatesWithThem() throws Exception {
        propertiesMode("127.0.0.1", Integer.toString(smtp.port()), "mailer", "s3cret");

        new MailProvider();

        assertEquals("mailer:s3cret", smtp.presentedPlainCredentials(),
                "the configured username and password must be presented to the server");
    }

    /**
     * No {@code mail.port}: {@link MailProvider#getTransport()} takes the
     * 3-argument {@code connect(host, user, password)} overload, letting the
     * session pick its own (protocol-default, port 25) port rather than an
     * explicit one -- unlike every other properties-mode test here, this can
     * no longer be observed by actually authenticating against the fake
     * server's arbitrary loopback port. A session built with {@code
     * Session.getInstance} (see the class javadoc on why this replaced
     * {@code getDefaultInstance}) truly carries no {@code mail.smtp.port} in
     * that case and genuinely targets 25, so the assertion moves to the
     * unroutable TEST-NET-3 address (RFC 5737): construction still fails
     * (nothing is listening), but deterministically and quickly (via our own
     * connect timeout, not depending on what happens to run on port 25 of
     * this machine), and the resulting exception names the port it tried.
     */
    @Test
    void propertiesModeWithCredentialsButNoPortTargetsTheDefaultSmtpPort() throws Exception {
        int previousTimeout = MailProvider.connectTimeoutMillis;
        MailProvider.connectTimeoutMillis = 500;
        try {
            propertiesModeWithSecurity("203.0.113.1", null, "mailer", "s3cret", "none");

            StartupException ex = assertThrows(StartupException.class, MailProvider::new);

            assertEquals("ERROR connecting to mail server", ex.getMessage());
            assertInstanceOf(MessagingException.class, ex.getCause());
            assertTrue(ex.getCause().getMessage().contains("25"),
                    "no mail.port configured must still target the standard SMTP port: "
                            + ex.getCause());
        } finally {
            MailProvider.connectTimeoutMillis = previousTimeout;
        }
    }

    /** A username without a password is not a credential: connect anonymously. */
    @Test
    void propertiesModeWithAUsernameButNoPasswordConnectsWithoutAuthenticating() throws Exception {
        propertiesMode("127.0.0.1", Integer.toString(smtp.port()), "mailer", null);

        new MailProvider();

        assertTrue(smtp.commands.stream().anyMatch(c -> c.startsWith("EHLO")),
                "the provider must have connected: " + smtp.commands);
        assertTrue(smtp.commands.stream().noneMatch(c -> c.startsWith("AUTH")),
                "no AUTH without a password: " + smtp.commands);
    }

    /**
     * A non-numeric {@code mail.port} must not itself crash startup: {@link
     * MailProvider} already swallows the {@code NumberFormatException} with
     * a {@code log.warn}, so construction proceeds exactly as if no port had
     * been configured at all -- see {@link
     * #propertiesModeWithCredentialsButNoPortTargetsTheDefaultSmtpPort} for
     * why the assertion is against an unroutable address rather than a live
     * authentication.
     */
    @Test
    void aNonNumericPortIsIgnoredRatherThanCrashingPortParsing() throws Exception {
        int previousTimeout = MailProvider.connectTimeoutMillis;
        MailProvider.connectTimeoutMillis = 500;
        try {
            propertiesModeWithSecurity("203.0.113.1", "twenty-five", "mailer", "s3cret", "none");

            StartupException ex = assertThrows(StartupException.class, MailProvider::new);

            assertEquals("ERROR connecting to mail server", ex.getMessage());
            assertInstanceOf(MessagingException.class, ex.getCause(),
                    "a non-numeric mail.port must be ignored, not crash startup: " + ex.getCause());
            assertTrue(ex.getCause().getMessage().contains("25"),
                    "an ignored mail.port must still target the standard SMTP port: "
                            + ex.getCause());
        } finally {
            MailProvider.connectTimeoutMillis = previousTimeout;
        }
    }

    /**
     * Connecting is done at construction so a misconfigured relay fails
     * startup, not the first password-reset mail.
     */
    @Test
    void anUnreachableMailServerFailsStartup() throws Exception {
        int closedPort;
        try (ServerSocket probe = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            closedPort = probe.getLocalPort();
        }
        propertiesMode("127.0.0.1", Integer.toString(closedPort), "mailer", "s3cret");

        StartupException ex = assertThrows(StartupException.class, MailProvider::new);

        assertEquals("ERROR connecting to mail server", ex.getMessage());
        assertInstanceOf(MessagingException.class, ex.getCause());
    }

    /** {@code getTransport()} hands back a new, connected transport each call. */
    @Test
    void getTransportReturnsAConnectedTransportForTheCaller() throws Exception {
        propertiesMode("127.0.0.1", Integer.toString(smtp.port()), "mailer", "s3cret");
        MailProvider provider = new MailProvider();
        smtp.commands.clear();

        try (Transport transport = provider.getTransport()) {
            assertTrue(transport.isConnected());
        }
        assertEquals("mailer:s3cret", smtp.presentedPlainCredentials());
    }

    // --------------------------------------------------------- mail.security

    /**
     * The default: STARTTLS is enabled, hostname verification is on, and
     * -- because credentials are configured -- STARTTLS is REQUIRED, not
     * merely offered.
     */
    @Test
    void starttlsIsTheDefaultAndIsRequiredOnceCredentialsAreConfigured() throws Exception {
        propertiesModeWithSecurity("127.0.0.1", Integer.toString(smtp.port()),
                "mailer", "s3cret", null);

        MailProvider provider = new MailProvider();

        Properties sessionProps = provider.getSession().getProperties();
        assertEquals("true", sessionProps.getProperty("mail.smtp.starttls.enable"));
        assertEquals("true", sessionProps.getProperty("mail.smtp.starttls.required"));
        assertEquals("true", sessionProps.getProperty("mail.smtp.ssl.checkserveridentity"));
        assertNull(sessionProps.getProperty("mail.smtp.ssl.enable"));
        assertEquals("mailer:s3cret", smtp.presentedPlainCredentials(),
                "credentials must still reach the server, now over the TLS channel");
    }

    /** Without credentials, STARTTLS stays opportunistic, not required. */
    @Test
    void starttlsIsOpportunisticWithoutCredentials() throws Exception {
        propertiesModeWithSecurity("127.0.0.1", Integer.toString(smtp.port()), null, null, null);

        MailProvider provider = new MailProvider();

        Properties sessionProps = provider.getSession().getProperties();
        assertEquals("true", sessionProps.getProperty("mail.smtp.starttls.enable"));
        assertNull(sessionProps.getProperty("mail.smtp.starttls.required"));
        assertEquals("true", sessionProps.getProperty("mail.smtp.ssl.checkserveridentity"));
    }

    /**
     * The behavioural heart of the fix: a relay that does not offer STARTTLS
     * must never receive credentials in the clear. The old code would have
     * sent AUTH PLAIN straight over the unencrypted connection; the new
     * default requires STARTTLS whenever credentials are configured, so
     * construction must fail instead.
     */
    @Test
    void aServerThatDoesNotOfferStarttlsIsRefusedRatherThanSentCredentialsInTheClear()
            throws Exception {
        propertiesModeWithSecurity("127.0.0.1", Integer.toString(plainSmtp.port()),
                "mailer", "s3cret", null);

        StartupException ex = assertThrows(StartupException.class, MailProvider::new);

        assertEquals("ERROR connecting to mail server", ex.getMessage());
        assertTrue(plainSmtp.commands.stream().noneMatch(c -> c.startsWith("AUTH")),
                "credentials must never be sent when STARTTLS could not be negotiated: "
                        + plainSmtp.commands);
    }

    /** {@code mail.security=ssl}: implicit TLS from the first byte. */
    @Test
    void sslModeConnectsOverImplicitTls() throws Exception {
        propertiesModeWithSecurity("127.0.0.1", Integer.toString(implicitTlsSmtp.port()),
                "mailer", "s3cret", "ssl");

        MailProvider provider = new MailProvider();

        Properties sessionProps = provider.getSession().getProperties();
        assertEquals("true", sessionProps.getProperty("mail.smtp.ssl.enable"));
        assertEquals("true", sessionProps.getProperty("mail.smtp.ssl.checkserveridentity"));
        assertNull(sessionProps.getProperty("mail.smtp.starttls.enable"));
        assertEquals("mailer:s3cret", implicitTlsSmtp.presentedPlainCredentials());
    }

    /**
     * {@code mail.security=none} is a deliberate opt-out: neither STARTTLS
     * nor SSL is requested, so a relay with no TLS at all keeps working, and
     * credentials are presented exactly as before this change.
     */
    @Test
    void noneModeIsAnExplicitOptOutAndStillAuthenticatesInTheClear() throws Exception {
        propertiesModeWithSecurity("127.0.0.1", Integer.toString(plainSmtp.port()),
                "mailer", "s3cret", "none");

        MailProvider provider = new MailProvider();

        Properties sessionProps = provider.getSession().getProperties();
        assertNull(sessionProps.getProperty("mail.smtp.starttls.enable"));
        assertNull(sessionProps.getProperty("mail.smtp.ssl.enable"));
        assertNull(sessionProps.getProperty("mail.smtp.ssl.checkserveridentity"));
        assertEquals("mailer:s3cret", plainSmtp.presentedPlainCredentials());
    }

    /** An unsupported value fails startup loudly, naming the property and the value. */
    @Test
    void anUnsupportedSecurityValueFailsStartupNamingItself() throws Exception {
        propertiesModeWithSecurity("127.0.0.1", Integer.toString(smtp.port()),
                null, null, "carrier-pigeon");

        StartupException ex = assertThrows(StartupException.class, MailProvider::new);

        assertTrue(ex.getMessage().contains("mail.security"), ex.getMessage());
        assertTrue(ex.getMessage().contains("carrier-pigeon"), ex.getMessage());
        assertFalse(smtp.commands.stream().anyMatch(c -> c.startsWith("EHLO")),
                "an invalid value must fail before ever connecting: " + smtp.commands);
    }

    /**
     * {@link MailProvider} connects at startup so a misconfigured relay
     * fails boot, not the first password-reset mail -- but that only holds
     * if the connect/read/write have a bound. A relay that accepts the TCP
     * connection and then never answers must fail startup within the
     * configured timeout, not hang forever.
     */
    @Test
    void anUnresponsiveServerFailsStartupWithinTheTimeoutRatherThanHanging() throws Exception {
        int previousTimeout = MailProvider.connectTimeoutMillis;
        MailProvider.connectTimeoutMillis = 500;
        try {
            propertiesModeWithSecurity("127.0.0.1", Integer.toString(silentSmtp.port()),
                    null, null, "none");

            StartupException ex = assertThrows(StartupException.class, MailProvider::new);

            assertEquals("ERROR connecting to mail server", ex.getMessage());
        } finally {
            MailProvider.connectTimeoutMillis = previousTimeout;
        }
    }

    // ------------------------------------------------------------ JNDI mode

    /**
     * The shipped default: the session is whatever the container binds at
     * {@code java:comp/env/mail/Session} (a relative name gets that prefix),
     * used as-is, and connected with its own settings.
     */
    @Test
    void jndiModeUsesTheContainersSessionUnderTheCompEnvPrefix() throws Exception {
        Session containerSession = Session.getInstance(sessionPointingAtFakeServer());
        jndiMode("mail/Session");

        MailProvider provider = withJndi(Map.of("java:comp/env/mail/Session", containerSession),
                MailProvider::new);

        assertSame(containerSession, provider.getSession());
        assertTrue(smtp.commands.stream().anyMatch(c -> c.startsWith("EHLO")),
                "the provider must connect at startup: " + smtp.commands);
    }

    /** A name already in the {@code java:} namespace is looked up verbatim. */
    @Test
    void jndiModeLooksUpAJavaNamespaceNameVerbatim() throws Exception {
        Session containerSession = Session.getInstance(sessionPointingAtFakeServer());
        jndiMode("java:/mail/Relay");

        MailProvider provider = withJndi(Map.of("java:/mail/Relay", containerSession),
                MailProvider::new);

        assertSame(containerSession, provider.getSession());
    }

    @Test
    void jndiModeWithNothingBoundFailsStartupNamingTheFullName() throws Exception {
        jndiMode("mail/Missing");

        StartupException ex = assertThrows(StartupException.class,
                () -> withJndi(Map.of(), MailProvider::new));

        assertEquals("ERROR looking up mail-session with JNDI name: java:comp/env/mail/Missing",
                ex.getMessage());
        assertInstanceOf(NameNotFoundException.class, ex.getCause());
    }

    // ------------------------------------------------------------- fixtures

    private void propertiesMode(String host, String port, String user, String password)
            throws Exception {
        propertiesModeWithSecurity(host, port, user, password, null);
    }

    private void propertiesModeWithSecurity(String host, String port, String user,
            String password, String security) throws Exception {
        setConfig("mail.configurationType", "properties");
        setConfig("mail.hostname", host);
        setConfig("mail.port", port);
        setConfig("mail.username", user);
        setConfig("mail.password", password);
        setConfig("mail.security", security);
    }

    private void jndiMode(String name) throws Exception {
        setConfig("mail.configurationType", "jndi");
        setConfig("mail.jndi.name", name);
        setConfig("mail.port", null);
        setConfig("mail.username", null);
        setConfig("mail.password", null);
    }

    private static Properties sessionPointingAtFakeServer() {
        Properties props = new Properties();
        props.setProperty("mail.transport.protocol", "smtp");
        props.setProperty("mail.smtp.host", "127.0.0.1");
        props.setProperty("mail.smtp.port", Integer.toString(smtp.port()));
        return props;
    }

    /**
     * Runs {@code body} with {@code java.naming.factory.initial} naming
     * {@link BindingsContextFactory} over {@code bindings}, restoring the
     * system property afterwards.
     */
    private static <T> T withJndi(Map<String, Object> bindings, ThrowingSupplier<T> body)
            throws Exception {
        String previous = System.getProperty(Context.INITIAL_CONTEXT_FACTORY);
        BindingsContextFactory.bindings = bindings;
        System.setProperty(Context.INITIAL_CONTEXT_FACTORY, BindingsContextFactory.class.getName());
        try {
            return body.get();
        } finally {
            if (previous == null) {
                System.clearProperty(Context.INITIAL_CONTEXT_FACTORY);
            } else {
                System.setProperty(Context.INITIAL_CONTEXT_FACTORY, previous);
            }
            BindingsContextFactory.bindings = Map.of();
        }
    }

    @FunctionalInterface
    private interface ThrowingSupplier<T> {
        T get() throws Exception;
    }

    /** A JNDI provider that answers lookups from a fixed map. */
    public static class BindingsContextFactory implements InitialContextFactory {
        static volatile Map<String, Object> bindings = Map.of();

        @Override
        public Context getInitialContext(Hashtable<?, ?> environment) {
            Context context = mock(Context.class);
            try {
                when(context.lookup(org.mockito.ArgumentMatchers.anyString())).thenAnswer(inv -> {
                    String name = inv.getArgument(0);
                    Object bound = bindings.get(name);
                    if (bound == null) {
                        throw new NameNotFoundException(name);
                    }
                    return bound;
                });
            } catch (javax.naming.NamingException e) {
                throw new IllegalStateException(e);
            }
            return context;
        }
    }

    private static Properties config() throws Exception {
        Field field = WebloggerConfig.class.getDeclaredField("config");
        field.setAccessible(true);
        return (Properties) field.get(null);
    }

    private static void setConfig(String key, String value) throws Exception {
        if (value == null) {
            config().remove(key);
        } else {
            config().setProperty(key, value);
        }
    }

    // ------------------------------------------------------- TLS test setup

    /**
     * Generates a throwaway self-signed keystore via the JDK's own {@code
     * keytool} (no new test dependency), valid for {@code CN=127.0.0.1} /
     * {@code SAN=ip:127.0.0.1} so the fake server's certificate satisfies
     * hostname verification against the loopback address the tests connect
     * to.
     */
    private static KeyStore generateSelfSignedKeyStore() throws Exception {
        certWorkDir = Files.createTempDirectory("mailprovidertest-tls");
        Path keystoreFile = certWorkDir.resolve("fake-smtp.p12");

        ProcessBuilder pb = new ProcessBuilder(
                "keytool", "-genkeypair",
                "-alias", "fake-smtp",
                "-keyalg", "RSA", "-keysize", "2048",
                "-validity", "2",
                "-keystore", keystoreFile.toString(),
                "-storetype", "PKCS12",
                "-storepass", new String(KEYSTORE_PASSWORD),
                "-keypass", new String(KEYSTORE_PASSWORD),
                "-dname", "CN=127.0.0.1",
                "-ext", "SAN=ip:127.0.0.1");
        pb.redirectErrorStream(true);
        Process process = pb.start();
        String output;
        try (InputStream processOutput = process.getInputStream()) {
            output = new String(processOutput.readAllBytes(), StandardCharsets.UTF_8);
        }
        if (!process.waitFor(30, TimeUnit.SECONDS) || process.exitValue() != 0) {
            throw new IllegalStateException("keytool failed to generate a test certificate: " + output);
        }

        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(keystoreFile)) {
            keyStore.load(in, KEYSTORE_PASSWORD);
        }
        return keyStore;
    }

    /**
     * A server-mode context (key material, to present the certificate) or a
     * client-mode context (trust material, to accept exactly that
     * certificate) built from the same self-signed keystore -- it is its own
     * trust anchor.
     */
    private static SSLContext sslContext(KeyStore keyStore, boolean forServer) throws Exception {
        SSLContext context = SSLContext.getInstance("TLS");
        if (forServer) {
            KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(keyStore, KEYSTORE_PASSWORD);
            context.init(kmf.getKeyManagers(), null, null);
        } else {
            TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(keyStore);
            context.init(null, tmf.getTrustManagers(), null);
        }
        return context;
    }

    private static void deleteRecursively(Path dir) throws IOException {
        if (dir == null || !Files.exists(dir)) {
            return;
        }
        try (var paths = Files.walk(dir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.delete(p);
                } catch (IOException ignored) {
                    // best-effort cleanup of a test-only temp directory
                }
            });
        }
    }

    /**
     * Just enough ESMTP to satisfy a JavaMail connect: greets, advertises
     * {@code AUTH PLAIN}, accepts any credentials and records every command.
     * {@link Mode} controls how much TLS it supports.
     */
    private static final class FakeSmtpServer implements AutoCloseable {

        /** How much TLS a given instance offers. */
        enum Mode {
            /** No TLS at all; STARTTLS is never advertised. */
            PLAIN,
            /** Advertises STARTTLS and completes the handshake on request. */
            OFFER_STARTTLS,
            /** Wraps every connection in TLS before the greeting, like a port-465 relay. */
            IMPLICIT_TLS,
            /** Accepts the TCP connection and then never says anything, ever. */
            SILENT
        }

        final List<String> commands = new CopyOnWriteArrayList<>();
        private final ServerSocket server;
        private final Thread acceptor;
        private final Mode mode;
        private final SSLContext sslContext;

        private FakeSmtpServer(ServerSocket server, Mode mode, SSLContext sslContext) {
            this.server = server;
            this.mode = mode;
            this.sslContext = sslContext;
            this.acceptor = new Thread(this::acceptLoop, "fake-smtp");
            this.acceptor.setDaemon(true);
        }

        static FakeSmtpServer start(Mode mode, SSLContext sslContext) throws IOException {
            FakeSmtpServer fake = new FakeSmtpServer(
                    new ServerSocket(0, 50, InetAddress.getLoopbackAddress()), mode, sslContext);
            fake.acceptor.start();
            return fake;
        }

        int port() {
            return server.getLocalPort();
        }

        /**
         * {@code user:password} from the last SASL PLAIN response
         * ({@code authzid NUL authcid NUL passwd}), or null if none was sent.
         */
        String presentedPlainCredentials() {
            String encoded = null;
            for (int i = 0; i < commands.size(); i++) {
                String command = commands.get(i);
                if (command.startsWith("AUTH PLAIN")) {
                    String[] parts = command.split(" ");
                    encoded = parts.length > 2 ? parts[2]
                            : (i + 1 < commands.size() ? commands.get(i + 1) : null);
                }
            }
            if (encoded == null) {
                return null;
            }
            String[] fields = new String(Base64.getDecoder().decode(encoded),
                    StandardCharsets.UTF_8).split("\0", -1);
            return fields[1] + ":" + fields[2];
        }

        private void acceptLoop() {
            while (!server.isClosed()) {
                try {
                    Socket socket = server.accept();
                    Thread handler = new Thread(() -> converse(socket), "fake-smtp-session");
                    handler.setDaemon(true);
                    handler.start();
                } catch (IOException closed) {
                    return;
                }
            }
        }

        private void converse(Socket rawSocket) {
            Socket socket = rawSocket;
            try {
                if (mode == Mode.IMPLICIT_TLS) {
                    socket = tlsWrap(socket);
                }
                BufferedReader in = newReader(socket);
                PrintWriter out = newWriter(socket);
                if (mode != Mode.SILENT) {
                    reply(out, "220 fake ESMTP");
                }
                // SILENT sends no greeting and falls straight into the read
                // loop below, which then blocks forever: the client waits
                // for a greeting it will never get. The client's own socket
                // timeout is what must end this, not the server.
                boolean awaitingPlainResponse = false;
                String line;
                while ((line = in.readLine()) != null) {
                    commands.add(line);
                    if (awaitingPlainResponse) {
                        awaitingPlainResponse = false;
                        reply(out, "235 accepted");
                    } else if (line.startsWith("EHLO")) {
                        out.print("250-fake\r\n");
                        if (mode == Mode.OFFER_STARTTLS && !(socket instanceof SSLSocket)) {
                            out.print("250-STARTTLS\r\n");
                        }
                        reply(out, "250 AUTH PLAIN");
                    } else if (line.startsWith("HELO")) {
                        reply(out, "250 fake");
                    } else if (line.startsWith("STARTTLS") && mode == Mode.OFFER_STARTTLS
                            && !(socket instanceof SSLSocket)) {
                        reply(out, "220 Go ahead");
                        socket = tlsWrap(socket);
                        in = newReader(socket);
                        out = newWriter(socket);
                    } else if (line.startsWith("AUTH PLAIN")) {
                        if (line.split(" ").length > 2) {
                            reply(out, "235 accepted");
                        } else {
                            awaitingPlainResponse = true;
                            reply(out, "334 ");
                        }
                    } else if (line.startsWith("QUIT")) {
                        reply(out, "221 bye");
                        break;
                    } else {
                        reply(out, "250 ok");
                    }
                }
            } catch (IOException ignored) {
                // the client hung up, or a TLS handshake failed; nothing more to record
            } finally {
                try {
                    socket.close();
                } catch (IOException ignored) {
                    // already closed
                }
            }
        }

        private Socket tlsWrap(Socket plain) throws IOException {
            SSLSocket tls = (SSLSocket) sslContext.getSocketFactory().createSocket(
                    plain, plain.getInetAddress().getHostAddress(), plain.getPort(), true);
            tls.setUseClientMode(false);
            tls.startHandshake();
            return tls;
        }

        private static BufferedReader newReader(Socket socket) throws IOException {
            return new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
        }

        private static PrintWriter newWriter(Socket socket) throws IOException {
            return new PrintWriter(
                    new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.US_ASCII));
        }

        private static void reply(PrintWriter out, String line) {
            out.print(line + "\r\n");
            out.flush();
        }

        @Override
        public void close() throws IOException {
            server.close();
        }
    }
}
