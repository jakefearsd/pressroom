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

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;
import java.util.regex.Pattern;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.wikantik.pressroom.util.PropertyExpander;


/**
 * This is the single entry point for accessing configuration properties in Pressroom.
 */
public final class WebloggerConfig {
    
    private static final String default_config = "pressroom.properties";
    private static final String custom_config = "/pressroom-custom.properties";
    private static final String junit_config = "/pressroom-junit.properties";
    private static final String custom_jvm_param = "pressroom.custom.config";

    private static final String env_prefix = "PRESSROOM_";

    private static final Pattern SECRET_KEY =
            Pattern.compile("password|token|secret", Pattern.CASE_INSENSITIVE);

    private static final Properties config;

    /**
     * What {@link #loadLayers} found, kept until the logger exists and then
     * logged: the layers load first because one of them may name the log4j2
     * configuration file.
     */
    private static final List<BootNote> bootNotes = new ArrayList<>();

    private static final Logger log;
    

    /*
     * Static block run once at class loading
     *
     * We load the default properties and any custom properties we find
     */
    static {
        config = new Properties();

        runAtClassLoad(() -> loadLayers(System.getenv(), System.getProperties()));

        // tell log4j2 to use the optionally specified config file instead of Pressroom's,
        // but only if it hasn't already been set with -D at JVM startup.
        String log4j2ConfigKey = "log4j.configurationFile";
        String customLog4j2File = config.getProperty(log4j2ConfigKey);
        if(customLog4j2File != null && !customLog4j2File.isBlank() && System.getProperty(log4j2ConfigKey) == null) {
            System.setProperty(log4j2ConfigKey, customLog4j2File);
        }
        
        // this bridges java.util.logging -> SLF4J which ends up being log4j2, probably.
        org.slf4j.bridge.SLF4JBridgeHandler.removeHandlersForRootLogger();
        org.slf4j.bridge.SLF4JBridgeHandler.install();
        
        // finally we can start logging...
        log = LoggerFactory.getLogger(WebloggerConfig.class);
        for (BootNote note : bootNotes) {
            if (note.warning()) {
                log.warn("{}", note.text());
            } else {
                log.info("{}", note.text());
            }
        }

        // some debugging for those that want it
        if(log.isDebugEnabled()) {
            log.debug("WebloggerConfig looks like this ...");

            String key;
            Enumeration<Object> keys = config.keys();
            while(keys.hasMoreElements()) {
                key = (String) keys.nextElement();
                log.debug("{}={}", key, maskSecret(key, config.getProperty(key)));
            }
        }

    }

    /**
     * Loads the default, junit, custom, environment and expanded layers into
     * {@link #config}. It runs before logging can exist (the log4j2
     * configuration file is itself named by a property this method loads), so
     * what it finds goes into {@link #bootNotes}, which the static initializer
     * logs once the logger is up.
     */
    @SuppressFBWarnings(
            value = "PATH_TRAVERSAL_IN",
            justification = "The path comes from the operator's -Dpressroom.custom.config JVM property, "
                    + "read once at startup; no request input reaches it.")
    static void loadLayers(Map<String, String> env, Properties systemProperties) throws Exception {
        // Refuse before any file is read: an old name left in the deploy would otherwise be ignored silently.
        LegacyConfigNames.refuse(env, systemProperties);

        // we'll need this to get at our properties files in the classpath
        Class<?> configClass = WebloggerConfig.class;

        // first, lets load our default properties
        try (InputStream is = configClass.getResourceAsStream(default_config)) {
            config.load(is);
        }
        
        // first, see if we can find our junit testing config
        try (InputStream test = configClass.getResourceAsStream(junit_config)) {
            
            if (test != null) {

                config.load(test);
                note(false, "Loaded the junit properties from the classpath: "
                        + configClass.getResource(junit_config).getFile());

            } else {

                // now, see if we can find our custom config
                try(InputStream custom = configClass.getResourceAsStream(custom_config)) {

                    if (custom != null) {
                        config.load(custom);
                        note(false, "Loaded the custom properties from the classpath: "
                                + configClass.getResource(custom_config).getFile());
                    } else {
                        note(false, "No custom properties file found on the classpath (pressroom-custom.properties)");
                    }
                }
            }
        }

        // finally, check for an external config file
        String envFile = systemProperties.getProperty(custom_jvm_param);
        if(envFile != null && envFile.length() > 0) {
            File customConfigFile = new File(envFile);

            // make sure the file exists, then try and load it
            if(customConfigFile.exists()) {
                try(InputStream is = new FileInputStream(customConfigFile)) {
                    config.load(is);
                }
                note(false, "Loaded the custom properties from " + customConfigFile.getAbsolutePath());
            } else {
                note(true, "No custom properties file at " + customConfigFile.getAbsolutePath()
                        + " (named by -D" + custom_jvm_param + "); carrying on without it");
            }

        }

        // Highest-precedence layer: PRESSROOM_* environment variables. Placed
        // before property expansion below so an env-supplied value can
        // still carry ${...} references.
        applyEnvironmentOverrides(config, env);

        // Now expand system properties for properties in the config.expandedProperties list,
        // replacing them by their expanded values.
        String expandedPropertiesDef = config.getProperty("config.expandedProperties");
        if (expandedPropertiesDef != null) {
            String[] expandedProperties = expandedPropertiesDef.split(",");
            for (String expandedProperty : expandedProperties) {
                String propName = expandedProperty.trim();
                String initialValue = config.getProperty(propName);
                if (initialValue != null) {
                    String expandedValue = PropertyExpander.expandSystemProperties(initialValue);
                    config.setProperty(propName, expandedValue);
                }
            }
        }

    }

