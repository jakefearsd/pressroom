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

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.function.Supplier;

import javax.sql.DataSource;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.roller.weblogger.WebloggerException;
import org.apache.roller.weblogger.config.WebloggerConfig;
import org.eclipse.persistence.config.PersistenceUnitProperties;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.EntityManager;
import jakarta.persistence.FlushModeType;
import jakarta.persistence.PersistenceException;
import jakarta.persistence.Query;
import jakarta.persistence.SharedCacheMode;
import jakarta.persistence.ValidationMode;
import jakarta.persistence.spi.ClassTransformer;
import jakarta.persistence.spi.PersistenceUnitInfo;
import jakarta.persistence.spi.PersistenceUnitTransactionType;
import javax.naming.InitialContext;
import javax.naming.NamingException;
import jakarta.persistence.TypedQuery;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import org.apache.roller.weblogger.business.DatabaseProvider;


/**
 * Responsible for the lowest-level interaction with the JPA API.
 */
// Every EntityManager handed out by getEntityManager()/getThreadLocalEntityManager()
// is the one thread-local instance held in the threadLocalEntityManager field --
// its lifecycle spans the whole unit of work (many calls into this class) and is
// closed exactly once, in release(), not by whichever method happened to fetch it.
// PMD's CloseResource has no way to see that field-scoped ownership across the
// dozen accessor methods below that each call getEntityManager(...) and return
// without closing it -- closing it in any of them would tear down every other
// manager call still in flight on this thread within the same request/transaction.
@SuppressWarnings("PMD.CloseResource")
public class JPAPersistenceStrategy {

    private static final Logger log =
        LoggerFactory.getLogger(JPAPersistenceStrategy.class);

    private static final String PERSISTENCE_UNIT_NAME = "RollerPU";

    /** In FROM, so it runs whatever the planner makes of the select list. */
    private static final String LOCK_UNTIL_COMMIT =
            "SELECT 1 FROM pg_advisory_xact_lock(hashtextextended(?1, 0))";

    private static final String PERSISTENCE_XML_RESOURCE = "META-INF/persistence.xml";

    private static final String ECLIPSELINK_PROVIDER_CLASS_NAME =
            "org.eclipse.persistence.jpa.PersistenceProvider";

    /**
     * The thread local EntityManager.
     */
    private final ThreadLocal<EntityManager> threadLocalEntityManager = new ThreadLocal<>();

    /**
     * Callbacks queued to run once the CURRENT unit of work actually
     * commits -- see {@link #runAfterCommit} and {@link #flush()}.
     */
    private final ThreadLocal<List<Runnable>> pendingPostCommitActions = new ThreadLocal<>();

    /**
     * Work queued to run inside the CURRENT unit of work just before it
     * commits -- see {@link #beforeCommit} and {@link #flush()}. One per type.
     */
    private final ThreadLocal<Map<Class<?>, BeforeCommit>> pendingPreCommitActions = new ThreadLocal<>();

    /** Work that runs in the transaction, immediately before its commit. */
    @FunctionalInterface
    public interface BeforeCommit {
        void run() throws WebloggerException;
    }

