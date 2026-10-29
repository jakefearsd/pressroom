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

package com.wikantik.pressroom.ui.core.util.menu;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.business.UserManager;
import com.wikantik.pressroom.config.WebloggerConfig;
import com.wikantik.pressroom.config.WebloggerRuntimeConfig;
import com.wikantik.pressroom.pojos.GlobalPermission;
import com.wikantik.pressroom.pojos.User;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.pojos.WeblogPermission;
import com.wikantik.pressroom.pojos.WeblogTheme;
import com.wikantik.pressroom.util.Utilities;
import org.jdom2.Document;
import org.jdom2.Element;
import org.jdom2.JDOMException;
import com.wikantik.pressroom.util.SafeXml;
import org.jdom2.input.SAXBuilder;

/**
 * A helper class for dealing with UI menus.
 * 
 * Note : Debug logging disabled here as it is too expensive time wise.
 * 
 */
public final class MenuHelper {

    private static final Logger log = LoggerFactory.getLogger(MenuHelper.class);

    private static Map<String, ParsedMenu> menus = new HashMap<>();

    private MenuHelper() {
    }

    static {
        try {

            // parse menus and cache so we can efficiently reuse them.
            // Named relative to the root package (WebloggerException's): no class lives in
            // ui.menu, and a name relative to MenuHelper would resolve under ui/core/util/menu
            // and silently return null.
            menus.put("editor", unmarshall(
                WebloggerException.class.getResourceAsStream("ui/menu/editor-menu.xml")));

            menus.put("admin", unmarshall(
                WebloggerException.class.getResourceAsStream("ui/menu/admin-menu.xml")));

        } catch (Exception ex) {
            log.error("Error parsing menu configs", ex);
        }
    }

    /**
     * Gets the menu.
     * 
     * @param menuId
     *            the menu id
     * @param currentAction
     *            the current action. Null to ignore.
     * @param user
     *            the user
     * @param weblog
     *            the weblog
     * @param userManager
     *            answers the permission checks that decide which entries the
     *            user may see; handed in by the caller rather than looked up
     *            here, so this helper has no dependency on the container
     *
     * @return the menu
     */
    public static Menu getMenu(String menuId, String currentAction, User user,
            Weblog weblog, UserManager userManager) {

        if (menuId == null) {
            return null;
        }

        Menu menu = null;

        // do we know the specified menu config?
        ParsedMenu menuConfig = menus.get(menuId);
        if (menuConfig != null) {
            try {
                menu = buildMenu(menuId, menuConfig, currentAction, user, weblog,
                        userManager, MenuHelper::getBooleanProperty);
            } catch (WebloggerException ex) {
                log.error("ERROR: fethcing user roles", ex);
            }
        }

        return menu;
    }

    /**
     * Returns the parsed configuration for a menu, or null if there is no such
     * menu. Package visible so tests can assert on what the XML parsed into.
     *
     * @param menuId
     *            the menu id
     *
     * @return the parsed menu, or null
     */
    static ParsedMenu getParsedMenu(String menuId) {
        return menus.get(menuId);
    }

    /**
     * Whether one menu element -- a tab or an item, they are gated identically
     * -- should appear for this user on this weblog.
     *
     * <p>Three rules, in order: an enabled/disabled property, then global
     * permissions, then weblog permissions. buildMenu used to apply all three
     * twice, once per level, which is most of why it reached cyclomatic
     * complexity 30.
     *
     * <p>A failed <em>global</em> permission lookup hides the element rather
     * than propagating: an admin menu that renders without a tab is better than
     * one that 500s, and the same store outage should not produce a different
     * outcome depending on which level of the menu asked. Before this was
     * shared, only the tab level did that -- the item level let the exception
     * out and took the whole page with it, which was an oversight rather than a
     * decision (see aFailedPermissionLookupHidesTheTab, which pins the tab
     * half, and its item counterpart added alongside this change). The weblog
     * permission lookup deliberately still propagates, exactly as it did at
     * both levels before.
     */
    private static boolean isVisible(MenuGated config, User user, Weblog weblog,
            UserManager umgr, Predicate<String> propertyEnabled)
            throws WebloggerException {

        if (config.getEnabledProperty() != null) {
            if (!propertyEnabled.test(config.getEnabledProperty())) {
                return false;
            }
        } else if (config.getDisabledProperty() != null
                && propertyEnabled.test(config.getDisabledProperty())) {
            return false;
        }

        List<String> globalActions = config.getGlobalPermissionActions();
        if (globalActions != null && !globalActions.isEmpty()) {
            try {
                if (!umgr.checkPermission(new GlobalPermission(globalActions), user)) {
                    return false;
                }
            } catch (WebloggerException ex) {
                log.error("ERROR: fetching user roles", ex);
                return false;
            }
        }

        List<String> weblogActions = config.getWeblogPermissionActions();
        return weblogActions == null || weblogActions.isEmpty()
                || umgr.checkPermission(new WeblogPermission(weblog, weblogActions), user);
    }


