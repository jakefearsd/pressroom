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

import java.io.Serializable;


/**
 * A cache entry that expires.
 *
 * We use this class to wrap objects being cached and associate a timestamp
 * and timeout period with them so we can know when they expire.
 */
public class ExpiringCacheEntry implements Serializable {

    private static final long serialVersionUID = 1L;

    private final transient Object value;
    private final long timeCached;
    private final long timeout;
    
    
    public ExpiringCacheEntry(Object value, long timeout) {
        this(value, timeout, System.currentTimeMillis());
    }


    /**
     * Cache an entry as if it had been created at the given time.
     *
     * Package private: this exists so that caches in this package (and their
     * tests) can decide for themselves what "now" means instead of being tied
     * to the system clock.  Expiry is the whole point of this class, and a test
     * that has to sleep to observe it is a test that will eventually lie.
     */
    ExpiringCacheEntry(Object value, long timeout, long timeCached) {
        this.value = value;
        this.timeout = Math.max(0, timeout);  // make sure that we don't support negative values
        this.timeCached = timeCached;
    }

    
    public long getTimeCached() {
        return this.timeCached;
    }
    
    
    public long getTimeout() {
        return this.timeout;
    }
    
    
    /**
     * Retrieve the value of this cache entry.
     *
     * If the value has expired then we return null.
     */
    public Object getValue() {
        return getValue(System.currentTimeMillis());
    }


    /**
     * Retrieve the value of this cache entry as of the given time.
     */
    Object getValue(long now) {
        if(this.hasExpired(now)) {
            return null;
        } else {
            return this.value;
        }
    }


    /**
     * Determine if this cache entry has expired.
     */
    public boolean hasExpired() {
        return hasExpired(System.currentTimeMillis());
    }


    /**
     * Determine if this cache entry has expired as of the given time.
     */
    boolean hasExpired(long now) {
        return (this.timeCached + this.timeout) < now;
    }
    
}