    /**
     * Runs {@code work}, escalating a {@link RuntimeException} (a
     * programming or configuration error) but only logging anything else (a
     * missing optional file, matched by the broad {@code Exception} catch
     * this class has always used here). Extracted from the static
     * initializer above purely so {@code WebloggerConfigTest} can drive both
     * catch clauses directly -- the initializer itself runs exactly once, at
     * class-load, before any test in the suite can intervene, so this
     * extraction is the only seam that reaches either branch. Package-
     * private, not private, for that test.
     */
    static void runAtClassLoad(ExceptionalRunnable work) {
        try {
            work.run();
        } catch (RuntimeException e) {
            // A RuntimeException here is a programming or configuration
            // error, not a missing optional file -- do not boot on it.
            printBootstrapFailure(e);
            throw e;
        } catch (Exception e) {
            printBootstrapFailure(e);
        }
    }

    // The logger does not exist yet when this runs (see loadLayers), so stderr
    // is the only place the failure can go.
    @SuppressWarnings("PMD.AvoidPrintStackTrace")
    @SuppressFBWarnings(
            value = "INFORMATION_EXPOSURE_THROUGH_AN_ERROR_MESSAGE",
            justification = "Runs once at class load, before logging is configured; printStackTrace writes "
                    + "to the server's stderr, which reaches the container log and never a response.")
    private static void printBootstrapFailure(Exception e) {
        e.printStackTrace();
    }

    private static void note(boolean warning, String text) {
        bootNotes.add(new BootNote(warning, text));
    }

    /** One finding of {@link #loadLayers}, logged at WARN or INFO once logging is up. */
    private record BootNote(boolean warning, String text) {}

    /** What loading the configuration layers reported, in order. Package-private for its test. */
    static List<String> bootReport() {
        return bootNotes.stream().map(BootNote::text).toList();
    }

    /** A block of work that may throw a checked exception. */
    @FunctionalInterface
    interface ExceptionalRunnable {
        void run() throws Exception;
    }


    // no, you may not instantiate this class :p
    private WebloggerConfig() {}

    /**
     * Overlay {@code PRESSROOM_*} environment variables onto the loaded
     * configuration. This is the highest-precedence layer, which is what lets
     * the production image take its whole configuration from the deploy host's
     * .env with no properties file mounted (see
     * docs/superpowers/specs/2026-08-14-container-push-deployment-design.md).
     *
     * <p>The name mapping strips the prefix, lowercases, and turns {@code _}
     * into {@code .}. A derived name that matches an existing key
     * case-insensitively is written to that key's ORIGINAL spelling, so
     * {@code PRESSROOM_DATABASE_JDBC_DRIVERCLASS} reaches
     * {@code database.jdbc.driverClass} instead of quietly creating a
     * lowercase twin nothing reads. A derived name matching nothing is used
     * as-is, which is required rather than incidental: {@code mail.port} has no
     * entry in pressroom.properties and {@code uploads.dir} is commented out, so
     * an allowlist of keys with active defaults could set neither.
     *
     * <p>Package-private and taking an explicit map so it can be tested;
     * {@code System.getenv()} is unmodifiable and the caller below is a static
     * initializer that runs once per JVM.
     */
    @SuppressFBWarnings(
            value = "IMPROPER_UNICODE",
            justification = "Matches PRESSROOM_ environment variable names to pressroom.properties keys "
                    + "case-insensitively: operator configuration read once at startup, no request input.")
    static void applyEnvironmentOverrides(Properties config, Map<String, String> env) {

        Map<String, String> knownByLowerCase = new HashMap<>();
        for (String key : config.stringPropertyNames()) {
            String previous = knownByLowerCase.put(key.toLowerCase(Locale.ROOT), key);
            if (previous != null && !previous.equals(key)) {
                throw new IllegalStateException("Configuration keys '" + previous + "' and '"
                        + key + "' differ only in case, so an environment override cannot "
                        + "address them unambiguously.");
            }
        }

        // Sorted for deterministic behaviour when two variables derive the same key.
        for (Map.Entry<String, String> entry : new TreeMap<>(env).entrySet()) {
            String name = entry.getKey();
            if (!name.startsWith(env_prefix) || name.length() == env_prefix.length()) {
                continue;
            }
            String derived = name.substring(env_prefix.length())
                                 .toLowerCase(Locale.ROOT)
                                 .replace('_', '.');
            config.setProperty(knownByLowerCase.getOrDefault(derived, derived), entry.getValue());
        }
    }

