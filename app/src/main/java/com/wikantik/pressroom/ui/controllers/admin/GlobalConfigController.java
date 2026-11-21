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

package com.wikantik.pressroom.ui.controllers.admin;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.ResourceBundle;

import jakarta.servlet.http.HttpServletRequest;

import org.apache.commons.text.StringEscapeUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.business.discovery.DiscoverySettings;
import com.wikantik.pressroom.business.PropertiesManager;
import com.wikantik.pressroom.business.WeblogManager;
import com.wikantik.pressroom.config.WebloggerRuntimeConfig;
import com.wikantik.pressroom.config.runtime.ConfigDef;
import com.wikantik.pressroom.config.runtime.PropertyDef;
import com.wikantik.pressroom.config.runtime.PropertyType;
import com.wikantik.pressroom.config.runtime.RuntimeConfigDefs;
import com.wikantik.pressroom.pojos.GlobalPermission;
import com.wikantik.pressroom.pojos.RuntimeConfigProperty;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.pojos.WeblogPage;
import com.wikantik.pressroom.ui.controllers.BaseController;
import com.wikantik.pressroom.ui.controllers.RuleViolation;
import com.wikantik.pressroom.ui.controllers.SiteSettingsEffects;
import com.wikantik.pressroom.ui.controllers.SiteSettingsRules;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import com.wikantik.pressroom.business.RevisionSweep;


/**
 * Action which handles editing of global configuration.
 */
@Controller
@RequestMapping("/pressroom-ui/admin")
public class GlobalConfigController extends BaseController {

    private static final Logger log = LoggerFactory.getLogger(GlobalConfigController.class);

    private static final String REFRESH_MINUTES_ATTR = "discoveryRefreshMinutes";
    private static final int MINUTES_MIN =
            DiscoverySettings.min(DiscoverySettings.REFRESH_SECONDS) / DiscoverySettings.SECONDS_PER_MINUTE;
    private static final int MINUTES_MAX =
            DiscoverySettings.max(DiscoverySettings.REFRESH_SECONDS) / DiscoverySettings.SECONDS_PER_MINUTE;
    /** The front-door select's value prefix for a site page; a blog's option is its bare handle. */
    private static final String PAGE_CHOICE = "page:";

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
        List<WeblogPage> sitePages = loadSitePages();
        model.addAttribute("sitePages", sitePages);
        model.addAttribute("staleFrontDoorPage", staleFrontDoorPage(properties, sitePages));
        showRefreshMinutes(model, storedValue(properties));