    /**
     * The EntityManagerFactory for this Roller instance.
     */
    private EntityManagerFactory emf;
    
            
    /**
     * Construct by finding JPA EntityManagerFactory.
     * @param dbProvider database configuration information for manual configuration.
     * @throws org.apache.roller.weblogger.WebloggerException on any error
     */
    @SuppressFBWarnings(
            value = "LDAP_INJECTION",
            justification = "The JNDI name is jpa.emf.jndi.name, operator configuration (roller.properties "
                    + "or ROLLER_* environment); no request reaches it.")
    public JPAPersistenceStrategy(DatabaseProvider dbProvider) throws WebloggerException {
        String jpaConfigurationType = WebloggerConfig.getProperty("jpa.configurationType");
        if ("jndi".equals(jpaConfigurationType)) {
            // Lookup EMF via JNDI: added for Geronimo
            String emfJndiName = "java:comp/env/" + WebloggerConfig.getProperty("jpa.emf.jndi.name");
            try {
                emf = (EntityManagerFactory) new InitialContext().lookup(emfJndiName);
            } catch (NamingException e) {
                throw new WebloggerException("Could not look up EntityManagerFactory in jndi at " + emfJndiName, e);
            }
        } else {

            // Add all JPA, OpenJPA, HibernateJPA, etc. properties found
            Properties emfProps = new Properties();

            // Default weaving off, set *before* the WebloggerConfig passthrough
            // below so an operator who explicitly sets eclipselink.weaving in
            // roller-custom.properties still wins (the loop overwrites this
            // entry if that key is present). EclipseLink's
            // createContainerEntityManagerFactoryImpl unconditionally calls
            // PersistenceUnitInfo.addTransformer(transformer) to register a
            // ClassTransformer for load-time weaving (verified by decompiling
            // EclipseLink 5.0.0 — there is no RESOURCE_LOCAL exemption).
            // RollerPersistenceUnitInfo.addTransformer() below is a no-op
            // because there is no instrumentation agent to hand the
            // transformer to under Boot's embedded Tomcat (no -javaagent, no
            // load-time weaving hook) and static weaving as a build step is
            // out of scope here. Declaring eclipselink.weaving=false makes
            // that a deliberate, disclosed choice instead of a silent side
            // effect of a no-op transformer: EclipseLink falls back to
            // non-weaved change detection (calendar/deep-compare instead of
            // AttributeChangeTracking) and unfetched-attribute access goes
            // through plain getters instead of triggering indirection. No
            // entity in the 19 mapping files has a LAZY to-one mapping (only
            // LAZY one-to-many collections, which use IndirectList regardless
            // of weaving), so no correctness change results — only a
            // performance/change-tracking-strategy difference.
            emfProps.setProperty("eclipselink.weaving", "false");

            // The configured database password is plaintext; without this,
            // EclipseLink's default encryptor mistakes an even-length all-hex
            // password for one of its own encrypted ones and refuses to
            // deploy (EclipseLink-7360). See PlaintextPasswordEncryptor. Set
            // before the passthrough, like weaving, so an explicit
            // eclipselink.login.encryptor in roller-custom.properties wins.
            emfProps.setProperty(PersistenceUnitProperties.LOGIN_ENCRYPTOR,
                    PlaintextPasswordEncryptor.class.getName());

            Enumeration<Object> keys = WebloggerConfig.keys();
            while (keys.hasMoreElements()) {
                String key = (String) keys.nextElement();
                if (       key.startsWith("jakarta.persistence.")
                        || key.startsWith("openjpa.")
                        || key.startsWith("eclipselink.")) {
                    String value = WebloggerConfig.getProperty(key);
                    log.info("{}: {}", key, value);
                    emfProps.setProperty(key, value);
                }
            }

            if (dbProvider.getType() == DatabaseProvider.ConfigurationType.JNDI_NAME) {
                emfProps.setProperty("jakarta.persistence.nonJtaDataSource", dbProvider.getFullJndiName());
            } else {
                emfProps.setProperty("jakarta.persistence.jdbc.driver", dbProvider.getJdbcDriverClass());
                emfProps.setProperty("jakarta.persistence.jdbc.url", dbProvider.getJdbcConnectionURL());
                emfProps.setProperty("jakarta.persistence.jdbc.user", dbProvider.getJdbcUsername());
                emfProps.setProperty("jakarta.persistence.jdbc.password", dbProvider.getJdbcPassword());
            }

            try {
                ClassLoader classLoader = currentClassLoader();
                PersistenceXmlInfo persistenceXmlInfo = readPersistenceXmlInfo(classLoader);
                PersistenceUnitInfo unitInfo = new RollerPersistenceUnitInfo(
                        persistenceXmlInfo.mappingFileNames(), persistenceXmlInfo.rootUrl(),
                        persistenceXmlInfo.schemaVersion(), classLoader);
                this.emf = new org.eclipse.persistence.jpa.PersistenceProvider()
                        .createContainerEntityManagerFactory(unitInfo, emfProps);

            } catch (Exception pe) {
                log.error("ERROR: creating entity manager", pe);
                throw new WebloggerException(pe);
            }
        }
    }

