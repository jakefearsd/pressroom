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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.business.discovery.DiscoveryManager;
import com.wikantik.pressroom.business.plugins.PluginManager;
import com.wikantik.pressroom.business.runnable.ThreadManager;
import com.wikantik.pressroom.business.search.IndexManager;
import com.wikantik.pressroom.business.themes.ThemeManager;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;


/**
 * The abstract version of the Weblogger implementation.
 * 
 * Here we put code that pertains to *all* implementations of the Weblogger
 * interface, regardless of their persistence strategy.
 */
public abstract class WebloggerImpl implements Weblogger {
    
    private static final Logger log = LoggerFactory.getLogger(WebloggerImpl.class);
    
    // managers
    private final IndexManager         indexManager;
    private final MediaFileManager     mediaFileManager;
    private final FileContentManager   fileContentManager;
    private final WeblogPageManager    weblogPageManager;
    private final WeblogRedirectManager weblogRedirectManager;
    private final BusinessManager     businessManager;
    private final CommentManager      commentManager;
    private final EventManager         eventManager;
    private final FormSubmissionManager formSubmissionManager;
    private final UserTokenManager     userTokenManager;
    private final ApiTokenManager      apiTokenManager;
    private final PluginManager        pluginManager;
    private final PropertiesManager    propertiesManager;
    private final ThemeManager         themeManager;
    private final ThreadManager        threadManager;
    private final UserManager          userManager;
    private final WeblogManager        weblogManager;
    private final WeblogEntryManager   weblogEntryManager;

    // url strategy
    private final URLStrategy          urlStrategy;
    private final VirtualHostRegistry  virtualHostRegistry;
    private final EntryRenderer        entryRenderer;
    private final DiscoveryManager     discoveryManager;
    
    // some simple attributes
    private final String version;
    private final String revision;
    private final String buildTime;
    private final String buildUser;
    
    
    protected WebloggerImpl(
        IndexManager         indexManager,
        MediaFileManager     mediaFileManager,
        FileContentManager   fileContentManager,
        WeblogPageManager    weblogPageManager,
        WeblogRedirectManager weblogRedirectManager,
        BusinessManager      businessManager,
        CommentManager       commentManager,
        EventManager         eventManager,
        FormSubmissionManager formSubmissionManager,
        UserTokenManager     userTokenManager,
        ApiTokenManager      apiTokenManager,
        PluginManager        pluginManager,
        PropertiesManager    propertiesManager,
        ThemeManager         themeManager,
        ThreadManager        threadManager,
        UserManager          userManager,
        WeblogManager        weblogManager,
        WeblogEntryManager   weblogEntryManager,
        URLStrategy          urlStrategy,
        VirtualHostRegistry  virtualHostRegistry,
        EntryRenderer        entryRenderer,
        DiscoveryManager     discoveryManager) throws WebloggerException {

        this.indexManager        = indexManager;
        this.mediaFileManager    = mediaFileManager;
        this.fileContentManager  = fileContentManager;
        this.weblogPageManager   = weblogPageManager;
        this.weblogRedirectManager = weblogRedirectManager;
        this.businessManager     = businessManager;
        this.commentManager      = commentManager;
        this.eventManager        = eventManager;
        this.formSubmissionManager = formSubmissionManager;
        this.userTokenManager    = userTokenManager;
        this.apiTokenManager     = apiTokenManager;
        this.pluginManager       = pluginManager;
        this.propertiesManager   = propertiesManager;
        this.themeManager        = themeManager;
        this.threadManager       = threadManager;
        this.userManager         = userManager;
        this.weblogManager       = weblogManager;
        this.weblogEntryManager  = weblogEntryManager;
        this.urlStrategy         = urlStrategy;
        this.virtualHostRegistry = virtualHostRegistry;
        this.entryRenderer       = entryRenderer;
        this.discoveryManager    = discoveryManager;

        Properties props = new Properties();
        // WebloggerImpl.class, not getClass(): this class is abstract and
        // JPAWebloggerImpl extends it, so getClass() would resolve via the
        // subclass's Class object (UI_INHERITANCE_UNSAFE_GETRESOURCE). The
        // resource itself belongs to this class either way.
        try (InputStream in = WebloggerImpl.class.getResourceAsStream("/pressroom-version.properties")) {
            props.load(in);
        } catch (IOException e) {
            log.error("pressroom-version.properties not found", e);
        }
        
        version = props.getProperty("ro.version", "UNKNOWN");
        revision = props.getProperty("ro.revision", "UNKNOWN");
        buildTime = props.getProperty("ro.buildTime", "UNKNOWN");
        buildUser = props.getProperty("ro.buildUser", "UNKNOWN");
    }
    
    
    /**
     * 
     * 
     * @see com.wikantik.pressroom.business.Weblogger#getThreadManager()
     */
    @Override
    public ThreadManager getThreadManager() {
        return threadManager;
    }
    
    
    /**
     * 
     * 
     * @see com.wikantik.pressroom.business.Weblogger#getIndexManager()
     */
    @Override
    public IndexManager getIndexManager() {
        return indexManager;
    }
    
    
    /**
     * 
     * 
     * @see com.wikantik.pressroom.business.Weblogger#getThemeManager()
     */
    @Override
    public ThemeManager getThemeManager() {
        return themeManager;
    }
    
    
    /**
     * 
     * 
     * @see com.wikantik.pressroom.business.Weblogger#getUserManager()
     */
    @Override
    public UserManager getUserManager() {
        return userManager;
    }
    
    
    /**
     *
     *
     * @see com.wikantik.pressroom.business.Weblogger#getMediaFileManager()
     */
    @Override
    public MediaFileManager getMediaFileManager() {
        return mediaFileManager;
    }
    
