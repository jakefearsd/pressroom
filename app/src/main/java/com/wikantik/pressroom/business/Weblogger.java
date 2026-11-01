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

package com.wikantik.pressroom.business;

import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.business.discovery.DiscoveryManager;
import com.wikantik.pressroom.business.plugins.PluginManager;
import com.wikantik.pressroom.business.runnable.ThreadManager;
import com.wikantik.pressroom.business.search.IndexManager;
import com.wikantik.pressroom.business.themes.ThemeManager;


/**
 * 
 * The main entry point interface of the Weblogger business tier.
 */
public interface Weblogger {

    /**
     * 
     * Get UserManager associated with this Weblogger instance.
     */
    UserManager getUserManager();
    
    
    /**
     * 
     * Get WeblogManager associated with this Weblogger instance.
     */
    WeblogManager getWeblogManager();
    
    /**
     * 
     * Get WeblogManager associated with this Weblogger instance.
     */
    WeblogEntryManager getWeblogEntryManager();
    
    
    /**
     * 
     * Get PropertiesManager associated with this Weblogger instance.
     */
    PropertiesManager getPropertiesManager();
    
    
    /**
     * Get ThreadManager associated with this Weblogger instance.
     */
    ThreadManager getThreadManager();
    
    
    /**
     * Get IndexManager associated with this Weblogger instance.
     */
    IndexManager getIndexManager();
    
    
    /**
     * Get ThemeManager associated with this Weblogger instance.
     */
    ThemeManager getThemeManager();
    
    
    /**
     * Get PluginManager associated with this Weblogger instance.
     */
    PluginManager getPluginManager();
    
    /**
     * Get MediaFileManager associated with this Weblogger instance.
     */
    MediaFileManager getMediaFileManager();
    
    /**
     * Get FileContentManager associated with this Weblogger instance.
     */
    FileContentManager getFileContentManager();

    /**
     * Get the WeblogPageManager, which handles static pages.
     */
    WeblogPageManager getWeblogPageManager();

    /**
     * Get the WeblogRedirectManager, which answers URLs that would otherwise
     * 404 with 301s to live content.
     */
    WeblogRedirectManager getWeblogRedirectManager();

    /**
     * Get the BusinessManager, which stores the shared business records.
     */
    BusinessManager getBusinessManager();

    /**
     * Get the CommentManager, which stores comments by signed-in readers.
     */
    CommentManager getCommentManager();

    /**
     * Get the EventManager, which records first-party outcome events.
     */
    EventManager getEventManager();

    /**
     * Get the FormSubmissionManager, which records contact-form inquiries.
     */
    FormSubmissionManager getFormSubmissionManager();

    /**
     * Get the UserTokenManager, which handles single-use, expiring account
     * tokens (forgot-password, admin set-password links).
     */
    UserTokenManager getUserTokenManager();

    /**
     * Get the ApiTokenManager, which handles long-lived, revocable
     * credentials for the automation API.
     */
    ApiTokenManager getApiTokenManager();

    /**
     * Get the URLStrategy used to build all urls in the system.
     */
    URLStrategy getUrlStrategy();

    /**
     * The hostname-to-weblog-handle map (custom domains), built with the rest
     * of the tier so it resolves from the first request.
     */
    VirtualHostRegistry getVirtualHostRegistry();

    /**
     * The content pipeline (entry plugins, shortcodes, markdown, sanitization)
     * as a service; what the template wrappers render entry and page text
     * through.
     */
    EntryRenderer getEntryRenderer();

    /**
     * The discovery picker behind the front page and each blog's "more from
     * the site" block.
     */
    DiscoveryManager getDiscoveryManager();
    
    /**
     * Flush object states.
     */
    void flush() throws WebloggerException;
    
    
    /**
     * Release all resources associated with Weblogger session.
     */
    void release();
    
    
    /**
     * Initialize any resources necessary for this instance of Weblogger.
     */
    void initialize() throws InitializationException;
    
    
    /**
     * Release all resources necessary for this instance of Weblogger.
     */
    void shutdown();
    
    
    /**
     * Weblogger version
     */
    String getVersion();
    
    /**
     * Weblogger source code management revision
     */
    String getRevision();
    
    /**
     * Weblogger build time
     */
    String getBuildTime();
        
    /**
     * Get username that built Weblogger
     */
    String getBuildUser();

}