    /**
     * Build the EclipseLink {@code EntityManagerFactory} without going through
     * {@code Persistence.createEntityManagerFactory}'s classpath-scanning
     * discovery. That entry point asks EclipseLink's {@code
     * PersistenceUnitProcessor} to compute a "persistence unit root URL" from
     * the {@code META-INF/persistence.xml} resource URL, splitting on the
     * <em>last</em> {@code !/} and requiring the left half to end literally in
     * {@code .jar} or {@code .war}. A Spring Boot repackaged executable
     * archive nests {@code WEB-INF/classes/} as its own pseudo-archive
     * boundary, producing a second {@code !/} (e.g. {@code
     * jar:nested:/app.war/!WEB-INF/classes/!/META-INF/persistence.xml}), which
     * fails that check and throws {@code PersistenceUnitLoadingException} even
     * though the resource is perfectly readable. Calling {@code
     * PersistenceProvider.createContainerEntityManagerFactory} directly with a
     * hand-built {@link PersistenceUnitInfo} skips that URL parsing entirely,
     * so this path works identically under {@code java -jar}, {@code
     * spring-boot:run} (exploded classpath), and an external Tomcat.
     *
     * <p>The mapping-file list is parsed from the real {@code
     * META-INF/persistence.xml} at runtime (rather than duplicated as a
     * literal constant here) so that file stays the single source of truth;
     * it never drifts from what this method actually builds.
     *
     * <p>{@link PersistenceUnitInfo#getPersistenceUnitRootUrl()} is not left
     * {@code null}: EclipseLink 5.0.0's {@code
     * PersistenceUnitProcessor.buildPersistenceUnitName} unconditionally calls
     * {@code rootURL.toString()} on it (verified empirically — a null root
     * throws {@code NullPointerException} before any mapping file is even
     * read), so this derives a real, non-null root URL by stripping the
     * trailing {@code META-INF/persistence.xml} off the resource URL used to
     * read the file. Unlike EclipseLink's own discovery-path parsing, nothing
     * in the {@code createContainerEntityManagerFactory} path re-splits this
     * URL on {@code !/} or checks its suffix, so the same nested-archive URL
     * that breaks {@code Persistence.createEntityManagerFactory}'s discovery
     * works fine here purely as an opaque root anchor.
     */
    private static PersistenceXmlInfo readPersistenceXmlInfo(ClassLoader classLoader) throws WebloggerException {
        URL resource = classLoader.getResource(PERSISTENCE_XML_RESOURCE);
        if (resource == null) {
            throw new WebloggerException("Could not find " + PERSISTENCE_XML_RESOURCE + " on the classpath");
        }

        try (InputStream in = resource.openStream()) {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            // Harden against XXE; persistence.xml is a trusted, packaged resource
            // but there is no reason to allow external entity resolution here.
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.parse(in);

            Element unit = findPersistenceUnit(doc);
            NodeList mappingFileNodes = unit.getElementsByTagNameNS("*", "mapping-file");
            List<String> mappingFileNames = new ArrayList<>();
            for (int i = 0; i < mappingFileNodes.getLength(); i++) {
                mappingFileNames.add(mappingFileNodes.item(i).getTextContent().trim());
            }
            if (mappingFileNames.isEmpty()) {
                throw new WebloggerException("No <mapping-file> entries found in the \""
                        + PERSISTENCE_UNIT_NAME + "\" persistence-unit of " + PERSISTENCE_XML_RESOURCE);
            }
            String schemaVersion = readSchemaVersion(doc);
            return new PersistenceXmlInfo(mappingFileNames, derivePersistenceUnitRootUrl(resource), schemaVersion);
        } catch (ParserConfigurationException | SAXException | IOException e) {
            throw new WebloggerException("Could not parse " + PERSISTENCE_XML_RESOURCE, e);
        }
    }

    /**
     * Reads the {@code version} attribute off the root {@code <persistence>}
     * element (e.g. {@code "3.0"}), rather than hardcoding the value this
     * strategy happens to expect today, so {@code getPersistenceXMLSchemaVersion()}
     * stays truthful if persistence.xml is ever bumped to a newer schema
     * version. Falls back to {@code "3.0"} only if the attribute is somehow
     * missing or blank; the {@code version} attribute is mandatory per the
     * Jakarta Persistence XSD, so in practice this fallback is unreachable
     * for a persistence.xml that parses at all.
     */
    private static String readSchemaVersion(Document doc) {
        // Element.getAttribute() is specified to return "" rather than null
        // when the attribute is absent -- it never returns null -- so only
        // the blank case needs handling here.
        String version = doc.getDocumentElement().getAttribute("version");
        return version.isBlank() ? "3.0" : version;
    }