    /**
     *
     *
     * @see com.wikantik.pressroom.business.Weblogger#getFileContentManager()
     */
    @Override
    public FileContentManager getFileContentManager() {
        return fileContentManager;
    }


    /**
     *
     *
     * @see com.wikantik.pressroom.business.Weblogger#getWeblogPageManager()
     */
    @Override
    public WeblogPageManager getWeblogPageManager() {
        return weblogPageManager;
    }


    /**
     *
     *
     * @see com.wikantik.pressroom.business.Weblogger#getWeblogRedirectManager()
     */
    @Override
    public WeblogRedirectManager getWeblogRedirectManager() {
        return weblogRedirectManager;
    }

    @Override
    public BusinessManager getBusinessManager() {
        return businessManager;
    }

    @Override
    public CommentManager getCommentManager() {
        return commentManager;
    }


    /**
     *
     *
     * @see com.wikantik.pressroom.business.Weblogger#getEventManager()
     */
    @Override
    public EventManager getEventManager() {
        return eventManager;
    }


    /**
     *
     *
     * @see com.wikantik.pressroom.business.Weblogger#getFormSubmissionManager()
     */
    @Override
    public FormSubmissionManager getFormSubmissionManager() {
        return formSubmissionManager;
    }


    /**
     *
     *
     * @see com.wikantik.pressroom.business.Weblogger#getUserTokenManager()
     */
    @Override
    public UserTokenManager getUserTokenManager() {
        return userTokenManager;
    }


    /**
     *
     *
     * @see com.wikantik.pressroom.business.Weblogger#getApiTokenManager()
     */
    @Override
    public ApiTokenManager getApiTokenManager() {
        return apiTokenManager;
    }


    /**
     *
     *
     * @see com.wikantik.pressroom.business.Weblogger#getWeblogEntryManager()
     */
    @Override
    public WeblogEntryManager getWeblogEntryManager() {
        return weblogEntryManager;
    }
    
    
    /**
     * 
     * 
     * @see com.wikantik.pressroom.business.Weblogger#getWeblogManager()
     */
    @Override
    public WeblogManager getWeblogManager() {
        return weblogManager;
    }
    

    /**
     * 
     * 
     * @see com.wikantik.pressroom.business.Weblogger#getPropertiesManager()
     */
    @Override
    public PropertiesManager getPropertiesManager() {
        return propertiesManager;
    }
    
    
    /**
     *
     *
     * @see com.wikantik.pressroom.business.Weblogger#getPluginManager()
     */
    @Override
    public PluginManager getPluginManager() {
        return pluginManager;
    }


    /**
     * @inheritDoc
     */
    @Override
    public URLStrategy getUrlStrategy() {
        return urlStrategy;
    }

    @Override
    public VirtualHostRegistry getVirtualHostRegistry() {
        return virtualHostRegistry;
    }

    @Override
    public EntryRenderer getEntryRenderer() {
        return entryRenderer;
    }

    @Override
    public DiscoveryManager getDiscoveryManager() {
        return discoveryManager;
    }
	
    /**
     * @inheritDoc
     */
    @Override
    public void release() {
        try {
            mediaFileManager.release();
            fileContentManager.release();
            pluginManager.release();
            threadManager.release();
            userManager.release();
            weblogManager.release();
        } catch(Exception e) {
            log.error("Error calling Weblogger.release()", e);
        }
    }
    
    
    /**
     * @inheritDoc
     */
    @Override
    public void initialize() throws InitializationException {
        
        log.info("Initializing Pressroom Weblogger business tier");
        
        // TODO: this should probably be done in a more uniform fashion, possibly
        // using annotations?  biggest issue is controlling ordering
        getPropertiesManager().initialize();
        getThemeManager().initialize();
        getThreadManager().initialize();
        getIndexManager().initialize();
        getMediaFileManager().initialize();

        // we always need to do a flush after initialization because it's
        // possible that some changes need to be persisted
        try {
            flush();
        } catch(WebloggerException ex) {
            throw new InitializationException("Error flushing after initialization", ex);
        } 
        
        log.info("Pressroom Weblogger business tier successfully initialized");
    }
    
    
    /**
     * @inheritDoc
     */
    @Override
    public void shutdown() {
        try {
            if (indexManager != null) {
                indexManager.shutdown();
            }
            if (threadManager != null) {
                threadManager.shutdown();
            }
        } catch(Exception e) {
            log.error("Error calling Weblogger.shutdown()", e);
        }
    }
    
    
    /**
     * Weblogger version
     */
    @Override
    public String getVersion() {
        return version;
    }
    
    /**
     * Get source code repository revision # used to create build
     */
    @Override
    public String getRevision() {
        return revision;
    }
        
    /**
     * Weblogger build time
     */
    @Override
    public String getBuildTime() {
        return buildTime;
    }
    
    
    /**
     * Get username that built Weblogger
     */
    @Override
    public String getBuildUser() {
        return buildUser;
    }
    
}
