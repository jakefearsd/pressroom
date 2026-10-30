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

package com.wikantik.pressroom.business;

import java.util.Properties;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import javax.naming.Context;
import javax.naming.InitialContext;
import javax.naming.NamingException;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.wikantik.pressroom.business.startup.StartupException;
import com.wikantik.pressroom.config.WebloggerConfig;


/**
 * Encapsulates Pressroom mail configuration, returns mail sessions.
 */
public class MailProvider {

    private static final Logger log = LoggerFactory.getLogger(MailProvider.class);

    private enum ConfigurationType {JNDI_NAME, MAIL_PROPERTIES }

    private static final String SECURITY_STARTTLS = "starttls";
    private static final String SECURITY_SSL = "ssl";
    private static final String SECURITY_NONE = "none";

    /**
     * Connect/read/write timeout (milliseconds) for a properties-mode SMTP
     * socket. {@link MailProvider} connects at construction time so a
     * misconfigured relay fails startup rather than the first
     * password-reset mail -- but only if the socket itself is bounded: with
     * no timeout set, a relay that accepts the TCP connection and then never
     * answers hangs startup forever. Package-private and non-final so the
     * test suite can shrink it rather than wait out the real default.
     */
    @SuppressWarnings("PMD.MutableStaticState") // test seam: MailProviderTest shrinks it, see above
    static int connectTimeoutMillis = 30_000;

    private Session session;
    
    private ConfigurationType type = ConfigurationType.JNDI_NAME;
    
    private String mailHostname;
    private int    mailPort = -1;
    private String mailUsername;
    private String mailPassword;

    
    @SuppressFBWarnings(
            value = "LDAP_INJECTION",
            justification = "The JNDI name is mail.jndi.name, operator configuration (pressroom.properties or "
                    + "PRESSROOM_* environment); no request reaches it.")
    public MailProvider() throws StartupException {
        
        String connectionTypeString = WebloggerConfig.getProperty("mail.configurationType"); 
        if ("properties".equals(connectionTypeString)) {
            type = ConfigurationType.MAIL_PROPERTIES;
        }
        String jndiName =     WebloggerConfig.getProperty("mail.jndi.name");
        mailHostname = WebloggerConfig.getProperty("mail.hostname");
        mailUsername = WebloggerConfig.getProperty("mail.username");
        mailPassword = WebloggerConfig.getProperty("mail.password");
        try {
            String portString = WebloggerConfig.getProperty("mail.port");
            if (portString != null) {
                mailPort = Integer.parseInt(portString);
            }
        } catch (Exception e) {
            log.warn("mail server port not a valid integer, ignoring");
        }
        
        // init and connect now so we fail early
        if (type == ConfigurationType.JNDI_NAME) {            
            if (jndiName != null && !jndiName.startsWith("java:")) {
                jndiName = "java:comp/env/" + jndiName;
            }
            try {
                Context ctx = new InitialContext();
                session = (Session) ctx.lookup(jndiName);
            } catch (NamingException ex) {
                throw new StartupException(
                        "ERROR looking up mail-session with JNDI name: " + jndiName, ex);
            }
        } else {
            Properties props = new Properties();
            props.setProperty("mail.smtp.host", mailHostname);
            // A relay that accepts the TCP connection and then never
            // answers must not hang startup forever. mail.smtp.* covers the
            // plain and implicit-TLS (ssl.enable) cases alike -- both use
            // the "smtp" transport/protocol name, so no separate
            // mail.smtps.* spelling is needed.
            String timeoutMillis = Integer.toString(connectTimeoutMillis);
            props.setProperty("mail.smtp.connectiontimeout", timeoutMillis);
            props.setProperty("mail.smtp.timeout", timeoutMillis);
            props.setProperty("mail.smtp.writetimeout", timeoutMillis);
            boolean hasCredentials = mailUsername != null && mailPassword != null;
            if (hasCredentials) {
                props.setProperty("mail.smtp.auth", "true");
            }
            if (mailPort != -1) {
                props.setProperty("mail.smtp.port", String.valueOf(mailPort));
            }
            applySecurityProperties(props, hasCredentials);
            session = Session.getInstance(props, null);
        }
        
        try (Transport _ = getTransport()) {
            log.debug("Successfully connected to mail server");
        } catch (Exception e) {
            throw new StartupException("ERROR connecting to mail server", e);
        }
        
    }
    
    
    /**
     * Set STARTTLS/SSL properties on a properties-mode session according to
     * the {@code mail.security} startup property: {@code starttls} (the
     * default when unset), {@code ssl} or {@code none}. Any other value
     * fails startup loudly rather than silently sending credentials in the
     * clear.
     */
    private static void applySecurityProperties(Properties props, boolean hasCredentials)
            throws StartupException {
        String security = WebloggerConfig.getProperty("mail.security");
        if (security == null) {
            security = SECURITY_STARTTLS;
        }
        switch (security) {
            case SECURITY_STARTTLS:
                props.setProperty("mail.smtp.starttls.enable", "true");
                props.setProperty("mail.smtp.ssl.checkserveridentity", "true");
                // Credentials must never go over the wire in the clear: if a
                // username/password is configured, STARTTLS is mandatory,
                // not merely offered.
                if (hasCredentials) {
                    props.setProperty("mail.smtp.starttls.required", "true");
                }
                break;
            case SECURITY_SSL:
                props.setProperty("mail.smtp.ssl.enable", "true");
                props.setProperty("mail.smtp.ssl.checkserveridentity", "true");
                break;
            case SECURITY_NONE:
                break;
            default:
                throw new StartupException(
                        "ERROR unsupported mail.security property value: " + security);
        }
    }


    /**
     * Get a mail Session.
     */
    public Session getSession() {
        return session;
    }
    
    
    /**
     * Create and connect to transport, caller is responsible for closing transport.
     */
    public Transport getTransport() throws MessagingException {
        
        Transport transport;
        
        if (type == ConfigurationType.MAIL_PROPERTIES) {
            // Configure transport ourselves using mail properties
            transport = session.getTransport("smtp"); 
            if (mailUsername != null && mailPassword != null && mailPort != -1) {
                transport.connect(mailHostname, mailPort, mailUsername, mailPassword); 
            } else if (mailUsername != null && mailPassword != null) {
                transport.connect(mailHostname, mailUsername, mailPassword); 
            } else {
                transport.connect();
            }
        } else {
            // Assume container set things up properly
            transport = session.getTransport(); 
            transport.connect();
        }
        
        return transport;
    }
    
}