    /**
     * Derives the persistence-unit root URL by stripping the trailing
     * {@code META-INF/persistence.xml} off the resource URL. Used only as an
     * opaque, non-null anchor (see {@link #readPersistenceXmlInfo}); every
     * entity is reached through {@code getMappingFileNames()} instead of
     * being resolved relative to this root.
     */
    private static URL derivePersistenceUnitRootUrl(URL persistenceXmlUrl) throws WebloggerException {
        String urlString = persistenceXmlUrl.toString();
        if (!urlString.endsWith(PERSISTENCE_XML_RESOURCE)) {
            // Defensive fallback: some non-standard classloader returned a URL
            // that doesn't literally end in the resource path we asked for.
            // Returning the persistence.xml URL itself unmodified rather than
            // failing startup is safe specifically because this value is
            // never dereferenced as a real location — RollerPersistenceUnitInfo
            // sets excludeUnlistedClasses() true, so EclipseLink never opens
            // this URL to scan for annotated classes; buildPersistenceUnitName
            // only ever calls toString() on it to build an internal cache key
            // (see the class-level javadoc on readPersistenceXmlInfo). A
            // "wrong" root here is at worst a slightly odd-looking internal
            // name, never a runtime failure.
            return persistenceXmlUrl;
        }
        String rootUrlString = urlString.substring(0, urlString.length() - PERSISTENCE_XML_RESOURCE.length());
        try {
            return java.net.URI.create(rootUrlString).toURL();
        } catch (IllegalArgumentException | java.net.MalformedURLException e) {
            throw new WebloggerException("Could not derive a persistence-unit root URL from " + urlString, e);
        }
    }

    /**
     * Holds what {@link #readPersistenceXmlInfo} parses out of the real
     * {@code META-INF/persistence.xml}: the mapping-file list, the
     * persistence-unit root URL derived from the same resource lookup, and
     * the schema version declared on the root {@code <persistence>} element.
     */
    private record PersistenceXmlInfo(List<String> mappingFileNames, URL rootUrl, String schemaVersion) {
    }

    private static Element findPersistenceUnit(Document doc) throws WebloggerException {
        NodeList persistenceUnits = doc.getElementsByTagNameNS("*", "persistence-unit");
        for (int i = 0; i < persistenceUnits.getLength(); i++) {
            Element candidate = (Element) persistenceUnits.item(i);
            if (PERSISTENCE_UNIT_NAME.equals(candidate.getAttribute("name"))) {
                return candidate;
            }
        }
        throw new WebloggerException("No <persistence-unit name=\"" + PERSISTENCE_UNIT_NAME
                + "\"> found in " + PERSISTENCE_XML_RESOURCE);
    }

    @SuppressWarnings("PMD.UseProperClassLoader") // the context loader is tried first; this is the fallback
    private static ClassLoader currentClassLoader() {
        ClassLoader tccl = Thread.currentThread().getContextClassLoader();
        return tccl != null ? tccl : JPAPersistenceStrategy.class.getClassLoader();
    }

    /**
     * Hand-built {@link PersistenceUnitInfo} standing in for what a Java EE
     * container would normally assemble from {@code META-INF/persistence.xml}.
     * The mapping-file list is the only thing that varies between instances;
     * everything else is a fixed fact about the "RollerPU" unit.
     */
    private static final class RollerPersistenceUnitInfo implements PersistenceUnitInfo {

        private final List<String> mappingFileNames;
        private final URL rootUrl;
        private final String schemaVersion;
        private final ClassLoader classLoader;

        RollerPersistenceUnitInfo(List<String> mappingFileNames, URL rootUrl, String schemaVersion,
                ClassLoader classLoader) {
            this.mappingFileNames = mappingFileNames;
            this.rootUrl = rootUrl;
            this.schemaVersion = schemaVersion;
            this.classLoader = classLoader;
        }

        @Override
        public String getPersistenceUnitName() {
            return PERSISTENCE_UNIT_NAME;
        }

        @Override
        public String getPersistenceProviderClassName() {
            return ECLIPSELINK_PROVIDER_CLASS_NAME;
        }

        @Override
        public String getScopeAnnotationName() {
            return null;
        }

        @Override
        public List<String> getQualifierAnnotationNames() {
            return List.of();
        }

        // PersistenceUnitInfo.getTransactionType() is abstract and still returns
        // the spi enum, which JPA 3.2 marks for removal; the interface leaves no
        // other way to say RESOURCE_LOCAL, so this stays until the contract moves.
        @SuppressWarnings("removal")
        @Override
        public PersistenceUnitTransactionType getTransactionType() {
            return PersistenceUnitTransactionType.RESOURCE_LOCAL;
        }

        @Override
        public DataSource getJtaDataSource() {
            return null;
        }

        @Override
        public DataSource getNonJtaDataSource() {
            // Not RESOURCE_LOCAL JTA lookup: the JDBC/JNDI coordinates are
            // supplied via the standard jakarta.persistence.jdbc.*/
            // jakarta.persistence.nonJtaDataSource properties in the map
            // passed to createContainerEntityManagerFactory, exactly as they
            // were previously passed to Persistence.createEntityManagerFactory.
            return null;
        }

