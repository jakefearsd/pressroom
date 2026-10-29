package org.apache.roller.weblogger.build;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import org.junit.jupiter.api.Test;

/** Self-tests for {@link ClassNameResolver} and {@link ProjectNames}. */
class ClassNameResolverTest {

    private static final String ROOT = ProjectNames.currentRoot();
    private static final String SLASHED = ROOT.replace('.', '/');

    @Test
    void resolvesARealClassPackageAndResource() {
        assertTrue(ClassNameResolver.unresolved(ROOT + ".weblogger.config.WebloggerConfig").isEmpty());
        assertTrue(ClassNameResolver.unresolved(ROOT + ".weblogger.config").isEmpty());
        assertTrue(ClassNameResolver.unresolved(SLASHED + "/weblogger/pojos/Weblog.orm.xml").isEmpty());
    }

    @Test
    void resolvesMembersAndNestedClasses() {
        assertTrue(ClassNameResolver.unresolved(
                ROOT + ".weblogger.config.WebloggerRuntimeConfig.getProperty").isEmpty());
    }

    @Test
    void reportsATypo() {
        assertTrue(ClassNameResolver.unresolved(ROOT + ".weblogger.config.WebloggerConfg").isPresent());
    }

    @Test
    void reportsAMissingResource() {
        assertTrue(ClassNameResolver.unresolved(SLASHED + "/weblogger/pojos/Nope.orm.xml").isPresent());
    }

    @Test
    void qualifiedNamePatternFindsBothRoots() {
        String text = "a " + ProjectNames.LEGACY_ROOT + ".x.Y, b " + ROOT + ".z.W;";
        Matcher m = ProjectNames.QUALIFIED_NAME.matcher(text);
        List<String> found = new ArrayList<>();
        while (m.find()) {
            found.add(m.group());
        }
        // The roots are equal today, so each name matches once; both are found.
        assertEquals(List.of(ProjectNames.LEGACY_ROOT + ".x.Y", ROOT + ".z.W"), found);
    }
}
