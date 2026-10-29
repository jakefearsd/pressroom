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

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.wikantik.pressroom.business.DatabaseProvider;
import com.wikantik.pressroom.business.MailProvider;
import com.wikantik.pressroom.config.WebloggerConfig;


/**
 * Manages Roller Weblogger startup process.
 */
public final class WebloggerStartup {
    
    private static final Logger log = LoggerFactory.getLogger(WebloggerStartup.class);
    
    // volatile: written by the boot thread or by an install-wizard request
    // (createDatabase/upgradeDatabase), read by every other request thread.
    private static volatile boolean prepared = false;

    private static DatabaseProvider dbProvider = null;
    private static StartupException dbProviderException = null;

    /**
     * How long a configured relay that did not answer is left alone before the
     * next use tries it again. Bounds a relay that stays down to one connection
     * attempt (at most the provider's connect timeout) per interval.
     */
    static final long MAIL_RETRY_INTERVAL_MILLIS = 60_000L;

    private static final long NEVER = Long.MAX_VALUE;

    private static final MailProviderFactory REAL_MAIL = MailProvider::new;

    private static volatile MailProvider mailProvider = null;

    /** When the next use may try the relay again; {@link #NEVER} when there is nothing to retry. */
    private static final AtomicLong nextMailRetry = new AtomicLong(NEVER);

    /** host:port or JNDI name, for the log lines; set at boot. */
    private static volatile String mailTarget = "";

    private static MailProviderFactory mailFactory = REAL_MAIL;
    private static LongSupplier clock = System::currentTimeMillis;


    // non-instantiable
    private WebloggerStartup() {}


    /**
     * Is the Roller Weblogger app properly prepared to be bootstrapped?
     */
    public static boolean isPrepared() {
        return prepared;
    }
    
    
    /**
     * Get a reference to the currently configured DatabaseProvider.
     *
     * @return DatabaseProvider The configured database provider.
     * @throws IllegalStateException If the app has not been properly prepared yet.
     */
    public static DatabaseProvider getDatabaseProvider() {
        if (dbProvider == null) {
            throw new IllegalStateException("Roller Weblogger has not been prepared yet");
        }
        return dbProvider;
    }
    
    
    /**
     * Get a reference to the exception thrown while instantiating the 
     * database provider, if any.
     *
     * @return StartupException Exception from db provider, or null if no exception thrown.
     */
    public static StartupException getDatabaseProviderException() {
        return dbProviderException;
    }
    
    
    /**
     * Get a reference to the currently configured MailProvider, if available.
     *
     * <p>A relay configured in properties mode that did not answer at boot is
     * tried again here, on the next use, at most once per
     * {@link #MAIL_RETRY_INTERVAL_MILLIS}; one caller makes the attempt and any
     * other caller meanwhile gets null rather than waiting on the connection.
     * Without this, a relay that was briefly down at boot left mail (and so
     * password reset) off until the next restart.
     *
     * @return MailProvider The configured mail provider, or null if none configured.
     */
    public static MailProvider getMailProvider() {
        MailProvider provider = mailProvider;
        return provider != null ? provider : retryMail();
    }

    private static MailProvider retryMail() {
        long due = nextMailRetry.get();
        long now = clock.getAsLong();
        if (now < due || !nextMailRetry.compareAndSet(due, now + MAIL_RETRY_INTERVAL_MILLIS)) {
            return null;
        }
        try {
            MailProvider provider = mailFactory.create();
            mailProvider = provider;
            nextMailRetry.set(NEVER);
            log.info("Mail is available: connected to the mail server at {}", mailTarget);
            return provider;
        } catch (StartupException ex) {
            log.debug("The mail server at {} still does not answer; next try in {} s",
                    mailTarget, MAIL_RETRY_INTERVAL_MILLIS / 1000, ex);
            return null;
        }
    }

    /**
     * Build the mail provider from the configuration, as {@link #prepare()}
     * does at boot. A failure is one WARN line naming the relay and the cause,
     * with the stack trace at DEBUG. Package-private for its test.
     */
    static void setUpMail() {
        nextMailRetry.set(NEVER);
        boolean relay = "properties".equals(WebloggerConfig.getProperty("mail.configurationType"))
                && StringUtils.isNotBlank(WebloggerConfig.getProperty("mail.hostname"));
        mailTarget = relay ? relayAddress() : "JNDI name " + WebloggerConfig.getProperty("mail.jndi.name");
        try {
            mailProvider = mailFactory.create();
        } catch (StartupException ex) {
            mailProvider = null;
            String cause = ExceptionUtils.getRootCauseMessage(ex);
            if (relay) {
                nextMailRetry.set(clock.getAsLong() + MAIL_RETRY_INTERVAL_MILLIS);
                log.warn("Mail is unavailable: the mail server at {} did not answer ({}). Password reset "
                        + "and notification mail are off until it does; the next mail sent tries again, "
                        + "at most once a minute.", mailTarget, cause);
            } else {
                log.warn("Mail is not configured ({}): password reset and notification mail are off. "
                        + "Set the mail.* properties (ROLLER_MAIL_* in a container).", cause);
            }
            log.debug("Mail provider setup failed", ex);
        }
    }