        @Override
        public List<String> getMappingFileNames() {
            return mappingFileNames;
        }

        @Override
        public List<URL> getJarFileUrls() {
            return List.of();
        }

        @Override
        public URL getPersistenceUnitRootUrl() {
            // Must not be null: EclipseLink's buildPersistenceUnitName calls
            // rootURL.toString() on it unconditionally (verified empirically).
            // Every Roller entity's mapping is metadata-complete in its
            // .orm.xml (see the <entity metadata-complete="true"> attribute)
            // and excludeUnlistedClasses() is true below, so this root is
            // never used to scan for annotated classes — it only needs to be
            // non-null, which sidesteps the nested-archive URL *parsing* that
            // breaks Persistence.createEntityManagerFactory's auto-discovery
            // under java -jar (see the class-level javadoc on
            // readPersistenceXmlInfo).
            return rootUrl;
        }

        @Override
        public List<String> getManagedClassNames() {
            return List.of();
        }

        @Override
        public boolean excludeUnlistedClasses() {
            // No <class> elements are declared in persistence.xml and no
            // annotation scanning of the persistence-root is needed; every
            // entity is metadata-complete in its .orm.xml and reached through
            // getMappingFileNames() instead.
            return true;
        }

        @Override
        public SharedCacheMode getSharedCacheMode() {
            return SharedCacheMode.UNSPECIFIED;
        }

        @Override
        public ValidationMode getValidationMode() {
            return ValidationMode.AUTO;
        }

        @Override
        public Properties getProperties() {
            // persistence.xml declares no <properties> block; all EclipseLink/
            // JDBC configuration flows through the map argument to
            // createContainerEntityManagerFactory, same as before.
            return new Properties();
        }

        @Override
        public String getPersistenceXMLSchemaVersion() {
            return schemaVersion;
        }

        @Override
        public ClassLoader getClassLoader() {
            return classLoader;
        }

        @Override
        public void addTransformer(ClassTransformer transformer) {
            // EclipseLink DOES call this: createContainerEntityManagerFactoryImpl
            // invokes getNewTempClassLoader() and then unconditionally calls
            // addTransformer(transformer) to register a ClassTransformer for
            // load-time weaving (verified by decompiling EclipseLink 5.0.0 —
            // there is no RESOURCE_LOCAL exemption in that path). This is a
            // deliberate no-op: under Boot's embedded Tomcat there is no
            // instrumentation agent to hand the transformer to (no
            // -javaagent, no LTW hook), so nothing could apply it anyway.
            // eclipselink.weaving=false is set explicitly on the emfProps
            // passed to createContainerEntityManagerFactory (see the
            // constructor) so this is a disclosed, intentional "weaving off"
            // rather than a silent side effect of discarding the transformer
            // here. See that property's comment for the correctness argument
            // (no LAZY to-one mappings in any of the 19 orm.xml files).
        }

        @Override
        public ClassLoader getNewTempClassLoader() {
            // Invoked by EclipseLink as part of the same weaving handshake as
            // addTransformer() above (normally used to load classes into a
            // throwaway loader for weaving inspection without polluting the
            // real one). Since weaving is off and addTransformer() is a
            // no-op, no class is ever actually transformed through this
            // loader, so returning the live classloader instead of a fresh
            // child one is harmless here.
            return classLoader;
        }
    }

    /**
     * Refresh changes to the current object.
     * 
     * @throws org.apache.roller.weblogger.WebloggerException on any error
     */
    @SuppressFBWarnings(
            value = "DE_MIGHT_IGNORE",
            justification = "A refresh of an entity that is no longer managed is a no-op for "
                    + "the caller's purposes; the caller re-reads through the EntityManager "
                    + "either way, so there is nothing to log or act on.")
    public void refresh(Object clazz) throws WebloggerException {
        if (clazz == null) {
            return;
        }
        try {
            EntityManager em = getEntityManager(true);
            em.refresh(clazz);
        } catch (Exception ignored) {
            // A refresh of an entity that is no longer managed is a no-op for
            // the caller's purposes; the caller re-reads through the
            // EntityManager either way. Nothing downstream can observe the
            // difference.
        }
    }
    /**
     * Registers a callback to run once the CURRENT unit of work actually
     * commits, i.e. after {@link #flush()}'s {@code commit()} call succeeds
     * -- not immediately.
     *
     * <p>Exists for state cached OUTSIDE the database (the motivating caller
     * is {@code VirtualHostRegistry}): invalidating such a cache the moment
     * a change is made, rather than once it is durable, leaves a window in
     * which a concurrent reader on a different connection can rebuild and
     * cache the cache from the still-uncommitted (pre-change) row -- and
     * with nothing invalidating it again afterward, that stale cache would
     * otherwise never be corrected. Queued rather than run immediately
     * because the actual commit happens later, in {@code flush()}, usually
     * called by the controller after one or more manager calls in the same
     * request.
     */
    public void runAfterCommit(Runnable action) {
        List<Runnable> actions = pendingPostCommitActions.get();
        if (actions == null) {
            actions = new ArrayList<>();
            pendingPostCommitActions.set(actions);
        }
        actions.add(action);
    }

