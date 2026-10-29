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


/**
 * Provides access to a Weblogger instance, and owns the answer to "is the
 * business tier up?".
 *
 * <p>This is the bean that replaced the static service locator the codebase
 * used to reach the tier through (see
 * {@code docs/superpowers/specs/2026-08-22-retire-static-service-locator-design.md},
 * Decision 2). Inject it where that question is genuinely a runtime one --
 * the bootstrap filter, the persistence-session filter, the install wizard,
 * the lifecycle bean; everywhere else take a {@code @Lazy Weblogger}.
 */
public interface WebloggerProvider {

    /**
     * True once {@link #bootstrap()} has obtained a Weblogger; false before.
     */
    boolean isBootstrapped();

    /**
     * Trigger bootstrapping: build or locate the business tier, initialize
     * it, and release the bootstrapping thread's persistence session.
     * Idempotent.
     */
    void bootstrap() throws BootstrapException;


    /**
     * Get a Weblogger instance.
     *
     * @throws IllegalStateException if not yet bootstrapped.
     */
    Weblogger getWeblogger();

}
