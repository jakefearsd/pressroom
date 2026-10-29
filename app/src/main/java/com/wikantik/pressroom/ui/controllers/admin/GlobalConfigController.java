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

import java.util.*;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

import jakarta.servlet.http.HttpServletRequest;

import org.apache.commons.text.StringEscapeUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.business.PageReference;
import com.wikantik.pressroom.business.PropertiesManager;
import com.wikantik.pressroom.business.WeblogManager;
import com.wikantik.pressroom.config.WebloggerRuntimeConfig;
import com.wikantik.pressroom.config.runtime.ConfigDef;
import com.wikantik.pressroom.config.runtime.PropertyDef;
import com.wikantik.pressroom.config.runtime.RuntimeConfigDefs;
import com.wikantik.pressroom.pojos.GlobalPermission;
import com.wikantik.pressroom.pojos.RuntimeConfigProperty;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.pojos.WeblogPage;
import com.wikantik.pressroom.ui.controllers.BaseController;
import com.wikantik.pressroom.util.cache.CacheManager;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;


/**
 * Action which handles editing of global configuration.
 */
@Controller
@RequestMapping("/pressroom-ui/admin")
public class GlobalConfigController extends BaseController {

    private static final Logger log = LoggerFactory.getLogger(GlobalConfigController.class);

    private static final String FRONTPAGE_WEBLOG_HANDLE = "site.frontpage.weblog.handle";
    /** The front door's site page; the front-door select writes it (see {@link #applyFrontDoorChoice}). */
    private static final String FRONTPAGE_PAGE = "site.frontpage.page";
    private static final String PRIVACY_PAGE = "site.privacy.page";
    private static final String ABSOLUTE_URL = "site.absoluteurl";
    /** The front-door select's value prefix for a site page; a blog's option is its bare handle. */
    private static final String PAGE_CHOICE = "page:";
    /**
     * The settings a rendered page's footer link, header or the front door
     * depend on: a change to any of them must expire every cached page.
     */
    private static final List<String> RENDERED_PAGE_INPUTS =
            List.of(PRIVACY_PAGE, FRONTPAGE_WEBLOG_HANDLE, FRONTPAGE_PAGE, ABSOLUTE_URL,
                    "site.name", "site.shortName", "site.description");

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
        List<WeblogPage> sitePages = loadSitePages();
        model.addAttribute("sitePages", sitePages);
        model.addAttribute("staleFrontDoorPage", staleFrontDoorPage(properties, sitePages));

        // Validate everything first and mutate nothing until it all passes.
        // The properties are managed entities, and an in-place edit is written
        // by whichever commit comes next (JPAPropertiesManagerImpl), so a
        // refused form that had set values as it went would still change the
        // site. Only properties that are already defined are processed.
        Map<String, String> accepted = new LinkedHashMap<>();
        for (Map.Entry<String, RuntimeConfigProperty> propEntry : properties.entrySet()) {
            String propName = propEntry.getKey();
            String incomingProp = request.getParameter(propEntry.getValue().getName());

            PropertyDef propertyDef = globalConfigDef.getPropertyDef(propName);
            if (propertyDef == null) {
                // we're only processing defined properties, i.e. ones shown in the UI
                continue;
            }

            if ("boolean".equals(propertyDef.getType())) {
                // an unticked checkbox submits nothing, so absent means false
                accepted.put(propName, Boolean.toString(Boolean.parseBoolean(incomingProp)));

            } else if (incomingProp != null && "integer".equals(propertyDef.getType())) {
                try {
                    Integer.parseInt(incomingProp);
                } catch (NumberFormatException nfe) {
                    String propDesc = bundle.getString(propertyDef.getKey());
                    addError(model, "ConfigForm.invalidIntegerProperty", new Object[]{propDesc, StringEscapeUtils.escapeHtml4(incomingProp)}, request);
                }
                accepted.put(propName, incomingProp);

            } else if (incomingProp != null && "float".equals(propertyDef.getType())) {
                try {
                    Float.parseFloat(incomingProp);
                } catch (NumberFormatException nfe) {
                    String propDesc = bundle.getString(propertyDef.getKey());
                    addError(model, "ConfigForm.invalidFloatProperty", new Object[]{propDesc, StringEscapeUtils.escapeHtml4(incomingProp)}, request);
                }
                accepted.put(propName, incomingProp);

            } else if (incomingProp != null) {
                accepted.put(propName, incomingProp.trim());

            } else if (!FRONTPAGE_WEBLOG_HANDLE.equals(propName) && !FRONTPAGE_PAGE.equals(propName)) {
                // Missing from the form. The front-page weblog is exempt: it is
                // a content choice, so absent it is left as it is rather than
                // blocking every other setting. So is the front-page page,
                // which is derived from that select, never submitted itself.
                addError(model, "ConfigForm.invalidProperty", propName, request);
            }
        }

