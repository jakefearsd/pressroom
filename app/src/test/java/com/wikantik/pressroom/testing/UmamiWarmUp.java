package com.wikantik.pressroom.testing;

import org.junit.platform.engine.support.descriptor.ClassSource;
import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestIdentifier;
import org.junit.platform.launcher.TestPlan;

/**
 * When the run includes a {@link UsesUmami} class, starts the Umami container in
 * the background as the run begins. It takes about 12 s to boot, which the tests
 * running meanwhile hide, where it used to be spent in the middle of the run.
 * Registered through {@code META-INF/services/org.junit.platform.launcher.TestExecutionListener}.
 */
public class UmamiWarmUp implements TestExecutionListener {

    @Override
    public void testPlanExecutionStarted(TestPlan testPlan) {
        if (needsUmami(testPlan)) {
            UmamiContainer.warmUp();
        }
    }

    static boolean needsUmami(TestPlan testPlan) {
        for (TestIdentifier root : testPlan.getRoots()) {
            for (TestIdentifier node : testPlan.getDescendants(root)) {
                if (node.getSource().orElse(null) instanceof ClassSource source
                        && source.getJavaClass().isAnnotationPresent(UsesUmami.class)) {
                    return true;
                }
            }
        }
        return false;
    }
}
