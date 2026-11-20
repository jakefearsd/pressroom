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
 * Wraps a piece of work that runs on its own thread so the thread's unit of
 * work (persistence session) is released when it finishes, however it
 * finishes. A Decorator over {@link Runnable}; the {@link Weblogger} is the
 * one the caller already holds, never a static lookup.
 */
public final class UnitOfWork {

    private UnitOfWork() {
    }

    /**
     * @return a runnable that runs {@code work}, then releases the unit of
     *         work in a {@code finally}; an exception from {@code work} propagates
     */
    public static Runnable releasing(Weblogger weblogger, Runnable work) {
        return () -> {
            try {
                work.run();
            } finally {
                weblogger.release();
            }
        };
    }
}
