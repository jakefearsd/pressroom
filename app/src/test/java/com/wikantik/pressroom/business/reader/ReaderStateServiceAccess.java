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
package com.wikantik.pressroom.business.reader;

import java.time.Clock;
import java.util.function.Supplier;

import com.wikantik.pressroom.business.Weblogger;
import com.wikantik.pressroom.business.jpa.JPAPersistenceStrategy;
import com.wikantik.pressroom.util.GenericThrottle;

/** Builds a {@link ReaderStateService} with a test's write limit, for tests outside this package. */
public final class ReaderStateServiceAccess {

    private ReaderStateServiceAccess() {
    }

    /** The service on the real clock, with {@code writes} as its write limit. */
    public static ReaderStateService service(Weblogger weblogger, Supplier<JPAPersistenceStrategy> strategy,
            GenericThrottle writes) {
        return new ReaderStateService(weblogger, strategy, Clock.systemUTC(), writes);
    }
}
