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
package com.wikantik.pressroom.business.discovery;

import java.time.Instant;

/**
 * How the last popularity refresh went, for the admin screens.
 *
 * @param state what the last refresh achieved
 * @param lastAttempt when a refresh last ran; null before the first
 * @param lastSuccess when traffic was last read successfully; null if never
 * @param scoredEntries candidates that carry a score in the current snapshot
 * @param candidates eligible entries in the current snapshot
 * @param lastError why the last refresh failed; null unless {@code state} is FAILED
 */
public record DiscoveryStatus(State state, Instant lastAttempt, Instant lastSuccess,
        int scoredEntries, int candidates, String lastError) {

    /** What the last refresh achieved. */
    public enum State {
        /** No traffic source is configured: every pick is random. */
        DISABLED,
        /** No refresh has run yet. */
        NEVER,
        /** The last refresh read traffic. */
        OK,
        /** The last refresh failed; the previous scores are kept. */
        FAILED
    }
}
