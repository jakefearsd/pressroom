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

package com.wikantik.pressroom.ui.core;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.HashMap;
import java.util.Map;
import jakarta.servlet.ServletContext;

import org.springframework.security.core.userdetails.UserCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.wikantik.pressroom.config.WebloggerConfig;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.context.ApplicationContext;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.scrypt.SCryptPasswordEncoder;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.crypto.password.Pbkdf2PasswordEncoder;
import org.springframework.web.context.support.WebApplicationContextUtils;


/**
 * Statics and helper methods for the Roller web application/context.
 *
 * <p>Startup/shutdown used to happen here too (this class extended
 * {@code ContextLoaderListener}/{@code ServletContextListener}); that role
 * has moved to {@code com.wikantik.pressroom.boot.PressroomLifecycle}, a
 * Spring Boot {@code SmartLifecycle} bean. This class now only holds the
 * statics that lifecycle/security config populate ({@link #getServletContext()},
 * {@link #getPasswordEncoder()}) plus the helper methods they call
 * ({@link #createPasswordEncoder()}) and the one
 * controllers/filters still call directly ({@link #flushAuthenticationUserCache(String)}).
 *
 * <p>Until Stage 1B Task 4, {@code initializeSecurityFeatures(ServletContext)}
 * built the password encoder and mutated Spring Security beans looked up by
 * the internal names the {@code security.xml} namespace parser assigned them.
 * That method is gone: {@code com.wikantik.pressroom.boot.SecurityConfig}
 * now builds those beans directly with {@code @Bean} methods (constructed
 * during context refresh, before {@code PressroomLifecycle.start()} ever runs),
 * calling {@link #createPasswordEncoder()} itself and publishing the result
 * here via {@link #setPasswordEncoder(PasswordEncoder)} so
 * {@link com.wikantik.pressroom.pojos.User}'s existing
 * {@link #getPasswordEncoder()} call site keeps working unchanged.
 */
public final class PressroomContext {

    private static final Logger log = LoggerFactory.getLogger(PressroomContext.class);

    private static ServletContext servletContext = null;
    private static PasswordEncoder encoder;


    private PressroomContext() {
        // static helpers only; PressroomLifecycle owns the startup sequence now
    }


    /**
     * Called once by {@code PressroomLifecycle.start()} to publish the
     * {@link ServletContext} that used to be captured in
     * {@code contextInitialized}.
     */
    @SuppressFBWarnings(
            value = "EI_EXPOSE_STATIC_REP2",
            justification = "ServletContext is the servlet container's own live, singleton "
                    + "representation of the running webapp -- it is an interface with no "
                    + "meaningful defensive copy, and holding a reference to the container's actual "
                    + "instance is the entire point of this method (every PressroomContext caller needs "
                    + "the real one, not a snapshot).")
    public static void hold(ServletContext sc) {
        servletContext = sc;
    }


    /**
     * Get the ServletContext.
     *
     * @return ServletContext
     */
    public static ServletContext getServletContext() {
        return servletContext;
    }

    public static PasswordEncoder getPasswordEncoder() {
        return encoder;
    }

    /**
     * Called once by {@code SecurityConfig}'s {@code passwordEncoder()} bean
     * method, immediately after building the encoder with
     * {@link #createPasswordEncoder()}, so this static accessor stays
     * populated for {@link com.wikantik.pressroom.pojos.User} the same
     * way it always has been -- just published at Spring context-refresh
     * time now instead of from {@code PressroomLifecycle.start()}.
     */
    public static void setPasswordEncoder(PasswordEncoder passwordEncoder) {
        encoder = passwordEncoder;
    }

    /**
     * The property that used to make this method register a no-op encoder.
     *
     * <p>Removed outright: encryption is not optional. Named here only so that
     * an explicitly-set value fails loudly rather than being ignored.
     */
    static final String REMOVED_ENCRYPTION_FLAG = "passwds.encryption.enabled";

    /**
     * Fails when a deployer explicitly sets the removed encryption flag.
     *
     * <p>Silently ignoring it is the failure mode this exists to prevent: a
     * deploy carrying {@code ROLLER_PASSWDS_ENCRYPTION_ENABLED=false} would
     * boot looking configured while behaving differently than its operator
     * believes. Same convention as an unsupported {@code authentication.method}.
     *
     * @param configuredValue the raw configured value, or null when absent
     */
    static void rejectRemovedEncryptionFlag(String configuredValue) {
        if (configuredValue != null) {
            throw new IllegalStateException(REMOVED_ENCRYPTION_FLAG
                    + " is no longer supported and must be removed from your configuration."
                    + " Password encryption is always on; choose the algorithm with"
                    + " passwds.encryption.algorithm.");
        }
    }

