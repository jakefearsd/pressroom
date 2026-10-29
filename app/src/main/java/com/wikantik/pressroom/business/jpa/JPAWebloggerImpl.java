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
package com.wikantik.pressroom.business.jpa;

import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.business.ApiTokenManager;
import com.wikantik.pressroom.business.EventManager;
import com.wikantik.pressroom.business.FileContentManager;
import com.wikantik.pressroom.business.FormSubmissionManager;
import com.wikantik.pressroom.business.MediaFileManager;
import com.wikantik.pressroom.business.PropertiesManager;
import com.wikantik.pressroom.business.URLStrategy;
import com.wikantik.pressroom.business.UserManager;
import com.wikantik.pressroom.business.UserTokenManager;
import com.wikantik.pressroom.business.EntryRenderer;
import com.wikantik.pressroom.business.VirtualHostRegistry;
import com.wikantik.pressroom.business.WeblogEntryManager;
import com.wikantik.pressroom.business.WeblogManager;
import com.wikantik.pressroom.business.WeblogPageManager;
import com.wikantik.pressroom.business.WeblogRedirectManager;
import com.wikantik.pressroom.business.BusinessManager;
import com.wikantik.pressroom.business.CommentManager;
import com.wikantik.pressroom.business.WebloggerImpl;
import com.wikantik.pressroom.business.plugins.PluginManager;
import com.wikantik.pressroom.business.runnable.ThreadManager;
import com.wikantik.pressroom.business.search.IndexManager;
import com.wikantik.pressroom.business.themes.ThemeManager;

/**
 * A JPA specific implementation of the Weblogger business layer.
 */
public class JPAWebloggerImpl extends WebloggerImpl {

    // a persistence utility class
    private final JPAPersistenceStrategy strategy;
    
    
    /**
     * Single constructor.
     * @throws com.wikantik.pressroom.WebloggerException on any error
     */
    public JPAWebloggerImpl(
        JPAPersistenceStrategy strategy,
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
        EntryRenderer        entryRenderer) throws WebloggerException {

        super(
            indexManager,
            mediaFileManager,
            fileContentManager,
            weblogPageManager,
            weblogRedirectManager,
            businessManager,
            commentManager,
            eventManager,
            formSubmissionManager,
            userTokenManager,
            apiTokenManager,
            pluginManager,
            propertiesManager,
            themeManager,
            threadManager,
            userManager,
            weblogManager,
            weblogEntryManager,
            urlStrategy,
            virtualHostRegistry,
            entryRenderer);
        
        this.strategy = strategy;
    }
    
    
    @Override
    public void flush() throws WebloggerException {
        this.strategy.flush();
    }

    
    @Override
    public void release() {
        super.release();
        // tell JPA to close down
        this.strategy.release();
    }


    @Override
    public void shutdown() {
        // do our own shutdown first
        this.release();

        // then let parent do its thing
        super.shutdown();

        this.strategy.shutdown();
    }

}