    /**
     * Builds the menu.
     *
     * <p>The user manager and the property lookup are parameters rather than
     * static calls so that the filtering rules -- which decide whether a user
     * sees a menu item at all -- can be exercised without a running business
     * tier. {@link #getMenu} supplies the real collaborators.
     *
     * @param menuId
     *            the menu id
     * @param menuConfig
     *            the menu config
     * @param currentAction
     *            the current action
     * @param user
     *            the user
     * @param weblog
     *            the weblog
     * @param umgr
     *            the user manager used for permission checks
     * @param propertyEnabled
     *            resolves an enabled/disabled property name to a boolean
     *
     * @return the menu
     *
     * @throws WebloggerException
     *             the weblogger exception
     */


    static Menu buildMenu(String menuId, ParsedMenu menuConfig,
            String currentAction, User user, Weblog weblog, UserManager umgr,
            Predicate<String> propertyEnabled)
            throws WebloggerException {

        // log.debug("creating menu for action - " + currentAction);

        Menu tabMenu = new Menu();


        // Hack - for blogger convenience, the design tab of the edit
        // menu defaults to the templates tab item (instead of theme edit)
        // if the weblog is using a custom theme.
        boolean customThemeOverride = "editor".equals(menuId)
                && WeblogTheme.CUSTOM.equals(weblog.getEditorTheme());

        // iterate over tabs from parsed config
        for (ParsedTab configTab : menuConfig.getTabs()) {

            // log.debug("config tab = " + configTab.getName());

            if (isVisible(configTab, user, weblog, umgr, propertyEnabled)) {

                // log.debug("tab allowed - " + configTab.getName());

                // all checks passed, tab should be included
                MenuTab tab = new MenuTab();
                tab.setKey(configTab.getName());

                // setup tab items
                boolean firstItem = true;
                boolean selectable = true;

                for (ParsedTabItem configTabItem : configTab.getTabItems()) {

                    if (offeredHere(weblog, configTabItem)
                            && isVisible(configTabItem, user, weblog, umgr, propertyEnabled)) {

                        // log.debug("tab item allowed - "
                        // + configTabItem.getName());

                        // all checks passed, item should be included
                        MenuTabItem tabItem = new MenuTabItem();
                        tabItem.setKey(configTabItem.getName());
                        tabItem.setAction(configTabItem.getAction());

                        // is this the selected item? Only one can be selected
                        // so skip the rest
                        if (currentAction != null && selectable
                                && isSelected(currentAction, configTabItem)) {
                            tabItem.setSelected(true);
                            tab.setSelected(true);
                            selectable = false;
                        }

                        // the url for the tab is the url of the first tab item
                        if (firstItem) {
                            if (customThemeOverride && "tabbedmenu.design".equals(tab.getKey())) {
                                tab.setAction("templates");
                            } else {
                                tab.setAction(tabItem.getAction());
                            }
                            firstItem = false;
                        }

                        // add the item
                        tab.addItem(tabItem);
                    }
                }

                // add the tab, unless the site space left it with nothing to offer
                if (!tab.getItems().isEmpty() || !isSiteSpace(weblog)) {
                    tabMenu.addTab(tab);
                }
            }
        }

        return tabMenu;
    }

    /**
     * The screens the site space offers -- the only ones
     * {@code PressroomHandlerInterceptor} lets act on it (each controller's
     * {@code allowsSiteSpace()}). The rail on the site space links these and
     * nothing else, since every other screen would refuse.
     */
    private static final Set<String> SITE_SPACE_ACTIONS = Set.of("pages", "mediaFileView", "themeEdit");

    private static boolean isSiteSpace(Weblog weblog) {
        return weblog != null && weblog.isSiteSpace();
    }

