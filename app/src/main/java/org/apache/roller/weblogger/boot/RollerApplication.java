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
package org.apache.roller.weblogger.boot;

import org.apache.roller.weblogger.WebloggerException;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.support.SpringBootServletInitializer;

/**
 * Roller manages its own EntityManagerFactory (EclipseLink, via
 * JPAPersistenceStrategy) and its own connections (DatabaseProvider), so
 * Boot's JPA and DataSource auto-configuration are excluded.
 *
 * <p>Spring Boot 4 split the old monolithic {@code spring-boot-autoconfigure}
 * jar into per-feature modules. {@code DataSourceAutoConfiguration} now lives
 * in {@code org.springframework.boot.jdbc.autoconfigure} (artifact
 * {@code spring-boot-jdbc}) and {@code HibernateJpaAutoConfiguration} in
 * {@code org.springframework.boot.hibernate.autoconfigure} (artifact
 * {@code spring-boot-hibernate}); neither artifact is on this project's
 * classpath (no {@code spring-boot-starter-jdbc} / {@code -data-jpa}), so
 * {@code excludeName} (string-based) is used instead of {@code exclude}
 * (class-based) — it does not require the class to be resolvable at compile
 * time, and stays a guard against a future dependency pulling either module
 * in transitively.
 *
 * <p>Spring Security is configured entirely by
 * {@code org.apache.roller.weblogger.boot.SecurityConfig} (component-scanned,
 * per {@code scanBasePackageClasses} below) -- there is no more
 * {@code @ImportResource("classpath:security.xml")} here: that annotation was
 * temporary scaffolding for the Stage 1B Task 3 -> Task 4 window, loading the
 * still-XML security configuration Task 3 could not fully port (Spring
 * Security 7.1 dropped the {@code AccessDecisionManager}/
 * {@code SecurityMetadataSource} classes {@code security.xml}'s original
 * form relied on). Task 4 deleted both the XML file and this annotation.
 */
@SpringBootApplication(
        // WebloggerException stands for the root package, which holds every scanned subpackage.
        scanBasePackageClasses = WebloggerException.class,
        excludeName = {
                "org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration",
                "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration"
        })
public class RollerApplication extends SpringBootServletInitializer {

    public static void main(String[] args) {
        SpringApplication.run(RollerApplication.class, args);
    }

    @Override
    protected SpringApplicationBuilder configure(SpringApplicationBuilder builder) {
        return builder.sources(RollerApplication.class);
    }
}