    /**
     * The {@code type} action queued for the CURRENT unit of work, queued
     * from {@code factory} if there is none yet. It runs in {@link #flush()},
     * inside the transaction and just before the commit, in the order the
     * actions were first queued; {@link #release()} discards it.
     *
     * <p>For work that must see, and be serialised against, everything else
     * the transaction wrote: {@code TagAggregateStore} recounts the tags a
     * transaction touched here, so it takes each aggregate row lock once, in
     * one order, after all of the transaction's other writes.
     */
    public <T extends BeforeCommit> T beforeCommit(Class<T> type, Supplier<T> factory) {
        Map<Class<?>, BeforeCommit> actions = pendingPreCommitActions.get();
        if (actions == null) {
            actions = new LinkedHashMap<>();
            pendingPreCommitActions.set(actions);
        }
        return type.cast(actions.computeIfAbsent(type, k -> factory.get()));
    }

    /**
     * Flush changes to the datastore, commit transaction, release em.
     * @throws org.apache.roller.weblogger.WebloggerException on any error
     */
    public void flush() throws WebloggerException {
        // Snapshot and clear BEFORE attempting the commit -- regardless of
        // whether it succeeds, these actions must not survive to leak into
        // whatever this thread does next (a pooled request-handling thread
        // reused for an unrelated later request). They are only actually run
        // below once the commit has genuinely succeeded.
        List<Runnable> actions = pendingPostCommitActions.get();
        pendingPostCommitActions.remove();
        Map<Class<?>, BeforeCommit> preCommit = pendingPreCommitActions.get();
        pendingPreCommitActions.remove();

        try {
            if (preCommit != null) {
                for (BeforeCommit action : preCommit.values()) {
                    action.run();
                }
            }
            EntityManager em = getEntityManager(true);
            em.getTransaction().commit();
        } catch (PersistenceException pe) {
            throw new WebloggerException(pe);
        }

        if (actions != null) {
            for (Runnable action : actions) {
                action.run();
            }
        }
    }
    
    /**
     * Release database session, rolls back any uncommitted changes. Tolerates
     * being called more than once: the {@code Weblogger} bean's {@code
     * destroyMethod="shutdown"} and a caller invoking {@code
     * Weblogger.shutdown()} directly can both reach here for the same
     * instance (see {@link #shutdown()}'s javadoc for the exact dual-
     * invocation), and {@code JPAWebloggerImpl.shutdown()} calls {@code
     * release()} before closing the {@code EntityManagerFactory}, so a second
     * pass lands here against an already-closed factory. Once closed,
     * {@code EntityManagerFactory.createEntityManager()} throws {@code
     * IllegalStateException}, which without this guard was caught below and
     * logged at ERROR on every graceful stop.
     */
    // Not try-with-resources: the EntityManager is acquired inside the guarded
    // block, its failed close is logged at debug (a rollback failure is the
    // error worth reporting), and the thread-local is removed after both.
    @SuppressWarnings("PMD.UseTryWithResources")
    public void release() {
        // Whatever this thread's transaction did or did not commit, any
        // post-commit actions queued via runAfterCommit() and never drained
        // by a flush() call (an exception before the controller reached it,
        // or simply no save this request) must not survive to run against a
        // LATER, unrelated transaction on a thread a pool hands out again.
        pendingPostCommitActions.remove();
        pendingPreCommitActions.remove();

        if (emf == null || !emf.isOpen()) {
            threadLocalEntityManager.remove();
            return;
        }
        EntityManager em = null;
        try {
            em = getEntityManager(false);
            if (isTransactionActive(em)) {
                em.getTransaction().rollback();
            }
        } catch (Exception e) {
            log.error("error during releasing database session", e);
        } finally {
            if (em != null) {
                try {
                    em.close();
                } catch (Exception e) {
                    log.debug("error during closing EntityManager", e);
                }
            }
            threadLocalEntityManager.remove();
        }
    }
    
