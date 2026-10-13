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

package org.apache.roller.weblogger.ui.controllers.core;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.sql.Connection;
import java.util.List;

import jakarta.servlet.http.HttpServletRequest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.roller.weblogger.WebloggerException;
import org.apache.roller.weblogger.business.WebloggerProvider;
import org.apache.roller.weblogger.business.startup.StartupException;
import org.apache.roller.weblogger.business.startup.WebloggerStartup;
import org.apache.roller.weblogger.config.WebloggerConfig;
import org.apache.roller.weblogger.ui.controllers.BaseController;
import org.springframework.beans.FatalBeanException;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;


/**
 * Walk user through install process.
 */
@Controller
@RequestMapping("/roller-ui/install")
public class InstallController extends BaseController {

    private static final Logger log = LoggerFactory.getLogger(InstallController.class);

    private final WebloggerProvider webloggerProvider;

    public InstallController(WebloggerProvider webloggerProvider) {
        this.webloggerProvider = webloggerProvider;
    }

    @Override
    public boolean isUserRequired() {
        return false;
    }

    @Override
    public boolean isWeblogRequired() {
        return false;
    }

    @GetMapping("/install.rol")
    public String execute(HttpServletRequest request, Model model) {
        // populateCommonModel skipped - runs before bootstrap

        if (webloggerProvider.isBootstrapped()) {
            return "redirect:/";
        }

        if (WebloggerStartup.getDatabaseProviderException() != null) {
            StartupException se = WebloggerStartup.getDatabaseProviderException();
            Throwable rootCause;
            if (se.getCause() != null) {
                rootCause = se.getCause();
            } else {
                rootCause = se;
            }
            model.addAttribute("rootCauseException", rootCause);
            model.addAttribute("rootCauseStackTrace", getStackTrace(rootCause));
            model.addAttribute("messages", se.getStartupLog());
            model.addAttribute("pageTitle", "installer.error.connection.pageTitle");

            log.debug("Forwarding to database error page");
            return ".DatabaseError";
        }

        if (WebloggerStartup.isDatabaseCreationRequired()) {
            log.debug("Forwarding to database table creation page");
            model.addAttribute("pageTitle", "installer.database.creation.pageTitle");
            model.addAttribute("databaseProductName", getDatabaseProductName());
            return ".CreateDatabase";
        }

        if (WebloggerStartup.isDatabaseUpgradeRequired()) {
            log.debug("Forwarding to database table upgrade page");
            model.addAttribute("pageTitle", "installer.database.upgrade.pageTitle");
            model.addAttribute("databaseProductName", getDatabaseProductName());
            model.addAttribute("upgradeRequired", true);
            return ".UpgradeDatabase";
        }

        model.addAttribute("pageTitle", "installer.error.unknown.pageTitle");
        Throwable rootCause = new Exception("UNKNOWN ERROR");
        rootCause.fillInStackTrace();
        model.addAttribute("rootCauseException", rootCause);
        model.addAttribute("rootCauseStackTrace", getStackTrace(rootCause));
        return ".Bootstrap";
    }

    @PostMapping("/install!create.rol")
    public String create(HttpServletRequest request, Model model) {
        // populateCommonModel skipped - runs before bootstrap

        if (webloggerProvider.isBootstrapped()) {
            return "redirect:/";
        }

        try {
            List<String> messages = WebloggerStartup.createDatabase();
            model.addAttribute("messages", messages);
            model.addAttribute("success", true);
        } catch (StartupException se) {
            model.addAttribute("error", true);
            model.addAttribute("messages", se.getStartupLog());
        }

        model.addAttribute("pageTitle", "installer.database.creation.pageTitle");
        model.addAttribute("databaseProductName", getDatabaseProductName());
        return ".CreateDatabase";
    }

    @PostMapping("/install!upgrade.rol")
    public String upgrade(HttpServletRequest request, Model model) {
        // populateCommonModel skipped - runs before bootstrap

        if (webloggerProvider.isBootstrapped()) {
            return "redirect:/";
        }

        try {
            List<String> messages = WebloggerStartup.upgradeDatabase(true);
            model.addAttribute("messages", messages);
            model.addAttribute("success", true);
        } catch (StartupException se) {
            model.addAttribute("error", true);
            model.addAttribute("messages", se.getStartupLog());
        }

        model.addAttribute("pageTitle", "installer.database.upgrade.pageTitle");
        model.addAttribute("databaseProductName", getDatabaseProductName());
        return ".UpgradeDatabase";
    }

    @RequestMapping(value = "/install!bootstrap.rol", method = {RequestMethod.GET, RequestMethod.POST})
    public String bootstrap(HttpServletRequest request, Model model) {
        // populateCommonModel skipped - runs before bootstrap

        log.info("ENTERING");

        if (webloggerProvider.isBootstrapped()) {
            log.info("EXITING - already bootstrapped, forwarding to Roller");
            return "redirect:/";
        }

        try {
            // trigger bootstrapping: the provider bean builds the tier from the
            // root web application context (which already imports
            // WebloggerBeanConfig) so controllers and the business tier share
            // a single Spring context, then initializes it
            webloggerProvider.bootstrap();

            log.info("EXITING - Bootstrap successful, forwarding to Roller");
            return "redirect:/";

        } catch (FatalBeanException ex) {
            log.error("FatalBeanException", ex);
            model.addAttribute("rootCauseException", ex);
            model.addAttribute("rootCauseStackTrace", getStackTrace(ex));
        } catch (WebloggerException ex) {
            log.error("WebloggerException", ex);
            model.addAttribute("rootCauseException", ex);
            model.addAttribute("rootCauseStackTrace", getStackTrace(ex));
        } catch (Exception e) {
            log.error("Exception", e);
            model.addAttribute("rootCauseException", e);
            model.addAttribute("rootCauseStackTrace", getStackTrace(e));
        }

        log.info("EXITING - Bootstrap failed, forwarding to error page");
        model.addAttribute("pageTitle", "installer.error.unknown.pageTitle");
        return ".Bootstrap";
    }

    private String getDatabaseProductName() {
        String name = "unknown";
        Connection con = null;
        try {
            con = WebloggerStartup.getDatabaseProvider().getConnection();
            name = con.getMetaData().getDatabaseProductName();
        } catch (Exception ignored) {
            // This is a best-effort probe for a diagnostics page shown before
            // the database is confirmed reachable; if the driver won't even
            // answer getDatabaseProductName(), "unknown" is a fine fallback
            // for that page and the real failure is reported elsewhere in
            // the install flow.
        } finally {
            if (con != null) {
                try {
                    con.close();
                } catch (Exception ex) {
                    log.debug("Could not close probe connection to database", ex);
                }
            }
        }
        return name;
    }

    @Override
    protected String getProp(String key) {
        // Static config only, we don't have database yet
        String value = WebloggerConfig.getProperty(key);
        return (value == null) ? key : value;
    }

    private String getStackTrace(Throwable t) {
        if (t == null) {
            return "";
        }
        StringWriter sw = new StringWriter();
        t.printStackTrace(new PrintWriter(sw));
        return sw.toString().trim();
    }
}
