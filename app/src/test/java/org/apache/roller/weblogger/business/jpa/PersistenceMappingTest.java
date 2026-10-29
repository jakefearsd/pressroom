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
package org.apache.roller.weblogger.business.jpa;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.metamodel.ManagedType;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.net.URL;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Set;
import java.util.TreeSet;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.apache.roller.weblogger.TestUtils;
import org.apache.roller.weblogger.business.WeblogEntryManager;
import org.apache.roller.weblogger.pojos.Weblog;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

/**
 * The persistence unit maps every class it should, and no mapping file is
 * left out of {@code persistence.xml}.
 *
 * <p><strong>These are characterisation tests</strong> -- they pass on
 * arrival and exist to catch what the {@code org.apache.roller} to
 * {@code com.wikantik.pressroom} package move can break silently. The
 * mapping files sit beside the entities and are listed by resource path in
 * {@code META-INF/persistence.xml}; move the entities and forget one file, or
 * one {@code class} attribute, and JPA does not fail -- the entity is simply
 * unmapped (or the file unlisted) until a query touches it.
 *
 * <p>Proven to bite: with the {@code ApiToken.orm.xml} line commented out of
 * {@code persistence.xml}, both tests failed (the unlisted file in the first,
 * {@code ApiToken} mapped on disk but missing from the managed types in the
 * second).
 */
class PersistenceMappingTest {

    private static final String PERSISTENCE_XML = "META-INF/persistence.xml";

    @Test
    void persistenceXmlListsExactlyTheMappingFilesThatExist() throws Exception {
        Set<String> listed = new TreeSet<>();
        NodeList files = parse(resource(PERSISTENCE_XML)).getElementsByTagNameNS("*", "mapping-file");
        for (int i = 0; i < files.getLength(); i++) {
            listed.add(files.item(i).getTextContent().trim());
        }

        Set<String> onDisk = mappingFilesOnDisk();

        assertEquals(onDisk, listed, "mapping files on disk (expected) vs listed in persistence.xml (actual)");
        for (String path : listed) {
            resource(path).close();
        }
    }

    @Test
    void everyMappedClassIsAnEntityAtRuntime() throws Exception {
        Set<String> mapped = new TreeSet<>();
        for (String file : mappingFilesOnDisk()) {
            Document orm = parse(resource(file));
            for (String tag : new String[] {"entity", "embeddable", "mapped-superclass"}) {
                NodeList types = orm.getElementsByTagNameNS("*", tag);
                for (int j = 0; j < types.getLength(); j++) {
                    mapped.add(((Element) types.item(j)).getAttribute("class"));
                }
            }
        }

        TestUtils.setupWeblogger();
        Set<String> managed = new TreeSet<>();
        for (ManagedType<?> type : emf().getMetamodel().getManagedTypes()) {
            managed.add(type.getJavaType().getName());
        }

        assertEquals(managed, mapped, () -> "managed types (expected) vs classes named in mapping files (actual); "
                + "missing at runtime: " + difference(mapped, managed)
                + "; managed but not mapped: " + difference(managed, mapped));
    }

    /** Every {@code *.orm.xml} beside {@code Weblog.class}, as classpath resource paths. */
    private static Set<String> mappingFilesOnDisk() throws Exception {
        URL anchor = Weblog.class.getResource("Weblog.orm.xml");
        assertNotNull(anchor, "Weblog.orm.xml sits beside Weblog.class");
        String pkg = Weblog.class.getPackageName().replace('.', '/');
        Set<String> found = new TreeSet<>();
        try (DirectoryStream<Path> dir = Files.newDirectoryStream(Paths.get(anchor.toURI()).getParent(), "*.orm.xml")) {
            for (Path p : dir) {
                found.add(pkg + "/" + p.getFileName());
            }
        }
        return found;
    }

    private static Set<String> difference(Set<String> a, Set<String> b) {
        Set<String> d = new TreeSet<>(a);
        d.removeAll(b);
        return d;
    }

    private static EntityManagerFactory emf() throws Exception {
        WeblogEntryManager entries = TestUtils.weblogger().getWeblogEntryManager();
        Field f = entries.getClass().getDeclaredField("strategy");
        f.setAccessible(true);
        JPAPersistenceStrategy strategy = (JPAPersistenceStrategy) f.get(entries);
        return strategy.getEntityManager(false).getEntityManagerFactory();
    }

    private static InputStream resource(String path) throws IOException {
        InputStream in = Thread.currentThread().getContextClassLoader().getResourceAsStream(path);
        if (in == null) {
            throw new IOException("not on the classpath: " + path);
        }
        return in;
    }

    private static Document parse(InputStream in) throws IOException {
        try (in) {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setNamespaceAware(true);
            f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            f.setXIncludeAware(false);
            f.setExpandEntityReferences(false);
            return f.newDocumentBuilder().parse(in);
        } catch (ParserConfigurationException | SAXException e) {
            throw new IOException("cannot parse mapping XML", e);
        }
    }
}
