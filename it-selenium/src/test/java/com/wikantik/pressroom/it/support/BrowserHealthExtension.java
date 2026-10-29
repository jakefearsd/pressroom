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

package com.wikantik.pressroom.it.support;

import com.codeborne.selenide.Selenide;
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ExtensionContext.Namespace;

/**
 * Gives every test a freshly instrumented browser and, when the test is done, fails it if
 * the browser saw anything broken.
 *
 * <p>Declared once on {@link PressroomIT}, so the whole suite inherits the checks and no
 * individual test has to remember to ask for them.
 *
 * <p><b>The browser is kept for the whole test CLASS, not for one test.</b> It used to be
 * closed in {@link #afterEach}, so a Chrome and a chromedriver were launched and torn down
 * once per test METHOD -- 126 times over a full suite -- and, because the session cookie
 * died with the browser, that in turn forced 21 of the 36 IT classes to sign in from
 * {@code @BeforeEach} rather than {@code @BeforeAll}. The cost was measurable:
 * {@code RouteSweepIT} spent 4.0s per test on a body that opens one URL and asserts one
 * CSS selector.
 *
 * <p>Keeping the PROCESS while resetting the SESSION is what makes that safe, and the two
 * hazards that used to justify a fresh browser are both handled explicitly in
 * {@link BrowserHealth#attach} instead: DevTools listeners are cleared before new ones are
 * registered (they would otherwise accumulate on a reused session and double-count
 * events), and cookies and web storage are cleared over CDP (a surviving login cookie
 * would let a test pass on the previous test's session instead of establishing its own).
 * What is reused is the process; the state is not.
 *
 * <p>Sharing a browser across the methods of one class is consistent with what this suite
 * already declares about itself in {@code junit-platform.properties} -- a class here is "a
 * narrative ... whose methods share fixtures built in {@code @BeforeAll}" and its methods
 * run {@code same_thread}. Selenide's WebDriver is thread-local, so class-parallel workers
 * still get a browser each.
 */
public class BrowserHealthExtension
        implements BeforeEachCallback, AfterEachCallback, AfterAllCallback {

    private static final Namespace NAMESPACE = Namespace.create(BrowserHealthExtension.class);
    private static final String HEALTH_KEY = "browserHealth";

    @Override
    public void beforeEach(ExtensionContext context) {
        // Selenide is configured from here rather than from a static initialiser because the
        // browser is about to be started and the settings only take effect beforehand.
        PressroomIT.configureSelenide();
        context.getStore(NAMESPACE).put(HEALTH_KEY, BrowserHealth.attach());
    }

    @Override
    public void afterEach(ExtensionContext context) {
        BrowserHealth health = context.getStore(NAMESPACE).remove(HEALTH_KEY, BrowserHealth.class);
        try {
            if (health != null) {
                health.settle();
                // Asserted even when the test has already failed: JUnit records this as a
                // suppressed exception rather than replacing the original one, and a broken
                // stylesheet or JavaScript error is frequently the reason the test failed.
                health.assertHealthy();
            }
        } finally {
            // The browser stays up for the next test in this class; BrowserHealth.attach()
            // clears its listeners and its session state on the way in. See the class
            // javadoc for why that replaced closing it here.
            BrowserHealth.detach();
        }
    }

    /**
     * Closes the browser the class has been sharing.
     *
     * <p>Runs on the same thread as the class's tests -- methods here are
     * {@code same_thread} -- which is what {@code Selenide.closeWebDriver()} needs, its
     * driver being thread-local. Selenide also registers a JVM shutdown hook covering
     * every driver it created on any thread, so a class that somehow never reaches this
     * callback still cannot leak a browser past the end of the fork.
     */
    @Override
    public void afterAll(ExtensionContext context) {
        Selenide.closeWebDriver();
    }
}
