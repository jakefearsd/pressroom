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

import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code main(String[])} is deliberately not exercised here -- it hands
 * control straight to {@code SpringApplication.run}, which would try to
 * start the whole application (business tier, embedded Tomcat, and all).
 * That path is what the live smoke tests recorded in the Task 1/2/2b reports
 * cover ({@code java -jar}, {@code spring-boot:run}). What's left --
 * instantiating the class and the {@code SpringApplicationServletInitializer}
 * override -- is cheap to pin directly.
 */
class PressroomApplicationTest {

    @Test
    void configureRegistersItselfAsTheSpringApplicationSource() {
        PressroomApplication application = new PressroomApplication();
        SpringApplicationBuilder builder = new SpringApplicationBuilder();

        SpringApplicationBuilder returned = application.configure(builder);

        assertSame(builder, returned,
                "configure() must return the same builder it was handed, just with a source added");
        // build() (not application(), which reflects only the builder's
        // initial state) is what finalizes the accumulated .sources(...)
        // calls onto the underlying SpringApplication -- same call
        // SpringApplicationBuilder.run() makes internally before starting.
        assertTrue(returned.build().getAllSources().contains(PressroomApplication.class),
                "configure() must register PressroomApplication itself as a source, matching "
                        + "SpringApplication.run(PressroomApplication.class, args) in main()");
    }
}
