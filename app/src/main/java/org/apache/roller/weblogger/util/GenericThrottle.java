/*
 * Copyright 2004 Sun Microsystems, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package org.apache.roller.weblogger.util;

import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.roller.weblogger.util.cache.Cache;
import org.apache.roller.weblogger.util.cache.CacheManager;
import org.apache.roller.weblogger.util.cache.ExpiringCacheEntry;


/**
 * A tool used to provide throttling support.
 *
 * The basic idea is that if the # of hits from a client within a certain
 * interval of time is greater than the threshold value then the client is
 * considered to be abusive.
 */
public class GenericThrottle {
    
    private static final Logger log = LoggerFactory.getLogger(GenericThrottle.class);
    
    // threshold and interval to determine who is abusive
    private int threshold = 1;
    private int interval = 0;
    
    // a cache to maintain the data
    Cache clientHistoryCache;
    
    
    public GenericThrottle(int thresh, int inter, int maxEntries) {
        
        // threshold can't be negative, that would mean everyone is abusive
        if(thresh > -1) {
            this.threshold = thresh;
        }
        
        // interval must be a positive value
        if(inter > 0) {
            this.interval = inter;
        }
        
        // The cache is a bounded LRU, so the oldest clients are forgotten once it fills: a throttle is a
        // brake on bursts, not an audit of everyone who ever called. A bad size is repaired to the nearest
        // working value rather than rejected.
        // max entries must be a positive value
        if(maxEntries < 0) {
            maxEntries = 1;
        }
        
        // cache props
        Map<String,String> cacheProps = new HashMap<>();
        cacheProps.put("id", "throttle");
        cacheProps.put("size", String.valueOf(maxEntries));
        cacheProps.put("timeout", String.valueOf(this.interval));
        
        // get cache instance.  handler is null cuz we don't want to register it
        this.clientHistoryCache = CacheManager.constructCache(null, cacheProps);
    }
    
    
    /**
     * Process a new hit from the client.
     *
     * Each call to this method increments the hit count for the client and
     * then returns a boolean value indicating if the hit has pushed the client
     * over the threshold.
     *
     * @return true if client is abusive, false otherwise
     */
    public boolean processHit(String clientId) {
        
        if(clientId == null) {
            return false;
        }
        
        // see if we have any info about this client yet
        ClientInfo client = null;
        ExpiringCacheEntry cacheEntry = (ExpiringCacheEntry) this.clientHistoryCache.get(clientId);
        if(cacheEntry != null) {
            log.debug("HIT {}", clientId);
            client = (ClientInfo) cacheEntry.getValue();

            // this means entry had expired
            if(client == null) {
                log.debug("EXPIRED {}", clientId);
                this.clientHistoryCache.remove(clientId);
            }
        }

        // if we already know this client then update their hit count and
        // see if they have surpassed the threshold
        if(client != null) {
            client.hits++;

            log.debug("STATUS {} - {} hits since {}", clientId, client.hits, client.start);

            // abusive client
            if(client.hits > this.threshold) {
                return true;
            }

        } else {
            log.debug("NEW {}", clientId);
            
            // first timer
            ClientInfo newClient = new ClientInfo();
            newClient.hits = 1;

            ExpiringCacheEntry newEntry = new ExpiringCacheEntry(newClient, this.interval);
            this.clientHistoryCache.put(clientId, newEntry);
        }
        
        return false;
    }

    /**
     * Check the current status of a client.
     *
     * A client is considered abusive if the number of hits from the client
     * within the configured interval is greater than the set threshold.
     *
     * @return true if client is abusive, false otherwise.
     */
    public boolean isAbusive(String clientId) {
        
        if(clientId == null) {
            return false;
        }
        
        // see if we have any info about this client
        ClientInfo client = null;
        ExpiringCacheEntry cacheEntry = (ExpiringCacheEntry) this.clientHistoryCache.get(clientId);
        if(cacheEntry != null) {
            log.debug("HIT {}", clientId);
            client = (ClientInfo) cacheEntry.getValue();

            // this means entry had expired
            if (client == null) {
                log.debug("EXPIRED {}", clientId);
                this.clientHistoryCache.remove(clientId);
            }
        }

        return client != null && client.hits > this.threshold;
    }
    
    
    // just something to keep a few properties in
    private final class ClientInfo {
        
        public int hits = 0;
        public java.util.Date start = new java.util.Date();
        
    }
    
}