        applyFrontDoorChoice(accepted, properties, model, request);

        // Only a change to the reference is validated, so a privacy page that
        // was later unpublished never blocks an unrelated save: Global Config
        // never blocks.
        if (changed(PRIVACY_PAGE, accepted, properties)) {
            validatePrivacyPage(accepted, model, request);
        }

        if (hasErrors(model)) {
            // Re-render what the admin typed, on detached copies: the managed
            // entities stay exactly as stored.
            Map<String, RuntimeConfigProperty> shown = new LinkedHashMap<>();
            for (Map.Entry<String, RuntimeConfigProperty> propEntry : properties.entrySet()) {
                String propName = propEntry.getKey();
                shown.put(propName, new RuntimeConfigProperty(propName,
                        accepted.getOrDefault(propName, propEntry.getValue().getValue())));
            }
            model.addAttribute("properties", shown);
            return ".GlobalConfig";
        }

        boolean renderedPageInputChanged = RENDERED_PAGE_INPUTS.stream()
                .anyMatch(name -> changed(name, accepted, properties));
        for (Map.Entry<String, String> change : accepted.entrySet()) {
            properties.get(change.getKey()).setValue(change.getValue());
            log.debug("Set {} = {}", change.getKey(), change.getValue());
        }

        try {
            // save 'em and flush
            PropertiesManager mgr = weblogger.getPropertiesManager();
            mgr.saveProperties(properties);
            weblogger.flush();

            // Rendered pages expire only through weblog.lastModified, and a
            // Global Config save does not move it, so a changed privacy link
            // would stay out of every cached page. Touch every weblog, and
            // clear the site-wide cache, which ignores lastModified.
            if (renderedPageInputChanged) {
                touchEveryWeblog();
            }

            // notify user of our success
            addMessage(model, "generic.changes.saved", request);

        } catch (WebloggerException ex) {
            log.error("Error saving pressroom properties", ex);
            addError(model, "generic.error.check.logs", request);
        }