    /**
     * Store object using an existing transaction.
     * @param obj the object to persist
     * @return the object persisted
     * @throws org.apache.roller.weblogger.WebloggerException on any error
     */
    public Object store(Object obj) throws WebloggerException {
        EntityManager em = getEntityManager(true);
        if (!em.contains(obj)) {
            // If entity is not managed we can assume it is new
            em.persist(obj);
        }
        return obj;
    }
    
    /**
     * Remove object from persistence storage.
     * @param clazz the class of object to remove
     * @param id the id of the object to remove
     * @throws WebloggerException on any error deleting object
     */
    public void remove(Class<?> clazz, String id) throws WebloggerException {
        EntityManager em = getEntityManager(true);
        Object po = em.find(clazz, id);
        em.remove(po);
    }
    
    /**
     * Remove object from persistence storage.
     * @param po the persistent object to remove
     * @throws org.apache.roller.weblogger.WebloggerException on any error
     */
    public void remove(Object po) throws WebloggerException {
        EntityManager em = getEntityManager(true);
        em.remove(po);
    }
    
    /**
     * Remove object from persistence storage.
     * @param pos the persistent objects to remove
     * @throws org.apache.roller.weblogger.WebloggerException on any error
     */
    public void removeAll(Collection<?> pos) throws WebloggerException {
        EntityManager em = getEntityManager(true);
        for (Object obj : pos) {
            em.remove(obj);
        }
    }
    
    /**
     * Retrieve object, no transaction needed.
     * @param clazz the class of object to retrieve
     * @param id the id of the object to retrieve
     * @return the object retrieved
     * @throws WebloggerException on any error retrieving object
     */
    public Object load(Class<?> clazz, String id) throws WebloggerException {
        EntityManager em = getEntityManager(false);
        return em.find(clazz, id);
    }
    
    /**
     * Return true if a transaction is active on the current EntityManager.
     * @param em the persistence manager
     * @return true if the persistence manager is not null and has an active
     *         transaction
     */
    private boolean isTransactionActive(EntityManager em) {
        return em != null && em.getTransaction().isActive();
    }
    
    /**
     * Get the EntityManager associated with the current thread of control.
     * @param isTransactionRequired true if a transaction is begun if not
     * already active
     * @return the EntityManager
     */
    public EntityManager getEntityManager(boolean isTransactionRequired) {
        EntityManager em = getThreadLocalEntityManager();
        if (isTransactionRequired && !em.getTransaction().isActive()) {
            em.getTransaction().begin();
        }
        return em;
    }
    
    /**
     * Get the current ThreadLocal EntityManager
     */
    private EntityManager getThreadLocalEntityManager() {
        EntityManager em = threadLocalEntityManager.get();
        if (em == null) {
            em = emf.createEntityManager();
            threadLocalEntityManager.set(em);
        }
        return em;
    }
    
    /**
     * Get named query that won't commit changes to DB first (FlushModeType.COMMIT)
     * @param queryName the name of the query
     * @throws org.apache.roller.weblogger.WebloggerException on any error
     */
    public Query getNamedQuery(String queryName)
    throws WebloggerException {
        EntityManager em = getEntityManager(false);
        Query q = em.createNamedQuery(queryName);
        // For performance, never flush/commit prior to running queries.
        // Roller code assumes this behavior
        q.setFlushMode(FlushModeType.COMMIT);
        return q;
    }

    /**
     * Get named TypedQuery that won't commit changes to DB first (FlushModeType.COMMIT)
     * Preferred over getNamedQuery(String) due to it being typesafe.
     * @param queryName the name of the query
     * @param resultClass return type of query
     * @throws org.apache.roller.weblogger.WebloggerException on any error
     */
    public <T> TypedQuery<T> getNamedQuery(String queryName, Class<T> resultClass)
            throws WebloggerException {
        EntityManager em = getEntityManager(false);
        TypedQuery<T> q = em.createNamedQuery(queryName, resultClass);
        // For performance, never flush/commit prior to running queries.
        // Roller code assumes this behavior
        q.setFlushMode(FlushModeType.COMMIT);
        return q;
    }

