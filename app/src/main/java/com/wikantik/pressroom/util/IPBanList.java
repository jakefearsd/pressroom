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

package com.wikantik.pressroom.util;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.wikantik.pressroom.config.WebloggerConfig;


/**
 * Represents a list of banned ip addresses.
 *
 * This base implementation gets its list from a file on the filesystem.  We
 * are also aware of when the file changes via some outside source and we will
 * automatically re-read the file and update the list when that happens.
 */
public final class IPBanList {

    private static final Logger log = LoggerFactory.getLogger(IPBanList.class);

    // set of ips that are banned, use a set to ensure uniqueness
    private volatile Set<String> bannedIps = newThreadSafeSet();

    // file listing the ips that are banned
    private ModifiedFile bannedIpsFile = null;

    // reference to our singleton instance
    private static IPBanList instance;


    static {
        instance = new IPBanList(() -> WebloggerConfig.getProperty("ipbanlist.file"));
    }


    // private -- construction with an explicit supplier is for the static
    // initializer above and, via newForTest, for unit tests
    @SuppressFBWarnings(
            value = "PATH_TRAVERSAL_IN",
            justification = "The path is ipbanlist.file, operator configuration (the supplier in the "
                    + "static initializer), or a test's own file; no request reaches it.")
    private IPBanList(Supplier<String> banIpsFilePathSupplier) {

        log.debug("INIT");

        // load up set of denied ips
        String banIpsFilePath = banIpsFilePathSupplier.get();
        if(banIpsFilePath != null) {
            ModifiedFile banIpsFile = new ModifiedFile(banIpsFilePath);

            if(banIpsFile.exists() && banIpsFile.canRead()) {
                this.bannedIpsFile = banIpsFile;
                this.loadBannedIps();
            }
        }
    }


    // access to the singleton instance
    public static IPBanList getInstance() {
        return instance;
    }


    /**
     * Package private for unit tests: builds an instance from a supplier of
     * their own, bypassing the singleton and its {@code WebloggerConfig} read.
     */
    static IPBanList newForTest(Supplier<String> banIpsFilePathSupplier) {
        return new IPBanList(banIpsFilePathSupplier);
    }


    public boolean isBanned(String ip) {

        // update the banned ips list if needed
        this.loadBannedIpsIfNeeded();

        return ip != null && this.bannedIps.contains(ip);
    }


    public void addBannedIp(String ip) {

        if(ip == null) {
            return;
        }

        // update the banned ips list if needed
        this.loadBannedIpsIfNeeded();

        if(!this.bannedIps.contains(ip) &&
                bannedIpsFile != null && bannedIpsFile.canWrite()) {

            try {
                synchronized(this) {
                    // add to file
                    try (PrintWriter out = new PrintWriter(
                            new FileWriter(this.bannedIpsFile, StandardCharsets.UTF_8, true))) {
                        out.println(ip);
                    }
                    this.bannedIpsFile.clearChanged();

                    // add to Set
                    this.bannedIps.add(ip);
                }

                log.debug("ADDED {}", ip);
            } catch(Exception e) {
                log.error("Error adding banned ip to file", e);
            }
        }
    }


    /**
     * Check if the banned ips file has changed and needs to be reloaded.
     */
    private void loadBannedIpsIfNeeded() {

        if(bannedIpsFile != null &&
                bannedIpsFile.hasChanged()) {

            // need to reload
            this.loadBannedIps();
        }
    }


    /**
     * Load the list of banned ips from a file.  This clears the old list and
     * loads exactly what is in the file.
     */
    private synchronized void loadBannedIps() {

        if(bannedIpsFile != null) {

            // TODO: optimize this
            try (BufferedReader in = new BufferedReader(
                    new FileReader(this.bannedIpsFile, StandardCharsets.UTF_8))) {
                Set<String> newBannedIpList = newThreadSafeSet();

                String ip;
                while((ip = in.readLine()) != null) {
                    newBannedIpList.add(ip);
                }

                // list updated, reset modified file
                this.bannedIps = newBannedIpList;
                this.bannedIpsFile.clearChanged();

                log.info("{} banned ips loaded", this.bannedIps.size());
            } catch(Exception ex) {
               log.error("Error loading banned ips from file", ex);
            }

        }
    }


    // a simple extension to the File class which tracks if the file has
    // changed since the last time we checked
    @SuppressFBWarnings(
            value = "EQ_DOESNT_OVERRIDE_EQUALS",
            justification = "myLastModified is bookkeeping this private, single-use-site class "
                    + "layers on top of File, not part of any identity comparison -- this class is "
                    + "never compared with .equals() and never used as a Set/Map key anywhere "
                    + "(grepped), so inheriting File's pathname-based equals is harmless.")
    private static class ModifiedFile extends java.io.File {

        private static final long serialVersionUID = 1L;

        private long myLastModified;

        @SuppressFBWarnings(
                value = "PATH_TRAVERSAL_IN",
                justification = "Constructed only by IPBanList with ipbanlist.file, operator "
                        + "configuration.")
        public ModifiedFile(String filePath) {
            super(filePath);

            this.myLastModified = lastModified();
        }

        public boolean hasChanged() {
            return lastModified() != myLastModified;
        }

        public void clearChanged() {
            myLastModified = lastModified();
        }
    }

    private static <T> Set<T> newThreadSafeSet() {
        return ConcurrentHashMap.newKeySet();
    }
}
