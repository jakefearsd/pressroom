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
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.Serializable;
import org.apache.roller.weblogger.pojos.ThemeResource;


/**
 * A FileManagerImpl specific implementation of a ThemeResource.
 *
 * ThemeResources from the FileManagerImpl are backed by a java.io.File
 * object which represents the resource on a filesystem.
 *
 * This class is internal to the FileManagerImpl class because there should
 * not be any external classes which need to construct their own instances
 * of this class.
 */
// compareTo is used only by SharedThemeFromDir.getResources()'s
// Collections.sort(List<ThemeResource>) -- List sort calls compareTo
// exclusively, never equals/hashCode. Confirmed by
// `grep -rn "TreeSet\|TreeMap\|SortedSet\|\.sort(\|Collections.sort"
// app/src/main/java | grep -i theme`: no TreeSet/TreeMap/SortedSet/HashSet of
// ThemeResource exists anywhere in app/src/main/java, so equals/hashCode on
// this class is never consulted.
@SuppressWarnings("PMD.OverrideBothEqualsAndHashCodeOnComparable")
@SuppressFBWarnings(
        value = "EQ_COMPARETO_USE_OBJECT_EQUALS",
        justification = "compareTo is consulted only by Collections.sort(List<ThemeResource>) in "
                + "SharedThemeFromDir.getResources -- List sort uses compareTo exclusively, never "
                + "equals/hashCode. No TreeSet/TreeMap/HashSet of ThemeResource exists anywhere in "
                + "app/src/main/java (grepped). Adding equals/hashCode here would add API surface "
                + "nothing reads.")
public class SharedThemeResourceFromDir
        implements ThemeResource, Serializable {

    private static final long serialVersionUID = 1L;

    // the physical java.io.File backing this resource
    private File resourceFile;
    
    // the relative path of the resource within the theme
    private String relativePath;
    
    
    public SharedThemeResourceFromDir(String path, File file) {
        relativePath = path;
        resourceFile = file;
    }
    
    
    /**
     * @see java.lang.Comparable#compareTo(java.lang.Object)
     */
    @Override
    public int compareTo(ThemeResource other) {
        return getPath().compareTo(other.getPath());
    }
    
    
    public ThemeResource[] getChildren() {
        return new ThemeResource[0];
    }
    
    
    @Override
    public String getName() {
        return resourceFile.getName();
    }
    
    @Override
    public String getPath() {
        return relativePath;
    }
    
    @Override
    public long getLastModified() {
        return resourceFile.lastModified();
    }
    
    @Override
    public long getLength() {
        return resourceFile.length();
    }
    
    @Override
    public boolean isDirectory() {
        return resourceFile.isDirectory();
    }
    
    public boolean isFile() {
        return resourceFile.isFile();
    }
    
    @Override
    public InputStream getInputStream() {
        try {
            return new FileInputStream(resourceFile);
        } catch (java.io.FileNotFoundException ex) {
            // should never happen, rethrow as runtime exception
            throw new RuntimeException("Error constructing input stream", ex);
        }
    }
    
}
