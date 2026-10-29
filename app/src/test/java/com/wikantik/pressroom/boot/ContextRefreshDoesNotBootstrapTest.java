/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.wikantik.pressroom.boot;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import com.wikantik.pressroom.business.jpa.WebloggerBeanConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.TypeExcludeFilter;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.type.classreading.MetadataReader;
import org.springframework.core.type.classreading.MetadataReaderFactory;

/**
 * Context refresh must not build the business tier.
 *
 * <p>Characterisation for the {@code @Lazy} scheme (spec Decision 2, 2026-08-22
 * "retire the static service locator"): {@code WebloggerBeanConfig} is
 * class-level {@code @Lazy}, and every injection point for the facade or a
 * manager outside that config carries {@code @Lazy}, so that Spring can wire
 * controllers, servlets, filters and interceptors at refresh while the
 * business graph -- which needs {@code WebloggerStartup.prepare()} to have
 * run, a real database, and {@code initialize()} -- is built only when
 * {@code WebloggerProvider.bootstrap()} asks for it inside
 * {@code PressroomLifecycle.start()}. Expected to pass on arrival; it turns red
 * the moment a future injection point forgets {@code @Lazy}, because that
 * point's bean would then pull the graph at refresh, before anything has
 * prepared it.
 *
 * <p>Why it starts the real Boot application and not a hand-built context:
 * the invariant is about what the <em>production</em> wiring instantiates at
 * refresh, and only the production wiring knows every injection point. The
 * lifecycle is switched off ({@code roller.lifecycle.enabled=false}, the
 * {@code @ConditionalOnProperty} on {@code PressroomLifecycle}) so nothing
 * legitimately bootstraps; the connector and the management port bind to
 * ephemeral/disabled ports so the test cannot collide with a dev server.
 * {@code PressroomTestBootstrap} has already pointed {@code WebloggerConfig} at
 * the Testcontainers database for the JVM, so static config loads -- and,
 * with nothing prepared, any premature ask for the tier fails loudly inside
 * {@code WebloggerBeanConfig.databaseProvider()} rather than passing
 * silently.
 *
 * <p>Not {@code @SpringBootTest}: see {@code SecurityConfigTest}'s javadoc --
 * {@code SpringExtension} does not load under this repo's pinned JUnit 5.14.
 * A plain {@code SpringApplicationBuilder} in a {@code @Test} needs nothing
 * past the JUnit 5 API.
 */
class ContextRefreshDoesNotBootstrapTest {

    /**
     * {@code scanBasePackages = "com.wikantik.pressroom"} also sweeps the
     * test classpath, where several tests carry their own nested
     * {@code @Configuration} ({@code EntriesApiDispatchTest.TestConfig},
     * {@code ApiScopeInterceptorDispatchTest.TestConfig}, ...) that would
     * collide on bean names. Boot's {@code @SpringBootApplication} scan honours
     * any {@code TypeExcludeFilter} bean, so one that drops everything loaded
     * from {@code test-classes} keeps this a scan of production sources only.
     */
    private static final TypeExcludeFilter EXCLUDE_TEST_CLASSES = new TypeExcludeFilter() {
        @Override
        public boolean match(MetadataReader metadataReader, MetadataReaderFactory factory)
                throws IOException {
            return metadataReader.getResource().getURL().toString().contains("/test-classes/");
        }
    };

    @Test
    void refreshingTheBootContextInstantiatesNoBusinessTierBean() {
        SpringApplicationBuilder builder = new SpringApplicationBuilder(PressroomApplication.class)
                .web(WebApplicationType.SERVLET)
                .initializers(ctx -> ctx.getBeanFactory()
                        .registerSingleton("excludeTestClasses", EXCLUDE_TEST_CLASSES));

        // Command-line arguments, not builder.properties(): those are default
        // properties, the lowest precedence, so application.properties'
        // server.port=8080 and management.server.port=8090 beat them and the
        // test bound the real ports -- failing on any machine with 8080 taken.
        try (ConfigurableApplicationContext ctx = builder.run(
                "--roller.lifecycle.enabled=false",
                "--server.port=0",
                "--management.server.port=-1",
                "--spring.main.banner-mode=off")) {
            ConfigurableListableBeanFactory beans = ctx.getBeanFactory();

            String[] configNames = ctx.getBeanNamesForType(WebloggerBeanConfig.class);
            assertTrue(configNames.length == 1,
                    "expected exactly one WebloggerBeanConfig in the context, saw "
                            + List.of(configNames));
            String configBean = configNames[0];

            List<String> tierBeans = new ArrayList<>();
            List<String> instantiated = new ArrayList<>();
            for (String name : beans.getBeanDefinitionNames()) {
                if (configBean.equals(beans.getBeanDefinition(name).getFactoryBeanName())) {
                    tierBeans.add(name);
                    if (beans.containsSingleton(name)) {
                        instantiated.add(name);
                    }
                }
            }

            assertTrue(tierBeans.size() >= 15,
                    "sanity: expected the business-tier bean definitions to be visible, saw "
                            + tierBeans);
            assertFalse(beans.containsSingleton(configBean),
                    "WebloggerBeanConfig itself was instantiated at refresh; its class-level "
                            + "@Lazy is what keeps the tier unbuilt until bootstrap()");
            assertTrue(instantiated.isEmpty(),
                    "context refresh built business-tier beans " + instantiated
                            + " before anything could have prepared or bootstrapped the tier. "
                            + "Some injection point for Weblogger or a manager is missing @Lazy "
                            + "(or an eager bean is calling into the tier at construction). "
                            + "Find the consumer of the first name listed and fix it there.");
        }
    }
}
