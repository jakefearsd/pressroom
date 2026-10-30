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

import com.wikantik.pressroom.business.jpa.WebloggerBeanConfig;
import com.wikantik.pressroom.business.startup.WebloggerStartup;
import com.wikantik.pressroom.config.WebloggerRuntimeConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.stereotype.Component;

/**
 * A Spring specific implementation of a WebloggerProvider, and the bean
 * that answers "is the business tier up?" for everything that needs to know
 * (the bootstrap sites, the filters ahead of the security chain, the
 * interceptor). There is no static equivalent: a class that needs the tier
 * injects {@link Weblogger} or this provider.
 *
 * <p>Bootstrapping builds (or, for the {@link ApplicationContext} constructor,
 * reuses) a Spring context and hands back its {@link Weblogger} bean. It owns
 * the whole startup sequence the two bootstrap sites ({@code PressroomLifecycle}
 * and {@code InstallController}) used to spell out by hand: the
 * prepare-before-construct guard, obtaining the bean, {@code initialize()},
 * and {@code release()} of the bootstrapping thread's persistence session.
 *
 * <p>Production gets the {@code @Component} (constructed with the Boot
 * context, into which {@link WebloggerBeanConfig} is already component-
 * scanned). {@code TestUtils} uses {@link #standalone()}, which builds a
 * private context from {@link WebloggerBeanConfig} on first bootstrap.
 */
@Component
public class SpringWebloggerProvider implements WebloggerProvider, DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(SpringWebloggerProvider.class);

    private final ApplicationContext existingContext;

    // Deliberately left null by both constructors -- see standalone()'s
    // javadoc. bootstrap() must run after WebloggerStartup.prepare(), and
    // only the caller's ordering guarantees that; building the context in the
    // constructor would risk running before prepare() and is the specific
    // mistake this design avoids.
    private ApplicationContext context;

    private volatile Weblogger weblogger;
    private final Object bootstrapLock = new Object();

    /**
     * Instantiate a provider around an already-built application context
     * (the webapp's Spring context, which already imports
     * {@link WebloggerBeanConfig}). This is the injection constructor.
     *
     * @param existingContext the application context to source the Weblogger bean from.
     */
    @Autowired
    public SpringWebloggerProvider(ApplicationContext existingContext) {
        this.existingContext = existingContext;
    }

    private SpringWebloggerProvider() {
        this.existingContext = null;
    }

    /**
     * A provider that builds its own application context from
     * {@link WebloggerBeanConfig} -- the test suite's path.
     *
     * <p>The context is not built here: {@link WebloggerStartup#prepare()}
     * must run first (it prepares the {@code DatabaseProvider} that
     * {@code WebloggerBeanConfig} reads), and that ordering is only
     * guaranteed by the caller invoking {@link #bootstrap()} afterward.
     */
    public static SpringWebloggerProvider standalone() {
        return new SpringWebloggerProvider();
    }

    @Override
    public boolean isBootstrapped() {
        return weblogger != null;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Idempotent: a repeat call on the same instance neither builds a
     * second (leaked) self-owned context nor initializes the tier again.
     *
     * <p>The tier counts as bootstrapped from the moment the Weblogger bean
     * has been obtained -- before {@code initialize()} runs, and even if
     * {@code initialize()} then fails. That is the shape the two bootstrap
     * sites always had (the provider was installed, then initialized), so
     * {@code BootstrapFilter} lets requests through in both cases; an
     * {@code initialize()} failure surfaces as a {@link BootstrapException}
     * for the caller to log.
     */
    @Override
    public void bootstrap() throws BootstrapException {
        // A private lock rather than synchronized(this): standalone() hands the
        // instance out, so its intrinsic lock is reachable by any caller
        // (SpotBugs USO_UNSAFE_METHOD_SYNCHRONIZATION).
        synchronized (bootstrapLock) {
            bootstrapLocked();
        }
    }

    private void bootstrapLocked() throws BootstrapException {
        if (weblogger != null) {
            return;
        }

        // if the app hasn't been properly started so far then bail
        if (!WebloggerStartup.isPrepared()) {
            throw new IllegalStateException("Cannot bootstrap until application has been properly prepared");
        }

        log.info("Bootstrapping Pressroom Weblogger business tier");

        if (this.context == null) {
            this.context = (existingContext != null)
                    ? existingContext
                    : new AnnotationConfigApplicationContext(WebloggerBeanConfig.class);
        }

        Weblogger built = context.getBean(Weblogger.class);
        if (built == null) {
            throw new BootstrapException("Bootstrapping failed, Weblogger instance is null");
        }
        this.weblogger = built;

        // The runtime-config facade reads through the attached manager from
        // here on (spec Decision 8 of the 2026-08-22 plan): attached before
        // initialize() so nothing inside it can regress to a null read.
        WebloggerRuntimeConfig.attach(built.getPropertiesManager());

        log.info("Pressroom Weblogger business tier successfully bootstrapped");
        log.info("   Version: {}", built.getVersion());
        log.info("   Revision: {}", built.getRevision());

        try {
            built.initialize();
        } catch (InitializationException ex) {
            throw new BootstrapException("Pressroom Weblogger initialization failed", ex);
        } finally {
            built.release();
        }
    }

    /**
     * Context close: the runtime-config facade must not keep answering from a
     * tier that is being torn down -- but only the manager THIS provider
     * attached is cleared, so a context that never bootstrapped (or a second
     * tier in the same JVM, as the test suite has) cannot detach someone
     * else's. Only the Boot-managed instance is a bean; the standalone test
     * provider lives as long as its JVM.
     */
    @Override
    public void destroy() {
        Weblogger built = weblogger;
        if (built != null) {
            WebloggerRuntimeConfig.detach(built.getPropertiesManager());
        }
    }

    @Override
    public Weblogger getWeblogger() {
        Weblogger current = weblogger;
        if (current == null) {
            throw new IllegalStateException("Pressroom Weblogger has not been bootstrapped yet");
        }
        return current;
    }

}