    /**
     * Builds the {@link DelegatingPasswordEncoder} Roller's configured
     * password-encryption settings ({@code passwds.encryption.*}) describe.
     *
     * <p>Called exactly once, by {@code SecurityConfig}'s
     * {@code passwordEncoder()} {@code @Bean} method, which immediately
     * hands the result to {@link #setPasswordEncoder(PasswordEncoder)}. Public
     * (was {@code private}, folded into the old {@code initializeSecurityFeatures})
     * so {@code SecurityConfig} -- which needs the encoder to build its
     * {@code DaoAuthenticationProvider} during context refresh, before
     * {@code PressroomLifecycle.start()} ever runs -- can call it directly
     * instead of going through a by-name Spring bean lookup that no longer
     * exists now that {@code security.xml} is gone.
     */
    public static DelegatingPasswordEncoder createPasswordEncoder() {

        rejectRemovedEncryptionFlag(WebloggerConfig.getProperty(REMOVED_ENCRYPTION_FLAG));

        Map<String, PasswordEncoder> encoders = new HashMap<>();

        // Outdated digest encoders, for lazy upgrade from pws encoded by old
        // roller versions. `plaintext` is deliberately NOT accepted: it
        // registered a no-op encoder against the null prefix, which made an
        // unprefixed stored string authenticate as plaintext.
        String migrateFrom = WebloggerConfig.getProperty("passwds.encryption.lazyUpgradeFrom");

        if(migrateFrom == null || migrateFrom.isEmpty()) {
            log.debug("lazy pw upgrade disabled");
        } else if ("MD5".equals(migrateFrom)) {
            encoders.put(null, legacyDigestEncoder("MD5"));
        } else if ("SHA".equals(migrateFrom)) {
            encoders.put(null, legacyDigestEncoder("SHA-1"));
        } else {
            throw new RuntimeException("passwds.encryption.lazyUpgradeFrom="+migrateFrom+" is no valid encoding to upgrade from.");
        }
        
        // supported encoders
        encoders.put("bcrypt", new BCryptPasswordEncoder());
        encoders.put("pbkdf2", Pbkdf2PasswordEncoder.defaultsForSpringSecurity_v5_8());
        // provided by bouncy castle dependency
        encoders.put("scrypt", SCryptPasswordEncoder.defaultsForSpringSecurity_v5_8());
        encoders.put("argon2", Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8());

        String algorithm = WebloggerConfig.getProperty("passwds.encryption.algorithm");

        if ("SHA".equals(algorithm) || "MD5".equals(algorithm)) {
            throw new RuntimeException("passwds.encryption.algorithm="+algorithm+" is outdated,"
                    + " please set passwds.encryption.algorithm to 'bcrypt' for automatic lazy upgrade.");
        }

        if (!encoders.containsKey(algorithm)) {
            throw new RuntimeException("passwds.encryption.algorithm="+algorithm+" is not supported.");
        }

        log.info("Password Encryption Algorithm set to '{}'", algorithm);
        
        return new DelegatingPasswordEncoder(algorithm, encoders);
    }

    // Spring Security deprecated MessageDigestPasswordEncoder and offers no
    // replacement for the one job it has here: recognising an unprefixed
    // MD5/SHA-1 hash from a pre-migration database so the user can sign in once
    // and be upgraded. Only reachable when passwds.encryption.lazyUpgradeFrom is set.
    @SuppressWarnings("deprecation")
    private static PasswordEncoder legacyDigestEncoder(String algorithm) {
        return new org.springframework.security.crypto.password.MessageDigestPasswordEncoder(algorithm);
    }


    /**
     * Flush user from any caches maintained by security system.
     */
    public static void flushAuthenticationUserCache(String userName) {
        ApplicationContext ctx =
                WebApplicationContextUtils.getRequiredWebApplicationContext(servletContext);
        try {
            UserCache userCache = (UserCache) ctx.getBean("userCache");
            if (userCache != null) {
                userCache.removeUserFromCache(userName);
            }
        } catch (NoSuchBeanDefinitionException exc) {
            log.debug("No userCache bean in context", exc);
        }
    }


}
