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

package org.apache.roller.weblogger.ui.controllers.admin;

import java.util.*;

import jakarta.servlet.http.HttpServletRequest;

import org.apache.commons.text.StringEscapeUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.roller.weblogger.WebloggerException;
import org.apache.roller.weblogger.business.PropertiesManager;
import org.apache.roller.weblogger.business.WeblogManager;
import org.apache.roller.weblogger.config.WebloggerRuntimeConfig;
import org.apache.roller.weblogger.config.runtime.ConfigDef;
import org.apache.roller.weblogger.config.runtime.PropertyDef;
import org.apache.roller.weblogger.config.runtime.RuntimeConfigDefs;
import org.apache.roller.weblogger.pojos.GlobalPermission;
import org.apache.roller.weblogger.pojos.RuntimeConfigProperty;
import org.apache.roller.weblogger.pojos.Weblog;
import org.apache.roller.weblogger.ui.controllers.BaseController;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;


/**
 * Action which handles editing of global configuration.
 */
@Controller
@RequestMapping("/roller-ui/admin")
public class GlobalConfigController extends BaseController {

    private static final Logger log = LoggerFactory.getLogger(GlobalConfigController.class);

    private static final String FRONTPAGE_WEBLOG_HANDLE = "site.frontpage.weblog.handle";

    private final ResourceBundle bundle = ResourceBundle.getBundle("ApplicationResources");

    @Override
    public boolean isWeblogRequired() {
        return false;
    }

    @Override
    public List<String> requiredGlobalPermissionActions() {
        return List.of(GlobalPermission.ADMIN);
    }

    @Override
    public String getPageTitle() {
        return "configForm.title";
    }

    @Override
    public String getDesiredMenu() {
        return "admin";
    }

    @Override
    public String getActionName() {
        return "globalConfig";
    }

    /**
     * Display global properties editor form.
     */
    @GetMapping("/globalConfig.rol")
    public String execute(HttpServletRequest request, Model model) {
        populateCommonModel(request, model);

        Map<String, RuntimeConfigProperty> properties = loadProperties();
        ConfigDef globalConfigDef = loadConfigDef();
        Collection<Weblog> weblogs = loadWeblogs();

        model.addAttribute("properties", properties);
        model.addAttribute("globalConfigDef", globalConfigDef);
        model.addAttribute("weblogs", weblogs);

        return ".GlobalConfig";
    }

    /**
     * Save global properties.
     */
    @PostMapping("/globalConfig!save.rol")
    public String save(HttpServletRequest request, Model model) {
        populateCommonModel(request, model);

        Map<String, RuntimeConfigProperty> properties = loadProperties();
        ConfigDef globalConfigDef = loadConfigDef();
        Collection<Weblog> weblogs = loadWeblogs();

        model.addAttribute("properties", properties);
        model.addAttribute("globalConfigDef", globalConfigDef);
        model.addAttribute("weblogs", weblogs);

        // only set values for properties that are already defined
        RuntimeConfigProperty updProp;
        String incomingProp;
        for (Map.Entry<String, RuntimeConfigProperty> propEntry : properties.entrySet()) {
            String propName = propEntry.getKey();
            updProp = propEntry.getValue();
            incomingProp = request.getParameter(updProp.getName());

            PropertyDef propertyDef = globalConfigDef.getPropertyDef(propName);
            if (propertyDef == null) {
                // we're only processing defined properties, i.e. ones shown in the UI
                continue;
            }

            if ("boolean".equals(propertyDef.getType())) {
                try {
                    if (incomingProp == null) {
                        updProp.setValue("false");
                    } else {
                        boolean value = Boolean.parseBoolean(incomingProp);
                        updProp.setValue(Boolean.toString(value));
                    }
                    log.debug("Set boolean {} = {}", propName, incomingProp);
                } catch (Exception nfe) {
                    String propDesc = bundle.getString(propertyDef.getKey());
                    addError(model, "ConfigForm.invalidBooleanProperty", new Object[]{propDesc, StringEscapeUtils.escapeHtml4(incomingProp)}, request);
                }

            } else if (incomingProp != null && "integer".equals(propertyDef.getType())) {
                try {
                    Integer.parseInt(incomingProp);
                    updProp.setValue(incomingProp);
                    log.debug("Set integer {} = {}", propName, incomingProp);
                } catch (NumberFormatException nfe) {
                    String propDesc = bundle.getString(propertyDef.getKey());
                    addError(model, "ConfigForm.invalidIntegerProperty", new Object[]{propDesc, StringEscapeUtils.escapeHtml4(incomingProp)}, request);
                }

            } else if (incomingProp != null && "float".equals(propertyDef.getType())) {
                try {
                    Float.parseFloat(incomingProp);
                    updProp.setValue(incomingProp);
                    log.debug("Set float {} = {}", propName, incomingProp);
                } catch (NumberFormatException nfe) {
                    String propDesc = bundle.getString(propertyDef.getKey());
                    addError(model, "ConfigForm.invalidFloatProperty", new Object[]{propDesc, StringEscapeUtils.escapeHtml4(incomingProp)}, request);
                }

            } else if (incomingProp != null) {
                updProp.setValue(incomingProp.trim());
                log.debug("Set something {} = {}", propName, incomingProp);

            } else if (!FRONTPAGE_WEBLOG_HANDLE.equals(propName)) {
                // Missing from the form. The front-page weblog is exempt: it is
                // a content choice, so absent it is left as it is rather than
                // blocking every other setting.
                addError(model, "ConfigForm.invalidProperty", propName, request);
            }
        }

        if (hasErrors(model)) {
            return ".GlobalConfig";
        }

        try {
            // save 'em and flush
            PropertiesManager mgr = weblogger.getPropertiesManager();
            mgr.saveProperties(properties);
            weblogger.flush();

            // notify user of our success
            addMessage(model, "generic.changes.saved", request);

        } catch (WebloggerException ex) {
            log.error("Error saving roller properties", ex);
            addError(model, "generic.error.check.logs", request);
        }

        return ".GlobalConfig";
    }

    private Map<String, RuntimeConfigProperty> loadProperties() {
        Map<String, RuntimeConfigProperty> properties = Collections.emptyMap();
        try {
            PropertiesManager mgr = weblogger.getPropertiesManager();
            properties = mgr.getProperties();
        } catch (WebloggerException ex) {
            log.error("Error getting runtime properties map", ex);
        }
        return properties;
    }

    private ConfigDef loadConfigDef() {
        RuntimeConfigDefs defs = WebloggerRuntimeConfig.getRuntimeConfigDefs();
        List<ConfigDef> configDefs = defs.getConfigDefs();
        for (ConfigDef configDef : configDefs) {
            if ("global-properties".equals(configDef.getName())) {
                return configDef;
            }
        }
        return null;
    }

    private Collection<Weblog> loadWeblogs() {
        try {
            WeblogManager mgr = weblogger.getWeblogManager();
            return mgr.getWeblogs(true, null, null, null, 0, -1);
        } catch (WebloggerException ex) {
            log.error("Error getting weblogs", ex);
            return Collections.emptyList();
        }
    }

}