    /**
     * Get named query with default flush mode (usually FlushModeType.AUTO)
     * FlushModeType.AUTO commits changes to DB prior to running statement
     *
     * @param queryName the name of the query
     * @param resultClass return type of query
     * @throws org.apache.roller.weblogger.WebloggerException on any error
     */
    public <T> TypedQuery<T> getNamedQueryCommitFirst(String queryName, Class<T> resultClass)
            throws WebloggerException {
        EntityManager em = getEntityManager(true);
        return em.createNamedQuery(queryName, resultClass);
    }

    /**
     * Create query from queryString that won't commit changes to DB first (FlushModeType.COMMIT)
     * @param queryString the query
     * @throws org.apache.roller.weblogger.WebloggerException on any error
     */
    public Query getDynamicQuery(String queryString)
    throws WebloggerException {
        EntityManager em = getEntityManager(false);
        Query q = em.createQuery(queryString);
        // For performance, never flush/commit prior to running queries.
        // Roller code assumes this behavior
        q.setFlushMode(FlushModeType.COMMIT);
        return q;
    }

    /**
     * Create TypedQuery from queryString that won't commit changes to DB first (FlushModeType.COMMIT)
     * Preferred over getDynamicQuery(String) due to it being typesafe.
     * @param queryString the query
     * @param resultClass return type of query
     * @throws org.apache.roller.weblogger.WebloggerException on any error
     */
    public <T> TypedQuery<T> getDynamicQuery(String queryString, Class<T> resultClass)
            throws WebloggerException {
        EntityManager em = getEntityManager(false);
        TypedQuery<T> q = em.createQuery(queryString, resultClass);
        // For performance, never flush/commit prior to running queries.
        // Roller code assumes this behavior
        q.setFlushMode(FlushModeType.COMMIT);
        return q;
    }

    /**
     * Get named update query with default flush mode (usually FlushModeType.AUTO)
     * FlushModeType.AUTO commits changes to DB prior to running statement
     * @param queryName the name of the query
     * @throws org.apache.roller.weblogger.WebloggerException on any error
     */
    public Query getNamedUpdate(String queryName)
    throws WebloggerException {
        EntityManager em = getEntityManager(true);
        return em.createNamedQuery(queryName);
    }

    /**
     * Native SQL in the current transaction (one is begun if none is
     * active). Default flush mode, so pending changes are written first.
     * For the little JPQL cannot express -- {@code TagAggregateStore}'s
     * {@code INSERT ... ON CONFLICT} and recounts -- and only with constant
     * SQL; values go through parameters.
     * @param sql the statement, never built from input
     */
    public Query getNativeQuery(String sql) {
        EntityManager em = getEntityManager(true);
        return em.createNativeQuery(sql);
    }

    /**
     * Takes a PostgreSQL transaction-scoped advisory lock on {@code key},
     * waiting for whoever holds it; it is released when the current unit of
     * work commits or rolls back ({@link #flush()}, {@link #release()}).
     *
     * <p>It is taken on the connection that will commit, not on a pooled
     * read connection in autocommit, where it would end with the statement:
     * EclipseLink begins the database transaction for a native query run in
     * an active transaction ({@code QueryImpl.executeReadQuery}), once. Do not
     * begin it here as well: {@code UnitOfWork.beginEarlyTransaction} does not
     * check whether it already has, a second call nests the transaction, and
     * the commit then ends only the inner level, leaving the connection idle
     * in transaction and the lock held ({@code EntryAnchorUniquenessTest}
     * pins both: held until the commit, released by it). Nothing is flushed
     * first, so a caller that has not written yet holds no row lock while it
     * waits here.
     *
     * @param key names what is locked; hashed to PostgreSQL's 64-bit lock key
     */
    public void lockUntilCommit(String key) {
        EntityManager em = getEntityManager(true);
        Query lock = em.createNativeQuery(LOCK_UNTIL_COMMIT);
        lock.setFlushMode(FlushModeType.COMMIT);
        lock.setParameter(1, key);
        lock.getSingleResult();
    }

    /**
     * Shut down the EntityManagerFactory. Tolerates being called more than
     * once: the {@code Weblogger} bean's {@code destroyMethod="shutdown"} and
     * a caller invoking {@code Weblogger.shutdown()} directly can both reach
     * here for the same instance, and EclipseLink's {@code EntityManagerFactory.close()}
     * throws {@code IllegalStateException} on a second close. {@link #release()}
     * guards against this same dual invocation, since {@code
     * JPAWebloggerImpl.shutdown()} calls {@code release()} before this method
     * on every pass.
     */
    public void shutdown() {
        if (emf != null && emf.isOpen()) {
            emf.close();
        }
    }
}
