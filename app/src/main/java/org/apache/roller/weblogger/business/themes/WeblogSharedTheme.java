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

package org.apache.roller.weblogger.business.themes;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.roller.weblogger.WebloggerException;
import org.apache.roller.weblogger.business.WeblogManager;

import java.util.*;
import org.apache.roller.weblogger.pojos.Theme;
import org.apache.roller.weblogger.pojos.ThemeResource;
import org.apache.roller.weblogger.pojos.ThemeTemplate;
import org.apache.roller.weblogger.pojos.ThemeTemplate.ComponentType;
import org.apache.roller.weblogger.pojos.Weblog;
import org.apache.roller.weblogger.pojos.WeblogTheme;


/**
 * A WeblogTheme shared by many weblogs and backed by a SharedTheme.
 */
// compareTo exists only to satisfy the Theme interface's Comparable contract
// and is dead code in practice: ThemeManagerImpl.getWeblogTheme(weblog)
// constructs exactly one WeblogSharedTheme per call (confirmed by
// `grep -rn "WeblogSharedTheme" app/src/main/java`, whose only non-declaration
// hit is that single `new WeblogSharedTheme(weblog, staticTheme, roller.getWeblogManager())`), and it is
// returned and used standalone -- never placed in a List, Set or Map
// alongside a sibling instance. compareTo simply delegates to the wrapped
// SharedTheme's own (separately suppressed, same reasoning) compareTo.
@SuppressWarnings("PMD.OverrideBothEqualsAndHashCodeOnComparable")
@SuppressFBWarnings(
        value = "EQ_COMPARETO_USE_OBJECT_EQUALS",
        justification = "compareTo satisfies the Theme interface only and delegates to the wrapped "
                + "SharedTheme; ThemeManagerImpl constructs exactly one WeblogSharedTheme per weblog "
                + "and never collects instances together, so compareTo/equals/hashCode are never "
                + "compared against a sibling instance in practice (grepped: the only construction "
                + "site is getWeblogTheme).")
public class WeblogSharedTheme extends WeblogTheme {

    private static final long serialVersionUID = 1L;

    private static final Logger log = LoggerFactory.getLogger(WeblogSharedTheme.class);
    
    private SharedTheme theme;

    // The weblog-side template store; transient because WeblogTheme is
    // Serializable and a manager is not state worth serialising.
    private final transient WeblogManager weblogManager;

    public WeblogSharedTheme(Weblog weblog, SharedTheme theme, WeblogManager weblogManager) {
        super(weblog);
        this.theme = theme;
        this.weblogManager = weblogManager;
    }
    
    
    @Override
    public String getId() {
        return this.theme.getId();
    }
    
    @Override
    public String getName() {
        return this.theme.getName();
    }

    @Override
    public String getDescription() {
        return this.theme.getDescription();
    }
    
    @Override
    public Date getLastModified() {
        return this.theme.getLastModified();
    }
    
    @Override
    public boolean isEnabled() {
        return this.theme.isEnabled();
    }

    @Override
    public int compareTo(Theme other) {
        return theme.compareTo(other);
    }

    /**
     * Get the collection of all templates associated with this Theme.
     */
    @Override
    public List<ThemeTemplate> getTemplates() throws WebloggerException {
        
        Map<String, ThemeTemplate> pages = new TreeMap<>();
        
        // first get the pages from the db
        try {
            for (ThemeTemplate template : weblogManager.getTemplates(this.weblog)) {
                pages.put(template.getName(), template);
            }
        } catch(Exception e) {
            // db error
            log.error("Error getting templates from database", e);
        }
        
        
        // now get theme pages if needed and put them in place of db pages
        try {
            for (ThemeTemplate template : this.theme.getTemplates()) {
                // note that this will put theme pages over custom
                // pages in the pages list, which is what we want
                pages.put(template.getName(), template);
            }
        } catch(Exception e) {
            // how??
            log.error("Error getting templates from theme", e);
        }
        
        return new ArrayList<>(pages.values());
    }
    
    
    /**
     * Lookup the stylesheet template for this theme.
     * Returns null if no stylesheet can be found.
     */
    @Override
     public ThemeTemplate getStylesheet() throws WebloggerException {
        // stylesheet is handled differently than other templates because with
        // the stylesheet we want to return the weblog custom version if it
        // exists, otherwise we return the shared theme version

        // load from theme first to see if we even support a stylesheet
        ThemeTemplate stylesheet = this.theme.getStylesheet();
        if (stylesheet != null) {
            // now try getting custom version from weblog
            ThemeTemplate override = weblogManager.getTemplateByAction(this.weblog, ComponentType.STYLESHEET);
            if (override != null) {
                stylesheet = override;
            }
        }
        return stylesheet;
    }
    
    
    /**
     * Lookup the default template.
     */
    @Override
    public ThemeTemplate getDefaultTemplate() throws WebloggerException {
        return this.theme.getDefaultTemplate();
    }
    
    
    /**
     * Lookup the specified template by action.
     * Returns null if the template cannot be found.
     */
    @Override
    public ThemeTemplate getTemplateByAction(ComponentType action) throws WebloggerException {
        
        if (action == null) {
            return null;
        }
        
        // NOTE: we specifically do *NOT* return templates by action from the
        // weblog's custom templates if the weblog is using a theme because we
        // don't want old templates to take effect when using a specific theme
        return this.theme.getTemplateByAction(action);
    }
    
    
    /**
     * Lookup the specified template by name.
     * Returns null if the template cannot be found.
     */
    @Override
    public ThemeTemplate getTemplateByName(String name) throws WebloggerException {
        
        if (name == null) {
            return null;
        }
        
        ThemeTemplate template;
        
        // if name refers to the stylesheet then return result of getStylesheet()
        ThemeTemplate stylesheet = getStylesheet();
        if (stylesheet != null && name.equals(stylesheet.getName())) {
            return stylesheet;
        }
        
        // first check if this user has selected a theme
        // if so then return the proper theme template
        template = this.theme.getTemplateByName(name);
        
        // if we didn't get the Template from a theme then look in the db
        if (template == null) {
            template = weblogManager.getTemplateByName(this.weblog, name);
        }
        
        return template;
    }
    
    
    /**
     * Lookup the specified template by link.
     * Returns null if the template cannot be found.
     */
    @Override
     public ThemeTemplate getTemplateByLink(String link) throws WebloggerException {

        if (link == null) {
            return null;
        }

        ThemeTemplate template;

        // if name refers to the stylesheet then return result of getStylesheet()
        ThemeTemplate stylesheet = getStylesheet();
        if(stylesheet != null && link.equals(stylesheet.getLink())) {
            return stylesheet;
        }

        // first check if this user has selected a theme
        // if so then return the proper theme template
        template = this.theme.getTemplateByLink(link);

        // if we didn't get the Template from a theme then look in the db
        if(template == null) {
            template = weblogManager.getTemplateByLink(this.weblog, link);
        }

        return template;
    }
    
    
    /**
     * Lookup the specified resource by path.
     * Returns null if the resource cannot be found.
     */
    @Override
    public ThemeResource getResource(String path) {
        
        if (path == null) {
            return null;
        }
        
        // Shared themes have never resolved a fallback resource from weblog
        // uploads -- the lookup here was never wired into the returned
        // value -- so this has always meant "check the shared theme only";
        // the dead local variable and its wasted lookup are removed, not
        // the (already inert) fallback behavior.
        return this.theme.getResource(path);
    }
    
}
