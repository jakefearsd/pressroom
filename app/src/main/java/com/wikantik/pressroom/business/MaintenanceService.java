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


import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.util.cache.CacheManager;

/**
 * The three per-weblog Maintenance operations (flush the page cache, rebuild
 * the search index, regenerate media renditions), extracted from {@code
 * MaintenanceController} so the REST API ({@code AdminActionsApi}) can be a
 * second caller of this logic rather than a second implementation of it.
 *
 * <p>Each method's body is the JSP controller's original try-block content,
 * moved verbatim -- same manager calls, same order, same {@code
 * WebloggerException} contract. The one thing added here that the controller
 * did not have is the null-weblog guard: the JSP controller can never reach
 * these bodies with a null weblog ({@code resolveWeblog} returns null and the
 * handler returns the error page before ever calling in), but a manager call
 * against a null weblog has no per-weblog target at all -- it would either
 * NPE or, worse, silently act globally -- so this seam refuses it outright
 * rather than trusting every future caller to check first.
 */
public class MaintenanceService {

    private final Weblogger weblogger;

    public MaintenanceService(Weblogger weblogger) {
        this.weblogger = weblogger;
    }

    /** Bumps {@code lastModified}, saves, flushes, and invalidates the page cache. */
    public void flushCache(Weblog weblog) throws WebloggerException {
        requireWeblog(weblog);
        // saveWeblog moves lastModified forward itself
        weblogger.getWeblogManager().saveWeblog(weblog);
        weblogger.flush();
        CacheManager.invalidate(weblog);
    }

    /** Rebuilds the weblog's Lucene search index. */
    public void rebuildIndex(Weblog weblog) throws WebloggerException {
        requireWeblog(weblog);
        weblogger.getIndexManager().rebuildWeblogIndex(weblog);
    }

    /**
     * Regenerates the media rendition ladder for every media file in the
     * weblog. Returns the count of files regenerated, which the JSP
     * controller substitutes into its confirmation message.
     */
    public int regenerateRenditions(Weblog weblog) throws WebloggerException {
        requireWeblog(weblog);
        return weblogger.getMediaFileManager().regenerateRenditions(weblog);
    }

    private static void requireWeblog(Weblog weblog) {
        if (weblog == null) {
            throw new IllegalArgumentException("weblog is required");
        }
    }
}
