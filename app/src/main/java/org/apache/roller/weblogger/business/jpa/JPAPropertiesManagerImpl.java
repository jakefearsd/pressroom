
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
package org.apache.roller.weblogger.business.jpa;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.roller.weblogger.WebloggerException;
import org.apache.roller.weblogger.business.InitializationException;
import org.apache.roller.weblogger.business.PropertiesManager;
import org.apache.roller.weblogger.config.WebloggerConfig;
import org.apache.roller.weblogger.config.WebloggerRuntimeConfig;
import org.apache.roller.weblogger.config.runtime.ConfigDef;
import org.apache.roller.weblogger.config.runtime.DisplayGroup;
import org.apache.roller.weblogger.config.runtime.PropertyDef;
import org.apache.roller.weblogger.config.runtime.RuntimeConfigDefs;
import org.apache.roller.weblogger.pojos.RuntimeConfigProperty;


/*
 * JPAPropertiesManagerImpl.java
 *
 * Created on May 29, 2006, 2:06 PM
 *
 */
public class JPAPropertiesManagerImpl implements PropertiesManager {
    
    /** The logger instance for this class. */
    private static final Logger log = LoggerFactory.getLogger(
        JPAPropertiesManagerImpl.class);

    private final JPAPersistenceStrategy strategy;

    /**
     * The committed values, read on every request (a dozen times a render)
     * and changed only by a save through this manager, which invalidates them.
     */
    private final RuntimeConfigSnapshot committed = new RuntimeConfigSnapshot();
    
    
    /**
     * Creates a new instance of JPAPropertiesManagerImpl
     */
    public JPAPropertiesManagerImpl(JPAPersistenceStrategy strategy) {
        log.debug("Instantiating JPA Properties Manager");
        this.strategy = strategy;
        // Any committed write of a row, saved through here or not: an entity
        // from getProperties() edited in place is written by whichever commit
        // comes next.
        strategy.onRowWrite(RuntimeConfigProperty.class, this::invalidateCommittedValues);
    }
    
    
    /**
     * @inheritDoc
     */
    @Override
    public void initialize() throws InitializationException {
        
        Map<String, RuntimeConfigProperty> props;
        try {
            // retrieve properties from database
            props = this.getProperties();

            // if any default props missing from the properties DB table,
            // initialize them and save them to that table.
            initializeMissingProps(props);
            this.saveProperties(props);

        } catch (Exception e) {
            log.error("Failed to initialize runtime configuration properties."+
                    "Please check that the database has been upgraded!", e);
            throw new RuntimeException(e);
        }
        
    }
    
    
    /**
     * Retrieve a single property by name.
     *
     * <p>Answered from the remembered committed values as a new, unmanaged
     * {@link RuntimeConfigProperty} each call; edit it and hand it to
     * {@link #saveProperty}, which merges. Only while this unit of work has
     * uncommitted work -- its own save may be among it -- is the row read
     * through the EntityManager, as before.
     */
    @Override
    public RuntimeConfigProperty getProperty(String name) throws WebloggerException {
        if (strategy.hasUncommittedWork()) {
            return (RuntimeConfigProperty) strategy.load(RuntimeConfigProperty.class, name);
        }
        Map<String, String> values = committed.values(this::loadCommittedValues);
        return values.containsKey(name) ? new RuntimeConfigProperty(name, values.get(name)) : null;
    }

    /**
     * Every row's name and value. A projection reads the rows themselves, so
     * an entity this unit of work edited and never saved cannot leak in; and
     * {@link #getProperty} calls it only when there is nothing uncommitted.
     */
    private Map<String, String> loadCommittedValues() throws WebloggerException {
        Map<String, String> values = new HashMap<>();
        for (Object[] row : strategy.getNamedQuery("RuntimeConfigProperty.getAllValues",
                Object[].class).getResultList()) {
            values.put((String) row[0], (String) row[1]);
        }
        return values;
    }


