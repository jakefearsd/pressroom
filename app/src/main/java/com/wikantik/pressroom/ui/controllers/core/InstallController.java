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

package com.wikantik.pressroom.ui.controllers.core;

import java.sql.Connection;
import java.util.List;

import jakarta.servlet.http.HttpServletRequest;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.wikantik.pressroom.business.WebloggerProvider;
import com.wikantik.pressroom.business.startup.StartupException;
import com.wikantik.pressroom.business.startup.WebloggerStartup;
import com.wikantik.pressroom.config.WebloggerConfig;
import com.wikantik.pressroom.ui.controllers.BaseController;
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
@RequestMapping("/pressroom-ui/install")
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

        // This page is anonymous, and with installation.type=auto every public
        // request reaches it while the database is down, so it names the
        // problem and nothing else: the startup log (the JDBC URL and user),
        // the exception and its trace were logged at ERROR where startup
        // failed (DatabaseProvider, PressroomLifecycle) and stay there.
        if (WebloggerStartup.getDatabaseProviderException() != null) {
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

        // Prepared but not running: PressroomLifecycle logged why the bootstrap
        // failed at startup, or install!bootstrap.rol starts it from here.
        // Nothing has gone wrong yet, so the page offers the start button.
        model.addAttribute("pageTitle", "installer.start.pageTitle");
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
    @SuppressFBWarnings(
            value = "SPRING_CSRF_UNRESTRICTED_REQUEST_MAPPING",
            justification = "The installer is anonymous by design (/pressroom-ui/install/** falls through to "
                    + "permitAll in SecurityConfig) and bootstraps only while no tier exists, when there "
                    + "is no signed-in user whose authority a forged request could borrow; afterwards it "
                    + "only redirects. CreateDatabase.jsp links here with a GET.")
    public String bootstrap(HttpServletRequest request, Model model) {
        // populateCommonModel skipped - runs before bootstrap

        if (webloggerProvider.isBootstrapped()) {
            return "redirect:/";
        }

        try {
            // trigger bootstrapping: the provider bean builds the tier from the
            // root web application context (which already imports
            // WebloggerBeanConfig) so controllers and the business tier share
            // a single Spring context, then initializes it
            webloggerProvider.bootstrap();

            log.info("Roller started from the installer");
            return "redirect:/";

        } catch (Exception ex) {
            // Logged here, the point of failure, and only here: the page is
            // anonymous, so it says where to look, not what went wrong.
            log.error("Roller could not be bootstrapped from the installer", ex);
        }

        model.addAttribute("pageTitle", "installer.start.pageTitle");
        model.addAttribute("startFailed", true);
        return ".Bootstrap";
    }

    private String getDatabaseProductName() {
        String name = "unknown";
        try (Connection con = WebloggerStartup.getDatabaseProvider().getConnection()) {
            name = con.getMetaData().getDatabaseProductName();
        } catch (Exception ignored) {
            // This is a best-effort probe for a diagnostics page shown before
            // the database is confirmed reachable; if the driver won't even
            // answer getDatabaseProductName(), "unknown" is a fine fallback
            // for that page and the real failure is reported elsewhere in
            // the install flow.
        }
        return name;
    }

    @Override
    protected String getProp(String key) {
        // Static config only, we don't have database yet
        String value = WebloggerConfig.getProperty(key);
        return (value == null) ? key : value;
    }

}