    /** Whether this item belongs on this weblog's rail: on the site space, only its three screens. */
    private static boolean offeredHere(Weblog weblog, ParsedTabItem item) {
        return !isSiteSpace(weblog) || SITE_SPACE_ACTIONS.contains(item.getAction());
    }

    /**
     * Check enabled property, prefers runtime properties.
     * 
     * @param propertyName
     *            the property name
     * 
     * @return the boolean property
     */
    private static boolean getBooleanProperty(String propertyName) {
        if (WebloggerRuntimeConfig.getProperty(propertyName) != null) {
            return WebloggerRuntimeConfig.getBooleanProperty(propertyName);
        }
        return WebloggerConfig.getBooleanProperty(propertyName);
    }

    /**
     * Checks if is selected.
     * 
     * @param currentAction
     *            the current action
     * @param tabItem
     *            the tab item
     * 
     * @return true, if is selected
     */
    private static boolean isSelected(String currentAction,
            ParsedTabItem tabItem) {

        if (currentAction.equals(tabItem.getAction())) {
            return true;
        }

        // an item is also considered selected if it's a subforward of the
        // current action
        Set<String> subActions = tabItem.getSubActions();

        return subActions != null && subActions.contains(currentAction);
    }

    /**
     * Unmarshall the given input stream into our defined set of Java objects.
     *
     * <p>Package visible so tests can parse a purpose-built menu definition
     * without going through the two menus cached at class load.
     *
     * @param instream
     *            the instream
     *
     * @return the parsed menu
     *
     * @throws IOException
     *             Signals that an I/O exception has occurred.
     * @throws JDOMException
     *             the jDOM exception
     */
    static ParsedMenu unmarshall(InputStream instream)
            throws IOException, JDOMException {

        if (instream == null) {
            throw new IOException("InputStream is null!");
        }

        ParsedMenu config = new ParsedMenu();

        SAXBuilder builder = SafeXml.saxBuilder();
        Document doc = builder.build(instream);

        Element root = doc.getRootElement();
        List<Element> parsedMenus = root.getChildren("menu");
        for (Element e : parsedMenus) {
            config.addTab(elementToParsedTab(e));
        }

        return config;
    }

    /**
     * Element to parsed tab.
     *
     * @param element
     *            the element
     *
     * @return the parsed tab
     */
    private static ParsedTab elementToParsedTab(Element element) {

        ParsedTab tab = new ParsedTab();

        tab.setName(element.getAttributeValue("name"));
        if (element.getAttributeValue("weblogPerms") != null) {
            tab.setWeblogPermissionActions(Utilities.stringToStringList(
                    element.getAttributeValue("weblogPerms"), ","));
        }
        if (element.getAttributeValue("globalPerms") != null) {
            tab.setGlobalPermissionActions(Utilities.stringToStringList(
                    element.getAttributeValue("globalPerms"), ","));
        }
        tab.setEnabledProperty(element.getAttributeValue("enabledProperty"));
        tab.setDisabledProperty(element.getAttributeValue("disabledProperty"));

        for (Element e : element.getChildren("menu-item")) {
            tab.addItem(elementToParsedTabItem(e));
        }

        return tab;
    }

    /**
     * Element to parsed tab item.
     * 
     * @param element
     *            the element
     * 
     * @return the parsed tab item
     */
    private static ParsedTabItem elementToParsedTabItem(Element element) {

        ParsedTabItem tabItem = new ParsedTabItem();

        tabItem.setName(element.getAttributeValue("name"));
        tabItem.setAction(element.getAttributeValue("action"));

        String subActions = element.getAttributeValue("subactions");
        if (subActions != null) {
            Set<String> set = new HashSet<>();
            for (String string : Utilities.stringToStringList(subActions, ",")) {
                set.add(string);
            }
            tabItem.setSubActions(set);
        }

        if (element.getAttributeValue("weblogPerms") != null) {
            tabItem.setWeblogPermissionActions(Utilities.stringToStringList(
                    element.getAttributeValue("weblogPerms"), ","));
        }
        if (element.getAttributeValue("globalPerms") != null) {
            tabItem.setGlobalPermissionActions(Utilities.stringToStringList(
                    element.getAttributeValue("globalPerms"), ","));
        }
        tabItem.setEnabledProperty(element.getAttributeValue("enabledProperty"));
        tabItem.setDisabledProperty(element.getAttributeValue("disabledProperty"));

        return tabItem;
    }

}
