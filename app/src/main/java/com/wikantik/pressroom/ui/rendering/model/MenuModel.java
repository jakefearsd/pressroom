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

package com.wikantik.pressroom.ui.rendering.model;

import java.util.Collections;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.business.UserManager;
import com.wikantik.pressroom.business.Weblogger;
import com.wikantik.pressroom.pojos.GlobalPermission;
import com.wikantik.pressroom.ui.core.util.menu.Menu;
import com.wikantik.pressroom.ui.core.util.menu.MenuHelper;
import com.wikantik.pressroom.ui.rendering.util.WeblogPageRequest;
import com.wikantik.pressroom.ui.rendering.util.WeblogRequest;


/**
 * Model which provides methods for displaying editor menu/navigation-bar.
 * 
 * Implemented by calling hybrid JSP tag.
 */
public class MenuModel implements Model {
    
    private static final Logger log = LoggerFactory.getLogger(MenuModel.class);
    
    private WeblogPageRequest pageRequest = null;

    /** The business-tier facade, handed in through {@code initData} (plan Task 10). */
    private Weblogger weblogger = null;


    /** Template context name to be used for model */
    @Override
    public String getModelName() {
        return "menuModel";
    }


    /** Init page model based on request */
    @Override
    public void init(Map<String, Object> initData) throws WebloggerException {

        // we expect the init data to contain a weblogRequest object
        WeblogRequest weblogRequest = (WeblogRequest) initData.get("parsedRequest");
        if(weblogRequest == null) {
            throw new WebloggerException("expected weblogRequest from init data");
        }

        // MenuModel only works on page requests, so cast weblogRequest
        // into a WeblogPageRequest and if it fails then throw exception
        if(weblogRequest instanceof WeblogPageRequest) {
            this.pageRequest = (WeblogPageRequest) weblogRequest;
        } else {
            throw new WebloggerException("weblogRequest is not a WeblogPageRequest."+
                    "  MenuModel only supports page requests.");
        }

        weblogger = ModelLoader.requireWeblogger(initData);
    }
    
    
    /**
     * Get a Menu representing the admin UI action menu, if the user is 
     * currently logged in and is an admin.
     */
    public Menu getAdminMenu() {
        try {
            GlobalPermission adminPerm =
                new GlobalPermission(Collections.singletonList(GlobalPermission.ADMIN));
            UserManager userManager = weblogger.getUserManager();
            boolean hasAdmin = userManager.checkPermission(adminPerm, pageRequest.getUser());
            if (pageRequest.isLoggedIn() && hasAdmin) {
                return MenuHelper.getMenu("admin", "noAction", pageRequest.getUser(),
                        pageRequest.getWeblog(), userManager);
            }
        } catch (WebloggerException ex) {
            log.error("ERROR: fetching user roles", ex);
        }
        return null;
    }


    /**
     * Get a Menu representing the author UI action menu, if the use is
     * currently logged in.
     */
    public Menu getAuthorMenu() {
        if(pageRequest.isLoggedIn()) {
            return MenuHelper.getMenu("editor", null, pageRequest.getUser(), pageRequest.getWeblog(),
                    weblogger.getUserManager());
        }
        return null;
    }
    
}
