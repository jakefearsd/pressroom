package org.apache.roller.weblogger.build;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import org.apache.roller.weblogger.config.WebloggerConfig;
import org.apache.roller.weblogger.config.WebloggerRuntimeConfig;
import org.apache.roller.weblogger.pojos.Weblog;
import org.junit.jupiter.api.Test;

/**
 * Self-tests for {@link ClassNameResolver} and {@link ProjectNames}.
 *
 * <p>Fixtures are derived from classes, never written under a literal root,
 * so they hold after the Phase 1 package move (which also drops the
 * {@code weblogger} package level).
 */
class ClassNameResolverTest {

    /** The post-move root, written so no literal here is a qualified name under it. */
    private static final String NEW_ROOT = "com.wikantik.pressroom";

    private static final String ORM_DIR = Weblog.class.getPackageName().replace('.', '/');

    @Test
    void resolvesARealClassPackageAndResource() {
        assertTrue(ClassNameResolver.unresolved(WebloggerConfig.class.getName()).isEmpty());
        assertTrue(ClassNameResolver.unresolved(WebloggerConfig.class.getPackageName()).isEmpty());
        assertTrue(ClassNameResolver.unresolved(ORM_DIR + "/Weblog.orm.xml").isEmpty());
    }

    @Test
    void resolvesMembersAndNestedClasses() {
        assertTrue(ClassNameResolver.unresolved(
                WebloggerRuntimeConfig.class.getName() + ".getProperty").isEmpty());
    }

    @Test
    void reportsATypo() {
        assertTrue(ClassNameResolver.unresolved(WebloggerConfig.class.getName() + "x").isPresent());
    }

    @Test
    void reportsAMissingResource() {
        assertTrue(ClassNameResolver.unresolved(ORM_DIR + "/Nope.orm.xml").isPresent());
    }

    @Test
    void qualifiedNamePatternFindsBothRoots() {
        String root = ProjectNames.currentRoot();
        String text = "a " + ProjectNames.LEGACY_ROOT + ".x.Y, b " + root + ".z.W;";
        Matcher m = ProjectNames.QUALIFIED_NAME.matcher(text);
        List<String> found = new ArrayList<>();
        while (m.find()) {
            found.add(m.group());
        }
        assertEquals(List.of(ProjectNames.LEGACY_ROOT + ".x.Y", root + ".z.W"), found);
    }

    @Test
    void rootRelativeKeysANameTheSameBeforeAndAfterTheMove() {
        String legacy = ProjectNames.LEGACY_ROOT;
        String legacySlashed = legacy.replace('.', '/');
        Set<String> roots = Set.of(NEW_ROOT, legacy);
        assertEquals(Optional.of(".a.B"), ProjectNames.rootRelative(legacy + ".weblogger.a.B", roots));
        assertEquals(Optional.of(".a.B"), ProjectNames.rootRelative(NEW_ROOT + ".a.B", roots));
        assertEquals(Optional.of(".util.C"), ProjectNames.rootRelative(legacy + ".util.C", roots));
        assertEquals(Optional.of("/a/b.xml"),
                ProjectNames.rootRelative(legacySlashed + "/weblogger/a/b.xml", roots));
        assertEquals(Optional.of("/a/b.xml"),
                ProjectNames.rootRelative(NEW_ROOT.replace('.', '/') + "/a/b.xml", roots));
        assertEquals(Optional.of(".A$B"), ProjectNames.rootRelative(legacy + ".weblogger.A$B", roots));
    }

    @Test
    void rootRelativeStripsOnlyWholeSegments() {
        String legacy = ProjectNames.LEGACY_ROOT;
        Set<String> roots = Set.of(NEW_ROOT, legacy);
        // A longer segment is not the dropped level, and a longer root word is not a root.
        assertEquals(Optional.of(".webloggerx.A"), ProjectNames.rootRelative(legacy + ".webloggerx.A", roots));
        assertEquals(Optional.empty(), ProjectNames.rootRelative(legacy + "x.A", roots));
        assertEquals(Optional.empty(), ProjectNames.rootRelative("com.example.A", roots));
    }
}