    /**
     * Replace a secret's value with a fixed mask for logging. The debug dump
     * below prints every key and value, and the environment overlay makes
     * turning that on while diagnosing a configuration problem much more
     * likely.
     */
    static String maskSecret(String key, String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        return SECRET_KEY.matcher(key).find() ? "********" : value;
    }

    /**
     * Loads Pressroom's configuration.
     * Call this as early as possible in the lifecycle.
     */
    public static void init() {
        // triggers static block
    }
    
    /**
     * Retrieve a property value
     * @param     key Name of the property
     * @return    String Value of property requested, null if not found
     */
    public static String getProperty(String key) {
        String value = config.getProperty(key);
        log.debug("Fetching property [{}={}]", key, value);
        return value == null ? null : value.trim();
    }
    
    /**
     * Retrieve a property value
     * @param     key Name of the property
     * @param     defaultValue Default value of property if not found     
     * @return    String Value of property requested or defaultValue
     */
    public static String getProperty(String key, String defaultValue) {
        String value = config.getProperty(key);
        log.debug("Fetching property [{}={},defaultValue={}]", key, value, defaultValue);
        if (value == null) {
            return defaultValue;
        }
        
        return value.trim();
    }

    /**
     * Retrieve a property as a boolean ... defaults to false if not present.
     */
    public static boolean getBooleanProperty(String name) {
        return getBooleanProperty(name,false);
    }

    /**
     * Retrieve a property as a boolean ... with specified default if not present.
     */
    public static boolean getBooleanProperty(String name, boolean defaultValue) {
        // get the value first, then convert
        String value = getProperty(name);

        if(value == null) {
            return defaultValue;
        }

        return Boolean.parseBoolean(value);
    }

    /**
     * Retrieve a property as an int ... defaults to 0 if not present.
     */
    public static int getIntProperty(String name) {
        return getIntProperty(name, 0);
    }

    /**
     * Retrieve a property as a int ... with specified default if not present.
     */
    public static int getIntProperty(String name, int defaultValue) {
        // get the value first, then convert
        String value = getProperty(name);

        if (value == null) {
            return defaultValue;
        }

        return Integer.parseInt(value);
    }

    /**
     * Retrieve all property keys
     * @return Enumeration A list of all keys
     **/
    public static Enumeration<Object> keys() {
        return config.keys();
    } 

    /**
     * Set the "uploads.dir" property at runtime.
     * <p />
     * Properties are meant to be read-only, but we make this exception because  
     * we know that some people are still writing their uploads to the webapp  
     * context and we can only get that path at runtime (and for unit testing).
     * <p />
     * This property is *not* persisted in any way.
     */
    public static void setUploadsDir(String path) {
        // only do this if the user wants to use the webapp context
        if("${webapp.context}".equals(config.getProperty("uploads.dir"))) {
            config.setProperty("uploads.dir", path);
        }
    }
    
    /**
     * Set the "themes.dir" property at runtime.
     * <p />
     * Properties are meant to be read-only, but we make this exception because  
     * we know that some people are still using their themes in the webapp  
     * context and we can only get that path at runtime (and for unit testing).
     * <p />
     * This property is *not* persisted in any way.
     */
    public static void setThemesDir(String path) {
        // only do this if the user wants to use the webapp context
        if("${webapp.context}".equals(config.getProperty("themes.dir"))) {
            config.setProperty("themes.dir", path);
        }
    }

    /**
     * Return the value of the authentication.method property as an AuthMethod
     * enum value.  Matching is done by checking the propertyName of each AuthMethod
     * enum object.
     * <p />
     * @throws IllegalArgumentException if property value defined in the properties
     * file is missing or not the property name of any AuthMethod enum object.
     */
    public static AuthMethod getAuthMethod() {
        return AuthMethod.getAuthMethod(getProperty("authentication.method"));
    }
    
}