        return ".GlobalConfig";
    }

    /**
     * The front-door select carries one of three things under the blog
     * setting's name: "" (the site directory), a blog handle, or
     * {@code page:<slug>} (a site page). The page setting is derived from it,
     * so exactly one of the two is ever set. Both a draft page and the site
     * space itself are refused here, not merely left out of the select: the
     * form can be posted by hand. A refused choice leaves both as stored.
     */
    @SuppressFBWarnings(
            value = "IMPROPER_UNICODE",
            justification = "Refuses the site space's handle in any case. Handles are ASCII "
                    + "alphanumerics, so a miss could only fail to refuse here; the lookups "
                    + "behind it refuse the site space anyway.")
    private void applyFrontDoorChoice(Map<String, String> accepted,
            Map<String, RuntimeConfigProperty> properties, Model model, HttpServletRequest request) {
        String choice = accepted.get(FRONTPAGE_WEBLOG_HANDLE);
        if (choice == null) {
            return;
        }
        String handle = choice;
        String slug = "";
        boolean refused = false;
        if (choice.startsWith(PAGE_CHOICE)) {
            handle = "";
            slug = choice.substring(PAGE_CHOICE.length()).trim();
            RuntimeConfigProperty stored = properties.get(FRONTPAGE_PAGE);
            boolean unchanged = stored != null && !slug.isEmpty()
                    && slug.equals(Objects.toString(stored.getValue(), ""));
            if (!unchanged && !isPublishedSitePage(slug)) {
                addError(model, "configForm.error.frontpagePage.notFound", request);
                refused = true;
            }
        } else if (Weblog.SITE_SPACE_HANDLE.equalsIgnoreCase(choice)) {
            addError(model, "ConfigForm.invalidProperty", FRONTPAGE_WEBLOG_HANDLE, request);
            refused = true;
        }
        if (refused) {
            accepted.remove(FRONTPAGE_WEBLOG_HANDLE);
            return;
        }
        accepted.put(FRONTPAGE_WEBLOG_HANDLE, handle);
        if (properties.containsKey(FRONTPAGE_PAGE)) {
            accepted.put(FRONTPAGE_PAGE, slug);
        }
    }

    /**
     * The stored front-door slug when it is not among the published pages (the
     * page was unpublished or renamed), else "". The select offers it as its
     * own selected option: with none selected the browser submits "" and an
     * unrelated save would silently clear the front door.
     */
    private static String staleFrontDoorPage(Map<String, RuntimeConfigProperty> properties,
            List<WeblogPage> sitePages) {
        RuntimeConfigProperty stored = properties.get(FRONTPAGE_PAGE);
        String slug = stored == null ? "" : Objects.toString(stored.getValue(), "").trim();
        return slug.isEmpty() || sitePages.stream().anyMatch(page -> slug.equals(page.getSlug()))
                ? "" : slug;
    }

    /** A decision path: a lookup that could not run is not a page that exists. */
    private boolean isPublishedSitePage(String slug) {
        if (slug.isEmpty()) {
            return false;
        }
        try {
            return new PageReference(null, slug).resolvePublished(weblogger).isPresent();
        } catch (WebloggerException ex) {
            log.error("Error checking the front door's site page", ex);
            return false;
        }
    }

    /**
     * The privacy setting must be a {@link PageReference} to a published page:
     * {@code slug} on the site, or {@code handle/slug} on a blog. A decision
     * path: a lookup that could not run is an error, not a pass.
     */
    private void validatePrivacyPage(Map<String, String> accepted, Model model, HttpServletRequest request) {
        String value = accepted.get(PRIVACY_PAGE);
        if (value == null || value.isEmpty()) {
            return;
        }
        if (PageReference.parse(value).filter(this::publishedOrFalse).isEmpty()) {
            addError(model, "configForm.error.privacyPage.notFound", request);
        }
    }

    private boolean publishedOrFalse(PageReference ref) {
        try {
            return ref.resolvePublished(weblogger).isPresent();
        } catch (WebloggerException ex) {
            log.error("Error checking the privacy page", ex);
            return false;
        }
    }

    /** True when the form submitted this property and it differs from the stored value. */
    private static boolean changed(String name, Map<String, String> accepted,
            Map<String, RuntimeConfigProperty> properties) {
        RuntimeConfigProperty stored = properties.get(name);
        return stored != null && accepted.containsKey(name)
                && !Objects.toString(stored.getValue(), "").equals(accepted.get(name));
    }

    private void touchEveryWeblog() throws WebloggerException {
        WeblogManager mgr = weblogger.getWeblogManager();
        List<Weblog> touched = new ArrayList<>();
        // The site space is no weblog to the listing, yet its pages carry the
        // same footer and are the front door, so it is touched explicitly.
        List<Weblog> everything = new ArrayList<>(mgr.getWeblogs(null, null, null, null, 0, -1));
        Weblog site = mgr.getSiteSpace();
        if (site != null) {
            everything.add(site);
        }
        for (Weblog weblog : everything) {
            weblog.touchLastModified();
            mgr.saveWeblog(weblog);
            touched.add(weblog);
        }
        weblogger.flush();
        // SiteWideCache ignores lastModified and is dropped only here; one
        // call clears it wholesale.
        for (Weblog weblog : touched) {
            CacheManager.invalidate(weblog);
        }
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
