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

/**
 * Why a save, follow or read was not stored. Thrown as one of the shared
 * instances below, so a caller compares it by identity; it carries no stack
 * trace, because a refusal is an answer, not a failure.
 */
public final class ReaderStateRefusal extends Exception {

    private static final long serialVersionUID = 1L;

    /**
     * The entry or weblog is unknown, or not visible to readers
     * ({@link ReaderVisibility}). Surfaces answer it as not found.
     */
    public static final ReaderStateRefusal NOT_VISIBLE = new ReaderStateRefusal("not visible");

    private ReaderStateRefusal(String reason) {
        super(reason, null, false, false);
    }
}
