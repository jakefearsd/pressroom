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

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import com.wikantik.pressroom.TestUtils;
import com.wikantik.pressroom.business.PropertiesManager;
import com.wikantik.pressroom.config.runtime.ConfigDef;
import com.wikantik.pressroom.config.runtime.DisplayGroup;
import com.wikantik.pressroom.config.runtime.PropertyDef;
import com.wikantik.pressroom.pojos.RuntimeConfigProperty;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * The properties promoted from startup-only to runtime-settable.
 *
 * <p>These three used to be readable only from {@code roller.properties}, which
 * meant the only way to exercise the other branch of any of them was to restart
 * the server with a different file. They now live in
 * {@code runtimeConfigDefs.xml} as well, so one running instance can be
 * reconfigured across all of them -- which is what lets the browser suite cover
 * these permutations without a second boot.
 *
 * <p>Promotion puts a property name in two files at once, and that is the
 * hazard these tests exist for. The database row wins once it exists, so a
 * drifting default would give a fresh install and an upgraded one different
 * behaviour from identical configuration, and a naive seeding would throw away
 * whatever the deployer had set in {@code roller-custom.properties}.
 */
class PromotedRuntimePropertyTest {

    /**
     * Two of these read the seeded database rows, so the properties manager has
     * to have run its initialization -- which is what bootstrapping does.
     */
    @BeforeEach
    void setUp() throws Exception {
        TestUtils.setupWeblogger();
    }

    /**
     * Startup-scoped properties made runtime-settable. Deliberately a literal
     * list rather than something derived: the point is to state which
     * properties were promoted, so that removing one from either file is a
     * test failure rather than a silent change of scope.
     */
    private static final List<String> PROMOTED = List.of(
            "groupblogging.enabled",
            "user.hideUserNames",
            "weblogentry.title.useUnderscoreSeparator");

    /**
     * The comment subsystem's six runtime properties -- {@code
     * users.comments.enabled}, {@code users.comments.htmlenabled}, {@code
     * users.comments.plugins}, {@code users.comments.emailnotify}, {@code
     * users.moderation.required} and {@code comment.throttle.enabled} -- are
     * gone from {@code runtimeConfigDefs.xml} along with the feature they
     * configured; this pins that none of them, nor the moderation namespace,
     * crept back in.
     */
    @Test
    void noCommentPropertiesRemainInRuntimeConfig() throws Exception {
        String xml = Files.readString(Path.of(
                "src/main/resources/com/wikantik/pressroom/config/runtimeConfigDefs.xml"));
        assertFalse(xml.contains("users.comments."), "comment runtime properties survive");
        assertFalse(xml.contains("comment.throttle."), "comment throttle property survives");
        assertFalse(xml.contains("users.moderation."), "moderation property survives");
    }

    /**
     * Both files must agree. {@code roller.properties} is what a running
     * instance falls back to before the row is seeded and what
     * {@code JPAPropertiesManagerImpl} seeds the row *from*; the
     * {@code <default-value>} is what a deployment with no such file entry
     * would get. If they disagree, the same configuration produces different
     * behaviour depending on how the installation got there.
     */
    @Test
    void everyPromotedPropertyIsDefinedInBothPlacesWithTheSameDefault() {
        for (String name : PROMOTED) {
            PropertyDef def = propertyDef(name);
            assertNotNull(def, name + " must have a property-def in runtimeConfigDefs.xml; "
                    + "without one it is not runtime-settable at all");

            String startupValue = WebloggerConfig.getProperty(name);
            assertNotNull(startupValue, name + " must stay in roller.properties: it is the "
                    + "fallback before the row is seeded, and the value the row is seeded from");

            assertEquals(startupValue, def.getDefaultValue(),
                    name + " has drifted between roller.properties and runtimeConfigDefs.xml. "
                            + "A fresh install takes the property-def default and an upgraded "
                            + "one takes the file value, so they would behave differently.");
        }
    }

