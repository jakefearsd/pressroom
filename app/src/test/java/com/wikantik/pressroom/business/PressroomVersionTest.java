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
package com.wikantik.pressroom.business;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that {@code /roller-version.properties} -- the classpath resource
 * {@link WebloggerImpl}'s constructor reads {@code ro.version}/{@code
 * ro.revision} from, and which the admin footer ({@code tiles/footer.jsp})
 * ultimately displays -- actually carries a real, Maven-substituted version
 * string in the packaged build.
 *
 * <p>The source template ({@code src/main/resources-filtered/
 * roller-version.properties}) holds the literal placeholder {@code
 * ro.version=${project.version}}; {@code app/pom.xml} filters it via the
 * {@code src/main/resources-filtered} resource entry (filtering=true) into
 * {@code target/classes/roller-version.properties}, which is what actually
 * ships on the classpath. This test reads that packaged resource the same
 * way {@link WebloggerImpl} does, so a regression in the pom's resource
 * filtering (wrong directory, filtering turned off, the include list
 * dropping the file) fails a fast unit test instead of shipping a footer
 * that reads "Powered by Roller Version  ()".
 */
class PressroomVersionTest {

    @Test
    void versionResolvesNonEmptyFromThePackagedResource() throws IOException {
        Properties props = loadPackagedVersionProperties();

        String version = props.getProperty("ro.version");
        assertNotNull(version, "ro.version is missing from the packaged roller-version.properties");
        assertFalse(version.isBlank(), "ro.version is blank in the packaged roller-version.properties");
        assertFalse(version.contains("${"),
                "ro.version still contains an unresolved Maven placeholder: \"" + version + "\" -- "
                        + "resource filtering did not run over src/main/resources-filtered");
    }

    @Test
    void revisionResolvesNonEmptyFromThePackagedResource() throws IOException {
        Properties props = loadPackagedVersionProperties();

        String revision = props.getProperty("ro.revision");
        assertNotNull(revision, "ro.revision is missing from the packaged roller-version.properties");
        assertFalse(revision.isBlank(), "ro.revision is blank in the packaged roller-version.properties");
        assertFalse(revision.contains("${"),
                "ro.revision still contains an unresolved Maven placeholder: \"" + revision + "\"");
    }

    /**
     * Mirrors {@code WebloggerImpl}'s own constructor: {@code
     * getClass().getResourceAsStream("/roller-version.properties")}.
     */
    private Properties loadPackagedVersionProperties() throws IOException {
        Properties props = new Properties();
        try (InputStream in = getClass().getResourceAsStream("/roller-version.properties")) {
            assertTrue(in != null, "/roller-version.properties not found on the classpath -- "
                    + "the app/pom.xml resources-filtered entry did not run");
            props.load(in);
        }
        return props;
    }
}