    /**
     * Retrieve all properties.
     * 
     * Properties are returned in a Map to make them easy to lookup.  The Map
     * uses the property name as the key and the RuntimeConfigProperty object
     * as the value.
     *
     * <p>They are managed entities, there to be edited, so this begins the
     * unit of work's transaction: from here until it commits or is released,
     * {@link #getProperty} reads through the EntityManager and sees the edits,
     * saved or not, as it always has.
     */
    @Override
    public Map<String, RuntimeConfigProperty> getProperties() throws WebloggerException {

        strategy.getEntityManager(true);
        Map<String, RuntimeConfigProperty> props = new HashMap<>();
        List<RuntimeConfigProperty> list = strategy.getNamedQuery("RuntimeConfigProperty.getAll",
                RuntimeConfigProperty.class).getResultList();
        /*
         * for convenience sake we are going to put the list of props
         * into a map for users to access it.  The value element of the
         * hash still needs to be the RuntimeConfigProperty object so that
         * we can save the elements again after they have been updated
         */
        for (RuntimeConfigProperty prop : list) {
            props.put(prop.getName(), prop);
        }
        return props;
    }


    /**
     * Save a single property. Merged, so the unmanaged copy
     * {@link #getProperty} hands out saves as well as a managed instance.
     */
    @Override
    public void saveProperty(RuntimeConfigProperty property) 
            throws WebloggerException {
        strategy.getEntityManager(true).merge(property);
    }


    /**
     * Save all properties.
     */
    @Override
    public void saveProperties(Map<String, RuntimeConfigProperty> properties) throws WebloggerException {

        // just go through the list and saveProperties each property
        for (RuntimeConfigProperty prop : properties.values()) {
            strategy.getEntityManager(true).merge(prop);
        }
    }

    /**
     * Once the commit that writes a row has succeeded, and not before: a read
     * between the row write and the database commit loads the rows being
     * replaced (see {@link RuntimeConfigSnapshot}), so only an invalidation
     * after the commit drops everything stale. One at the write as well would
     * change nothing -- the writing unit of work reads through its
     * EntityManager, and any other reader before the commit sees the old
     * rows either way.
     */
    private void invalidateCommittedValues() {
        strategy.runAfterCommit(committed::invalidate);
    }
    

    /**
     * This method compares the property definitions in the RuntimeConfigDefs
     * file with the properties in the given Map and initializes any properties
     * that were not found in the Map.
     *
     * If the Map of props is empty/null then we will initialize all properties.
     **/
    private Map<String, RuntimeConfigProperty> initializeMissingProps(Map<String, RuntimeConfigProperty> props) {

        if(props == null) {
            props = new HashMap<>();
        }

        // start by getting our runtimeConfigDefs
        RuntimeConfigDefs runtimeConfigDefs =
                WebloggerRuntimeConfig.getRuntimeConfigDefs();

        // can't do initialization without our config defs
        if(runtimeConfigDefs == null) {
            return props;
        }

        // iterate through all the definitions and add properties
        // that are not already in our props map

        for (ConfigDef configDef : runtimeConfigDefs.getConfigDefs()) {
            for (DisplayGroup dGroup : configDef.getDisplayGroups()) {
                for (PropertyDef propDef : dGroup.getPropertyDefs()) {

                    // do we already have this prop?  if not then add it
                    if(!props.containsKey(propDef.getName())) {
                        String initialValue = initialValueFor(propDef);
                        RuntimeConfigProperty newprop =
                                new RuntimeConfigProperty(
                                        propDef.getName(), initialValue);

                        props.put(propDef.getName(), newprop);

                        log.info("Property {} not yet in roller_properties database table, will store with "
                            + "value of [{}`]", propDef.getName(), initialValue);
                    }
                }
            }
        }

        return props;
    }


    /**
     * The value a property is stored with the first time it is seeded.
     *
     * <p>A value already present in {@code roller.properties} (or the
     * deployer's {@code roller-custom.properties} overlay) wins over the
     * definition's {@code <default-value>}. That matters when a property is
     * <em>promoted</em> from startup-only to runtime-settable: the property
     * name then appears in both files, and without this the first boot after
     * the upgrade would seed the shipped default and silently discard the
     * deployer's choice -- the DB row is authoritative from then on, so the
     * startup file would never be consulted again.
     *
     * <p>For a property that exists only as a definition (the usual case)
     * there is nothing in the startup config to find and the default applies
     * unchanged.
     */
    private String initialValueFor(PropertyDef propDef) {
        String configured = WebloggerConfig.getProperty(propDef.getName());
        return configured != null ? configured : propDef.getDefaultValue();
    }


    @Override
    public void release() {}

}
