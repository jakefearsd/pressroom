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

package com.wikantik.pressroom.util.cache;

import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.wikantik.pressroom.util.PressroomConstants;


/**
 * An LRU cache where entries expire after a given timeout period.
 */
public class ExpiringLRUCacheImpl extends LRUCacheImpl {

    private static final Logger log = LoggerFactory.getLogger(ExpiringLRUCacheImpl.class);

    private long timeout = 0;


    protected ExpiringLRUCacheImpl(String id) {

        super(id);
        this.timeout = PressroomConstants.HOUR_IN_MS;
    }


    protected ExpiringLRUCacheImpl(String id, int maxsize, long timeout) {
        this(id, maxsize, timeout, System::currentTimeMillis);
    }


    ExpiringLRUCacheImpl(String id, int maxsize, long timeout, LongSupplier clock) {

        super(id, maxsize, clock);

        // timeout is specified in seconds; only positive values allowed
        if (timeout > 0) {
            this.timeout = timeout * PressroomConstants.SEC_IN_MS;
        }
    }


    /**
     * How long a cached entry survives, in milliseconds. Package private: the
     * constructor takes seconds and the entries expire in milliseconds, and
     * that conversion is worth being able to assert on directly.
     */
    long getTimeoutMillis() {
        return this.timeout;
    }

    
    /**
     * Store an entry in the cache.
     *
     * We wrap the cached object in our ExpiringCacheEntry object so that we
     * can track when the entry has expired.
     */
    @Override
    public synchronized void put(String key, Object value) {
        
        ExpiringCacheEntry entry = new ExpiringCacheEntry(value, this.timeout, clock.getAsLong());
        super.put(key, entry);
    }
    
    
    /**
     * Retrieve an entry from the cache.
     *
     * This LRU cache supports timeouts, so if the cached object has expired
     * then we return null, just as if the entry wasn't found.
     */
    @Override
    public synchronized Object get(String key) {
        
        Object value = null;
        ExpiringCacheEntry entry = (ExpiringCacheEntry) super.get(key);
        
        if (entry != null) {
            
            value = entry.getValue(clock.getAsLong());
            
            // if the value is null then that means this entry expired
            if (value == null) {
                log.debug("EXPIRED [{}]", key);
                hits--;
                super.remove(key);
            }
        }
        
        return value;
    }
    
}
