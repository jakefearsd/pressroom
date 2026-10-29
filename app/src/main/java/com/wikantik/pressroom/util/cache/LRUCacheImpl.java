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

import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.LongSupplier;
import com.wikantik.pressroom.util.PressroomConstants;


/**
 * A simple LRU Cache.
 */
public class LRUCacheImpl implements Cache {

    private final String id;
    private final Map<String, Object> cache;

    /**
     * Where "now" comes from. Always the system clock in production; tests in
     * this package substitute a clock they can wind forward, so that anything
     * time dependent can be tested at its exact boundary rather than by
     * sleeping and hoping.
     */
    protected final LongSupplier clock;

    // for metrics
    protected double hits = 0;
    protected double misses = 0;
    protected double puts = 0;
    protected double removes = 0;
    protected Date startTime;


    protected LRUCacheImpl(String id) {

        this(id, 100);
    }


    protected LRUCacheImpl(String id, int maxsize) {
        this(id, maxsize, System::currentTimeMillis);
    }


    LRUCacheImpl(String id, int maxsize, LongSupplier clock) {

        this.id = id;
        this.cache = new LRULinkedHashMap<>(maxsize);
        this.clock = clock;
        this.startTime = new Date(clock.getAsLong());
    }

    
    @Override
    public String getId() {
        return this.id;
    }
    
    
    /**
     * Store an entry in the cache.
     */
    @Override
    public synchronized void put(String key, Object value) {
        
        this.cache.put(key, value);
        puts++;
    }
    
    
    /**
     * Retrieve an entry from the cache.
     */
    @Override
    public synchronized Object get(String key) {
        
        Object obj = this.cache.get(key);
        
        // for metrics
        if(obj == null) {
            misses++;
        } else {
            hits++;
        }
        
        return obj;
    }
    
    
    @Override
    public synchronized void remove(String key) {
        
        this.cache.remove(key);
        removes++;
    }
    
    
    @Override
    public synchronized void clear() {
        
        this.cache.clear();
        
        // clear metrics
        hits = 0;
        misses = 0;
        puts = 0;
        removes = 0;
        startTime = new Date(clock.getAsLong());
    }
    
    
    /**
     * synchronized to match put/get/remove/clear above: startTime, hits,
     * misses, puts and removes are the same fields those methods mutate
     * under this monitor, and the efficiency calculation below reads three
     * of them together -- an unsynchronized read here (IS2_INCONSISTENT_SYNC)
     * could both see a stale value AND tear the hits/misses/removes snapshot
     * across a concurrent clear().
     */
    @Override
    public synchronized Map<String, Object> getStats() {

        Map<String, Object> stats = new HashMap<>();
        stats.put("startTime", this.startTime);
        stats.put("hits", this.hits);
        stats.put("misses", this.misses);
        stats.put("puts", this.puts);
        stats.put("removes", this.removes);
        
        // calculate efficiency
        if((misses - removes) > 0) {
            double efficiency = hits / (misses + hits);
            stats.put("efficiency", efficiency * PressroomConstants.PERCENT_100);
        }
        
        return stats;
    }
    
    
    // David Flanaghan: http://www.davidflanagan.com/blog/000014.html
    private static class LRULinkedHashMap<K, V> extends LinkedHashMap<K, V> {
        private static final long serialVersionUID = 1L;

        protected int maxsize;
        
        public LRULinkedHashMap(int maxsize) {
            super(maxsize * 4 / 3 + 1, 0.75f, true);
            this.maxsize = maxsize;
        }
        
        @Override
        protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
            return this.size() > this.maxsize;
        }
    }
    
}