    private static String relayAddress() {
        String port = WebloggerConfig.getProperty("mail.port");
        String host = WebloggerConfig.getProperty("mail.hostname").trim();
        return StringUtils.isBlank(port) ? host : host + ":" + port.trim();
    }

    /** Builds a mail provider; {@link MailProvider}'s constructor connects. */
    @FunctionalInterface
    interface MailProviderFactory {
        MailProvider create() throws StartupException;
    }

    /**
     * Test seam: how a mail provider is built and what time it is, for
     * {@link #setUpMail()} and the retry in {@link #getMailProvider()}.
     * {@code null} restores the real one.
     */
    static void useMailProviderFactory(MailProviderFactory factory, LongSupplier testClock) {
        mailFactory = factory != null ? factory : REAL_MAIL;
        clock = testClock != null ? testClock : System::currentTimeMillis;
    }


    /**
     * Test seam: install a ready-made mail provider, skipping startup.
     *
     * <p>{@code MailUtil} is entirely static and reaches SMTP only through
     * {@link #getMailProvider()}, which is set during {@link #prepare()} from a
     * real mail configuration. Without a seam its message composition -- the
     * password-reset link, who an invitation is addressed to, what a comment
     * notification says -- cannot be exercised at all: every method returns
     * early when the provider is null.
     *
     * <p>Package-private on purpose: only test support in this package can
     * reach it, so it cannot become a production back door. Pass {@code null}
     * to reset.
     */
    static void installMailProvider(MailProvider provider) {
        mailProvider = provider;
        nextMailRetry.set(NEVER);
    }


    /** Test seam companion: the provider currently installed, or null. */
    static MailProvider currentMailProvider() {
        return mailProvider;
    }
    
    
    /**
     * Does the app need to create the database tables?
     */
    public static boolean isDatabaseCreationRequired() {
        return getDatabaseInstaller().isCreationRequired();
    }
    
    
    /**
     * Run database creation scripts.
     */
    public static List<String> createDatabase() throws StartupException {
        
        DatabaseInstaller installer = getDatabaseInstaller();
        try {
            installer.createDatabase();
            
            // any time we've successfully installed a db we are prepared
            prepared = true;
            
        } catch (StartupException se) {
            throw new StartupException(se.getMessage(), se, installer.getMessages());
        }
        
        return installer.getMessages();
    }
    
    
    /**
     * Does the app require any upgrading?
     */
    public static boolean isDatabaseUpgradeRequired() {
        return getDatabaseInstaller().isUpgradeRequired();
    }
    
    
    /**
     * Run database upgrade work, optionally including upgrade scripts.
     */
    public static List<String> upgradeDatabase(boolean runScripts) 
            throws StartupException {
        
        DatabaseInstaller installer = getDatabaseInstaller();
        try {
            installer.upgradeDatabase(true);
            
            // any time we've successfully upgraded a db we are prepared
            prepared = true;
            
        } catch (StartupException se) {
            throw new StartupException(se.getMessage(), se, installer.getMessages());
        }
        
        return installer.getMessages();
    }
    
    
    /**
     * Get a database installer.
     *
     * @return DatabaseInstaller A database installer.
     * @throws IllegalStateException If the database provider has not been properly setup yet.
     */
    private static DatabaseInstaller getDatabaseInstaller() {
        return new DatabaseInstaller(getDatabaseProvider(), new ClasspathDatabaseScriptProvider());
    }
    
    
    /**
     * Run the Roller Weblogger preparation sequence.
     *
     * This sequence is what prepares the core services of the application such
     * as setting up the database and mail providers.
     */
    public static void prepare() throws StartupException {
        
        // setup database provider
        try {
            dbProvider = new DatabaseProvider();
        } catch(StartupException ex) {
            dbProviderException = ex;
            throw ex;
        }
        
        // setup mail provider, if configured; a failure is logged and retried later
        setUpMail();
        
        // now we need to deal with database install/upgrade logic
        if ("manual".equals(WebloggerConfig.getProperty("installation.type"))) {
            
            // if we are doing manual install then all that is needed is the
            // app handled database upgrade work, not the db scripts
            DatabaseInstaller dbInstaller = getDatabaseInstaller();
            if (dbInstaller.isUpgradeRequired()) {
                dbInstaller.upgradeDatabase(false);
            }
            
            prepared = true;
            
        } else if ("auto".equals(WebloggerConfig.getProperty("installation.type"))) {
            
            // we are in auto install mode, so see if there is any work to do
            DatabaseInstaller dbInstaller = getDatabaseInstaller();
            if (!dbInstaller.isCreationRequired() &&
                    !dbInstaller.isUpgradeRequired()) {
                prepared = true;
            }
        }
        
    }
    
}
