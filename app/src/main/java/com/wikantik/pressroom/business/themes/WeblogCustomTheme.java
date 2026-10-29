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

package com.wikantik.pressroom.business.themes;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.business.WeblogManager;

import java.util.Date;
import java.util.List;
import com.wikantik.pressroom.pojos.Theme;
import com.wikantik.pressroom.pojos.ThemeResource;
import com.wikantik.pressroom.pojos.ThemeTemplate;
import com.wikantik.pressroom.pojos.ThemeTemplate.ComponentType;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.pojos.WeblogTheme;


/**
 * A WeblogTheme custom defined by the weblog owner.
 */
// compareTo exists only to satisfy the Theme interface's Comparable contract
// and is dead code in practice: ThemeManagerImpl.getWeblogTheme(weblog)
// constructs exactly one WeblogCustomTheme per call (confirmed by
// `grep -rn "WeblogCustomTheme" app/src/main/java`, whose only non-declaration
// hit is that single `new WeblogCustomTheme(weblog, pressroom.getWeblogManager())`), and it is returned and
// used standalone -- never placed in a List, Set or Map alongside a sibling
// instance, so compareTo/equals/hashCode are never actually compared against
// one another. getName() also always returns the constant CUSTOM, so a
// name-based equals would make every weblog's custom theme "equal" to every
// other weblog's, which is the wrong identity notion for this class -- worse
// than the current default (reference) equality, not better.
@SuppressWarnings("PMD.OverrideBothEqualsAndHashCodeOnComparable")
@SuppressFBWarnings(
        value = "EQ_COMPARETO_USE_OBJECT_EQUALS",
        justification = "compareTo satisfies the Theme interface only; ThemeManagerImpl constructs "
                + "exactly one WeblogCustomTheme per weblog and never collects instances together, "
                + "so compareTo/equals/hashCode are never compared against a sibling instance in "
                + "practice (grepped: the only construction site is getWeblogTheme). getName() is "
                + "the constant CUSTOM for every instance, so a name-based equals would incorrectly "
                + "equate every weblog's custom theme with every other's.")
public class WeblogCustomTheme extends WeblogTheme {

    private static final long serialVersionUID = 1L;

    // The weblog-side template store; transient because WeblogTheme is
    // Serializable and a manager is not state worth serialising.
    private final transient WeblogManager weblogManager;

    public WeblogCustomTheme(Weblog weblog, WeblogManager weblogManager) {
        super(weblog);
        this.weblogManager = weblogManager;
    }

    @Override
    public String getId() {
        return CUSTOM;
    }
    
    @Override
    public String getName() {
        return CUSTOM;
    }

    public String getType() {
        return CUSTOM;
    }

    @Override
    public String getDescription() {
        return CUSTOM;
    }

    public String getAuthor() {
        return "N/A";
    }
    
    @Override
    public Date getLastModified() {
        return this.weblog.getLastModified();
    }
    
    @Override
    public boolean isEnabled() {
        return true;
    }

    @Override
    public int compareTo(Theme other) {
        return getName().compareTo(other.getName());
    }

    /**
     * Get the collection of all templates associated with this Theme.
     */
    @Override
    public List<? extends ThemeTemplate> getTemplates() throws WebloggerException {
        return weblogManager.getTemplates(this.weblog);
    }
    
    
    /**
     * Lookup the stylesheet template for this theme.
     * Returns null if no stylesheet can be found.
     */
    @Override
    public ThemeTemplate getStylesheet() throws WebloggerException {
        return getTemplateByAction(ComponentType.STYLESHEET);
    }

    
    /**
     * Lookup the default template.
     * Returns null if the template cannot be found.
     */
    @Override
    public ThemeTemplate getDefaultTemplate() throws WebloggerException {
        return weblogManager
                .getTemplateByAction(this.weblog, ComponentType.WEBLOG);
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
        return weblogManager.getTemplateByAction(this.weblog, action);
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
        return weblogManager.getTemplateByName(this.weblog, name);
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
        return weblogManager.getTemplateByLink(this.weblog, link);
    }
    
    
    /**
     * Lookup the specified resource by path.
     * Returns null if the resource cannot be found.
     */
    @Override
    public ThemeResource getResource(String path) {
        // Custom themes have never resolved resources by media path -- no
        // ThemeResource implementation ever wrapped a MediaFile -- so this
        // has always returned null; the dead local variable is removed, not
        // the (already inert) behavior.
        return null;
    }
    
}
