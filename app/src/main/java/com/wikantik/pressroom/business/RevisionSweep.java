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

import java.time.Instant;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * What the revision sweep did or would do, and what the revision store
 * holds: the figures an operator reads before setting a limit (spec
 * 2026-11-15, Part 3).
 */
public interface RevisionSweep {

    /** Whose revisions an item's are. */
    enum ItemKind { ENTRY, PAGE }

    /**
     * One sweep. Removed (or, for a dry run, would remove) {@code byCount}
     * revisions beyond an item's count, {@code byAge} older than the age
     * limit, and {@code byCeiling} to bring the site under its ceiling.
     *
     * @param ceilingUnmeetable the ceiling is lower than what the protected
     *        revisions (each item's newest five, and kept ones) come to, so
     *        the total is still above it
     * @param weblogsFailed weblogs whose pass failed and so lost nothing
     * @param ceilingFailed the ceiling pass failed and removed nothing
     */
    record Report(Instant at, boolean dryRun, long byCount, long byAge, long byCeiling,
                         boolean ceilingUnmeetable, int weblogsFailed, boolean ceilingFailed) {

        /** Also a property of the JSON form, so a client need not add the parts up. */
        @JsonProperty("total")
        public long total() {
            return byCount + byAge + byCeiling;
        }

        /**
         * Whether part of the sweep could not run. For a dry run this means
         * the figures are a floor, not a prediction: "could not work it out"
         * is never "nothing will be removed".
         */
        @JsonProperty("incomplete")
        public boolean incomplete() {
            return weblogsFailed > 0 || ceilingFailed;
        }
    }

    /** An item and how many revisions it holds. A post's title is stored escaped, a page's raw. */
    record ItemCount(ItemKind kind,
                     String id, String title, long revisions) {
    }

    /**
     * The store as it stands. {@code lastSweep} is null until one has run
     * since the application started; {@code nextSweep} is a dry run at the
     * current settings.
     */
    record Stats(long entryRevisions, long pageRevisions, long kept, long approximateBytes,
                        Instant oldest, List<ItemCount> largest, Report lastSweep, Report nextSweep) {

        /** Also a property of the JSON form, so a client need not add the parts up. */
        @JsonProperty("total")
        public long total() {
            return entryRevisions + pageRevisions;
        }
    }
}