    /**
     * Seeding a missing row must take the startup config's value, not the
     * definition's default.
     *
     * <p>This is the upgrade path: an installation whose {@code
     * roller-custom.properties} turns one of these off boots for the first
     * time on the release that promoted it, and the row does not exist yet.
     * The row is authoritative from the moment it is written, so seeding the
     * shipped default here would discard the deployer's setting permanently
     * and silently -- the file would never be consulted again.
     *
     * <p>Deliberately drives {@code initializeMissingProps} against an empty
     * map rather than comparing the two live values: the two files ship
     * matching defaults (the test above requires it), so a comparison of what
     * the running instance reports would agree either way and prove nothing.
     * The value used here is the opposite of the shipped default, so only the
     * startup config can be its source.
     */
    @Test
    void seedingAMissingRowTakesTheStartupValueSoAnUpgradeKeepsTheDeployersChoice() throws Exception {
        String name = "groupblogging.enabled";
        String shippedDefault = propertyDef(name).getDefaultValue();
        String deployerValue = Boolean.toString(!Boolean.parseBoolean(shippedDefault));

        String previous = overrideStartupProperty(name, deployerValue);
        try {
            Map<String, RuntimeConfigProperty> seeded = seedInto(new HashMap<>());

            assertEquals(deployerValue, seeded.get(name).getValue(),
                    name + " was seeded with the shipped default instead of the value in the "
                            + "startup config, so upgrading to the release that promoted it "
                            + "would silently overwrite what the deployer had set");
        } finally {
            overrideStartupProperty(name, previous);
        }
    }

    /**
     * The point of the exercise: changing one of these at runtime is what the
     * code reads next, with no restart. Checked through
     * {@code WebloggerRuntimeConfig}, which is the accessor every promoted call
     * site now uses.
     */
    @Test
    void aPromotedPropertyCanBeFlippedWhileTheServerRuns() throws Exception {
        for (String name : PROMOTED) {
            boolean original = WebloggerRuntimeConfig.getBooleanProperty(name);
            try {
                setRuntimeProperty(name, Boolean.toString(!original));
                assertEquals(!original, WebloggerRuntimeConfig.getBooleanProperty(name),
                        name + " did not take a new value at runtime, so promoting it bought "
                                + "nothing -- something is still reading it at startup");
            } finally {
                setRuntimeProperty(name, Boolean.toString(original));
                TestUtils.endSession(true);
            }
        }
    }

    /**
     * Runs the seeding pass over the given map, exactly as bootstrap does for a
     * database that is missing property rows, and hands back what it produced.
     *
     * <p>Reflective because the method is private and has no reason to be
     * anything else; nothing persists, so this leaves the database alone.
     */
    @SuppressWarnings("unchecked")
    private static Map<String, RuntimeConfigProperty> seedInto(
            Map<String, RuntimeConfigProperty> props) throws Exception {
        PropertiesManager pmgr = TestUtils.weblogger().getPropertiesManager();
        Method seed = pmgr.getClass()
                .getDeclaredMethod("initializeMissingProps", Map.class);
        seed.setAccessible(true);
        return (Map<String, RuntimeConfigProperty>) seed.invoke(pmgr, props);
    }

    /**
     * Overrides a value in the startup configuration, standing in for a
     * deployer's {@code roller-custom.properties} entry, and returns what was
     * there before. The backing {@code Properties} is process-global, so
     * callers must put it back.
     */
    private static String overrideStartupProperty(String name, String value) throws Exception {
        Field field = WebloggerConfig.class.getDeclaredField("config");
        field.setAccessible(true);
        Properties config = (Properties) field.get(null);
        String previous = config.getProperty(name);
        if (value == null) {
            config.remove(name);
        } else {
            config.setProperty(name, value);
        }
        return previous;
    }

    private static void setRuntimeProperty(String name, String value) throws Exception {
        PropertiesManager pmgr = TestUtils.weblogger().getPropertiesManager();
        Map<String, RuntimeConfigProperty> config = pmgr.getProperties();
        config.get(name).setValue(value);
        pmgr.saveProperties(config);
        TestUtils.weblogger().flush();
    }

    private static PropertyDef propertyDef(String name) {
        for (ConfigDef configDef : WebloggerRuntimeConfig.getRuntimeConfigDefs().getConfigDefs()) {
            for (DisplayGroup group : configDef.getDisplayGroups()) {
                for (PropertyDef def : group.getPropertyDefs()) {
                    if (name.equals(def.getName())) {
                        return def;
                    }
                }
            }
        }
        return null;
    }
}