        return ".GlobalConfig";
    }

    private static String storedValue(Map<String, RuntimeConfigProperty> properties) {
        RuntimeConfigProperty stored = properties.get(DiscoverySettings.REFRESH_SECONDS);
        return stored == null ? null : stored.getValue();
    }

    /** Shows the interval in minutes unless a typed (refused) value is already being echoed. */
    private static void showRefreshMinutes(Model model, String storedSeconds) {
        if (model.getAttribute(REFRESH_MINUTES_ATTR) == null) {
            model.addAttribute(REFRESH_MINUTES_ATTR, Integer.toString(DiscoverySettings.refreshMinutesFrom(storedSeconds)));
        }
    }

    /**
     * Reads the refresh interval from its minutes field and, when valid,
     * records it in seconds. A bad value adds an error and records nothing.
     */
    private void acceptRefreshMinutes(HttpServletRequest request, PropertyDef propertyDef,
            Map<String, String> accepted, Model model) {
        String minutes = request.getParameter(DiscoverySettings.REFRESH_MINUTES_FIELD);
        if (minutes == null) {
            return;
        }
        try {
            int parsed = Integer.parseInt(minutes.trim());
            if (parsed < MINUTES_MIN || parsed > MINUTES_MAX) {
                addRangeError(model, propertyDef, MINUTES_MIN, MINUTES_MAX, request);
            } else {
                accepted.put(DiscoverySettings.REFRESH_SECONDS, Integer.toString(parsed * DiscoverySettings.SECONDS_PER_MINUTE));
            }
        } catch (NumberFormatException nfe) {
            String propDesc = bundle.getString(propertyDef.getKey());
            addError(model, "ConfigForm.invalidIntegerProperty",
                    new Object[]{propDesc, StringEscapeUtils.escapeHtml4(minutes)}, request);
        }
        // Echo what was typed so a refused form shows it back as typed.
        model.addAttribute(REFRESH_MINUTES_ATTR, minutes);
    }

    private void addRangeError(Model model, PropertyDef propertyDef, int min, int max,
            HttpServletRequest request) {
        addError(model, DiscoverySettings.RANGE_ERROR_KEY,
                new Object[]{StringEscapeUtils.escapeHtml4(bundle.getString(propertyDef.getKey())),
                        Integer.toString(min), Integer.toString(max)},
                request);
    }

    /**
     * The settings that remove revisions. Saving one deletes nothing: the
     * daily sweep applies it (and, for a per-item count, that item's next
     * save). The save says what the next sweep will remove.
     */
    private static final List<String> REVISION_LIMITS = List.of("entry.revisions.retention",
            "page.revisions.retention", "revisions.maxAgeDays", "revisions.total.max");

    /**
     * After a revision limit changed: how many revisions the next sweep
     * will remove, when that is any at all. A courtesy on a save that has
     * already been stored, so a prediction that cannot be made is logged and
     * adds nothing.
     */
    private void sayWhatTheNextSweepRemoves(Model model, HttpServletRequest request) {
        try {
            RevisionSweep.Report next = weblogger.getWeblogEntryManager().sweepRevisions(true);
            if (next == null) {
                return;
            }
            if (next.incomplete()) {
                // Could not be worked out in full: never shown as "nothing".
                addMessage(model, "configForm.revisionsNextSweepUnknown", request);
            } else if (next.total() > 0) {
                addMessage(model, "configForm.revisionsNextSweep", String.valueOf(next.total()), request);
            }
        } catch (RuntimeException e) {
            log.warn("Could not predict what the next revision sweep removes", e);
        }
    }

    /**
     * Save global properties. The rules are {@link SiteSettingsRules}'s and
     * what a stored save does next is {@link SiteSettingsEffects}'s, shared
     * with {@code PATCH /api/v1/admin/config}; this method keeps the form's
     * own concerns: the minutes field, the unticked checkbox, the front-door
     * select's encoding, a field missing from the form, and showing a refused
     * form back as typed.
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
        List<WeblogPage> sitePages = loadSitePages();
        model.addAttribute("sitePages", sitePages);
        model.addAttribute("staleFrontDoorPage", staleFrontDoorPage(properties, sitePages));

        // Validate everything first and mutate nothing until it all passes.
        // The properties are managed entities, and an in-place edit is written
        // by whichever commit comes next (JPAPropertiesManagerImpl), so a
        // refused form that had set values as it went would still change the
        // site. Only properties that are already defined are processed.
        Map<String, String> before = values(properties);
        Map<String, String> accepted = new LinkedHashMap<>();
        for (Map.Entry<String, RuntimeConfigProperty> propEntry : properties.entrySet()) {
            String propName = propEntry.getKey();
            PropertyDef propertyDef = globalConfigDef.getPropertyDef(propName);
            // we're only processing defined properties, i.e. ones shown in the UI
            if (propertyDef != null) {
                accept(propName, propertyDef, request.getParameter(propEntry.getValue().getName()),
                        accepted, model, request);
            }
        }

        String typedPage = accepted.get(SiteSettingsRules.FRONT_DOOR_PAGE);
        boolean frontDoorRefused = decodeFrontDoorChoice(accepted, model, request);
        showRefreshMinutes(model, accepted.getOrDefault(DiscoverySettings.REFRESH_SECONDS, storedValue(properties)));

        // Only a changed reference is looked up, so a privacy page that was
        // later unpublished never blocks an unrelated save: Global Config
        // never blocks.
        for (RuleViolation violation : SiteSettingsRules.checkReferences(accepted, before,
                SiteSettingsRules.publishedPages(weblogger))) {
            addViolation(model, violation, request);
            frontDoorRefused |= SiteSettingsRules.FRONT_DOOR_BLOG.equals(violation.field())
                    || SiteSettingsRules.FRONT_DOOR_PAGE.equals(violation.field());
        }
        if (frontDoorRefused) {
            // A refused front-door choice leaves both settings as stored.
            accepted.remove(SiteSettingsRules.FRONT_DOOR_BLOG);
            accepted.remove(SiteSettingsRules.FRONT_DOOR_PAGE);
            if (typedPage != null) {
                accepted.put(SiteSettingsRules.FRONT_DOOR_PAGE, typedPage);
            }
        }

        if (hasErrors(model)) {
            // Re-render what the admin typed, on detached copies: the managed
            // entities stay exactly as stored.
            Map<String, RuntimeConfigProperty> shown = new LinkedHashMap<>();
            properties.forEach((propName, stored) -> shown.put(propName,
                    new RuntimeConfigProperty(propName, accepted.getOrDefault(propName, stored.getValue()))));
            model.addAttribute("properties", shown);
            return ".GlobalConfig";
        }

        boolean revisionLimitChanged = REVISION_LIMITS.stream()
                .anyMatch(name -> SiteSettingsRules.changed(name, accepted, before));
        for (Map.Entry<String, String> change : accepted.entrySet()) {
            RuntimeConfigProperty property = properties.get(change.getKey());
            // The front-door page is decoded from the select even when it has
            // no row to store it in (rows are seeded at startup).
            if (property != null) {
                property.setValue(change.getValue());
                log.debug("Set {} = {}", change.getKey(), change.getValue());
            }
        }

        try {
            // save 'em and flush
            PropertiesManager mgr = weblogger.getPropertiesManager();
            mgr.saveProperties(properties);
            weblogger.flush();

            SiteSettingsEffects.afterSave(weblogger, accepted, before);

            // notify user of our success
            addMessage(model, "generic.changes.saved", request);
            if (revisionLimitChanged) {
                sayWhatTheNextSweepRemoves(model, request);
            }

        } catch (WebloggerException ex) {
            log.error("Error saving pressroom properties", ex);
            addError(model, "generic.error.check.logs", request);
        }

        return ".GlobalConfig";
    }

    /** Name to stored value, for the rules (a stored null stays null). */
    private static Map<String, String> values(Map<String, RuntimeConfigProperty> properties) {
        Map<String, String> values = new LinkedHashMap<>();
        properties.forEach((name, property) -> values.put(name, property.getValue()));
        return values;
    }

    /**
     * Checks one submitted property and records the value to store in
     * {@code accepted}; a value that does not parse is still recorded, so
     * the form shows what was typed beside the error.
     */
    private void accept(String propName, PropertyDef propertyDef, String incomingProp,
            Map<String, String> accepted, Model model, HttpServletRequest request) {
        PropertyType propertyType = PropertyType.of(propertyDef.getType());
        if (DiscoverySettings.REFRESH_SECONDS.equals(propName)) {
            // Edited in minutes, stored in seconds: the form never posts
            // the seconds property itself. Absent leaves it as it is.
            acceptRefreshMinutes(request, propertyDef, accepted, model);

        } else if (propertyType == PropertyType.BOOLEAN) {
            // an unticked checkbox submits nothing, so absent means false
            accepted.put(propName, Boolean.toString(Boolean.parseBoolean(incomingProp)));

        } else if (incomingProp == null) {
            if (!SiteSettingsRules.FRONT_DOOR_BLOG.equals(propName)
                    && !SiteSettingsRules.FRONT_DOOR_PAGE.equals(propName)) {
                // Missing from the form. The front-page weblog is exempt: it is
                // a content choice, so absent it is left as it is rather than
                // blocking every other setting. So is the front-page page,
                // which is derived from that select, never submitted itself.
                addError(model, "ConfigForm.invalidProperty", propName, request);
            }

        } else {
            String value = SiteSettingsRules.normalise(propertyDef, incomingProp);
            SiteSettingsRules.checkValue(propertyDef, value)
                    .ifPresent(violation -> addViolation(model, violation, request));
            accepted.put(propName, value);
        }
    }

    /**
     * Shows a rule's message, every argument HTML-escaped: they are raw
     * values, the typed one among them, and {@code tiles/messages.jsp} does
     * not escape.
     */
    private void addViolation(Model model, RuleViolation violation, HttpServletRequest request) {
        if (violation.args().isEmpty()) {
            addError(model, violation.messageKey(), request);
        } else {
            addError(model, violation.messageKey(), violation.args().stream()
                    .map(arg -> StringEscapeUtils.escapeHtml4(String.valueOf(arg))).toArray(), request);
        }
    }

    /**
     * The front-door select carries one of three things under the blog
     * setting's name: "" (the site directory), a blog handle, or
     * {@code page:<slug>} (a site page). Decodes it into the two settings, so
     * exactly one is ever set; {@link SiteSettingsRules} then judges them
     * (the site space, a draft page: the form can be posted by hand). A
     * {@code page:} naming no page is refused here. True when refused.
     */
    private boolean decodeFrontDoorChoice(Map<String, String> accepted, Model model, HttpServletRequest request) {
        String choice = accepted.get(SiteSettingsRules.FRONT_DOOR_BLOG);
        if (choice == null) {
            return false;
        }
        boolean pageChoice = choice.startsWith(PAGE_CHOICE);
        String slug = pageChoice ? choice.substring(PAGE_CHOICE.length()).trim() : "";
        accepted.put(SiteSettingsRules.FRONT_DOOR_BLOG, pageChoice ? "" : choice);
        accepted.put(SiteSettingsRules.FRONT_DOOR_PAGE, slug);
        if (pageChoice && slug.isEmpty()) {
            addError(model, "configForm.error.frontpagePage.notFound", request);
            return true;
        }
        return false;
    }

    /**
     * The stored front-door slug when it is not among the published pages (the
     * page was unpublished or renamed), else "". The select offers it as its
     * own selected option: with none selected the browser submits "" and an
     * unrelated save would silently clear the front door.
     */
    private static String staleFrontDoorPage(Map<String, RuntimeConfigProperty> properties,
            List<WeblogPage> sitePages) {
        RuntimeConfigProperty stored = properties.get(SiteSettingsRules.FRONT_DOOR_PAGE);
        String slug = stored == null ? "" : Objects.toString(stored.getValue(), "").trim();
        return slug.isEmpty() || sitePages.stream().anyMatch(page -> slug.equals(page.getSlug()))
                ? "" : slug;
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
            // The listing already leaves the site space out; the picker must
            // never offer it, so say so here too.
            return mgr.getWeblogs(true, null, null, null, 0, -1).stream()
                    .filter(weblog -> !weblog.isSiteSpace()).toList();
        } catch (WebloggerException ex) {
            log.error("Error getting weblogs", ex);
            return Collections.emptyList();
        }
    }

    /** The site space's published pages, for the front-door select; none when there is no site space. */
    private List<WeblogPage> loadSitePages() {
        try {
            Weblog site = weblogger.getWeblogManager().getSiteSpace();
            return site == null ? Collections.emptyList()
                    : weblogger.getWeblogPageManager().getPublishedPages(site);
        } catch (WebloggerException ex) {
            log.error("Error getting the site pages", ex);
            return Collections.emptyList();
        }
    }

}
