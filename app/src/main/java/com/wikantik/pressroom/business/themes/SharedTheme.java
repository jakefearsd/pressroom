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
import com.wikantik.pressroom.pojos.Theme;
import com.wikantik.pressroom.pojos.ThemeResource;

import java.io.Serializable;
import java.util.Date;
import java.util.List;


/**
 * A SharedTheme is a theme implementation which is designed to be shared by
 * multiple weblogs using a common set of resources.
 */
// compareTo is used only to Collections.sort(List<SharedTheme>)
// (ThemeManagerImpl.getEnabledThemesList) -- List.sort/Collections.sort call
// compareTo exclusively and never equals/hashCode. Confirmed by
// `grep -rn "TreeSet\|TreeMap\|SortedSet\|\.sort(\|Collections.sort"
// app/src/main/java | grep -i theme`: the only theme-related hits are that
// Collections.sort and one on SharedThemeFromDir's ThemeResource list (same
// reasoning there). ThemeManagerImpl's own SharedTheme map is keyed by theme
// id (String), not by the SharedTheme object, so equals/hashCode on this
// class is never consulted either. No TreeSet/TreeMap/HashSet of SharedTheme
// exists anywhere in app/src/main/java.
@SuppressWarnings("PMD.OverrideBothEqualsAndHashCodeOnComparable")
@SuppressFBWarnings(
        value = "EQ_COMPARETO_USE_OBJECT_EQUALS",
        justification = "compareTo is consulted only by Collections.sort(List<SharedTheme>) in "
                + "ThemeManagerImpl.getEnabledThemesList -- List sort uses compareTo exclusively, "
                + "never equals/hashCode. Nothing puts a SharedTheme into a TreeSet/TreeMap/HashSet "
                + "(confirmed by grepping app/src/main/java for TreeSet/TreeMap/SortedSet/HashSet "
                + "near Theme usages); the manager's own theme map is keyed by theme id (String), "
                + "not by this object. Adding equals/hashCode here would add API surface nothing "
                + "reads, and would risk making it inconsistent with compareTo the moment a mutable "
                + "field diverges.")
public abstract class SharedTheme implements Theme, Serializable {

    private static final long serialVersionUID = 1L;

    protected String id = null;
    protected String name = null;
    protected String description = null;
    protected String author = null;
    protected Date lastModified = null;
    protected boolean enabled = false;
    
    public abstract List<ThemeResource> getResources();
    
    public abstract ThemeResource getPreviewImage();

    @Override
    public int compareTo(Theme other) {
        return getName().compareTo(other.getName());
    }

    @Override
    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }
    
    @Override
    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    @Override
    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getAuthor() {
        return author;
    }

    public void setAuthor(String author) {
        this.author = author;
    }
    
    @Override
    public Date getLastModified() {
        return lastModified;
    }

    public void setLastModified(Date lastModified) {
        this.lastModified = lastModified;
    }
    
    @Override
    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
}
