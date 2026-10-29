/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  The ASF licenses this file to You
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
package com.wikantik.pressroom.business.jpa;

import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.business.DatabaseProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.xml.sax.SAXException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * How {@link JPAPersistenceStrategy}'s constructor reads
 * {@code META-INF/persistence.xml} from the thread context class loader, and
 * how it fails when that file is absent, malformed or incomplete. Each case
 * swaps in a class loader that serves a hand-written file for that one
 * resource and delegates everything else.
 *
 * <p>Characterisation tests: written against the existing behaviour and
 * expected to pass immediately, pinning it so a later change is noticed.
 */
class JPAPersistenceStrategyBootstrapTest {

    private static final String RESOURCE = "META-INF/persistence.xml";

    private static final String HEADER = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n";

    @TempDir
    Path tmp;

    /** A class loader whose only difference from the test's is what it answers for persistence.xml. */
    private static ClassLoader serving(URL persistenceXml) {
        return new ClassLoader(JPAPersistenceStrategyBootstrapTest.class.getClassLoader()) {
            @Override
            public URL getResource(String name) {
                return RESOURCE.equals(name) ? persistenceXml : super.getResource(name);
            }
        };
    }

    private static JPAPersistenceStrategy construct(ClassLoader loader, DatabaseProvider provider)
            throws WebloggerException {
        Thread thread = Thread.currentThread();
        ClassLoader previous = thread.getContextClassLoader();
        thread.setContextClassLoader(loader);
        try {
            return new JPAPersistenceStrategy(provider);
        } finally {
            thread.setContextClassLoader(previous);
        }
    }

    /** A JNDI-typed provider: nothing is looked up until the factory is built, which these cases never reach. */
    private static DatabaseProvider jndiProvider() {
        DatabaseProvider provider = mock(DatabaseProvider.class);
        when(provider.getType()).thenReturn(DatabaseProvider.ConfigurationType.JNDI_NAME);
        when(provider.getFullJndiName()).thenReturn("java:comp/env/jdbc/pressroom");
        return provider;
    }

    private URL write(Path dir, String xml) throws Exception {
        Path file = dir.resolve(RESOURCE);
        Files.createDirectories(file.getParent());
        Files.writeString(file, xml, StandardCharsets.UTF_8);
        return file.toUri().toURL();
    }

    /** The constructor wraps every bootstrap failure once more; the reason is the cause. */
    private Throwable bootstrapFailure(ClassLoader loader) {
        WebloggerException thrown = assertThrows(WebloggerException.class,
                () -> construct(loader, jndiProvider()));
        assertInstanceOf(WebloggerException.class, thrown.getCause());
        return thrown.getCause();
    }

    @Test
    void aMissingPersistenceXmlFailsBootstrapNamingTheResource() {
        Throwable reason = bootstrapFailure(serving(null));
        assertEquals("Could not find META-INF/persistence.xml on the classpath", reason.getMessage());
    }

    @Test
    void anUnparseablePersistenceXmlFailsBootstrapWithTheParserError() throws Exception {
        Throwable reason = bootstrapFailure(serving(write(tmp, HEADER + "<persistence version=\"3.0\">")));
        assertEquals("Could not parse META-INF/persistence.xml", reason.getMessage());
        assertInstanceOf(SAXException.class, reason.getCause());
    }

    @Test
    void aDoctypeDeclarationIsRefusedSoNoExternalEntityIsEverResolved() throws Exception {
        Path secret = tmp.resolve("secret.txt");
        Files.writeString(secret, "com/wikantik/pressroom/pojos/Weblog.orm.xml");
        String xml = HEADER
                + "<!DOCTYPE persistence [<!ENTITY leak SYSTEM \"" + secret.toUri() + "\">]>\n"
                + "<persistence version=\"3.0\"><persistence-unit name=\"PressroomPU\">"
                + "<mapping-file>&leak;</mapping-file></persistence-unit></persistence>";

        Throwable reason = bootstrapFailure(serving(write(tmp, xml)));
        assertEquals("Could not parse META-INF/persistence.xml", reason.getMessage());
        assertTrue(reason.getCause().getMessage().contains("DOCTYPE"), reason.getCause().getMessage());
    }

    @Test
    void aPersistenceXmlWithoutThePressroomUnitFailsBootstrap() throws Exception {
        String xml = HEADER + "<persistence version=\"3.0\"><persistence-unit name=\"OtherPU\">"
                + "<mapping-file>x.orm.xml</mapping-file></persistence-unit></persistence>";
        Throwable reason = bootstrapFailure(serving(write(tmp, xml)));
        assertEquals("No <persistence-unit name=\"PressroomPU\"> found in META-INF/persistence.xml",
                reason.getMessage());
    }

    @Test
    void aPressroomUnitListingNoMappingFilesFailsBootstrap() throws Exception {
        String xml = HEADER + "<persistence version=\"3.0\"><persistence-unit name=\"OtherPU\">"
                + "<mapping-file>elsewhere.orm.xml</mapping-file></persistence-unit>"
                + "<persistence-unit name=\"PressroomPU\"/></persistence>";
        Throwable reason = bootstrapFailure(serving(write(tmp, xml)));
        assertEquals("No <mapping-file> entries found in the \"PressroomPU\" persistence-unit of "
                + "META-INF/persistence.xml", reason.getMessage(),
                "only the PressroomPU unit's own mapping files count");
    }

    @Test
    @SuppressWarnings("deprecation") // URL(String) is the only way to build a URL that is not a legal URI
    void aResourceUrlThatIsNotAValidUriFailsBootstrapNamingTheUrl() throws Exception {
        Path dir = Files.createDirectories(tmp.resolve("with space"));
        String xml = HEADER + "<persistence version=\"3.0\"><persistence-unit name=\"PressroomPU\">"
                + "<mapping-file>x.orm.xml</mapping-file></persistence-unit></persistence>";
        write(dir, xml);
        // A raw space: readable as a file URL, but not a legal URI.
        URL unencoded = new URL("file:" + dir.toAbsolutePath() + "/" + RESOURCE);

        Throwable reason = bootstrapFailure(serving(unencoded));
        assertEquals("Could not derive a persistence-unit root URL from " + unencoded, reason.getMessage());
    }
}
