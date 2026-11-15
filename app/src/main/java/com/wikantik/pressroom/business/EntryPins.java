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
package com.wikantik.pressroom.business;

/**
 * The pin limit: a blog has at most {@link #MAX_PER_WEBLOG} pinned posts,
 * counting every one not in the trash (drafts and scheduled posts show once
 * published). One rule for the editor, the API and restore from the trash;
 * the home listing shows the same number.
 */
public final class EntryPins {

    /** The most pinned posts a blog may have, and the most its home page shows. */
    public static final int MAX_PER_WEBLOG = 3;

    private EntryPins() {
    }

    /**
     * Whether saving an entry pinned as {@code requestedPinned} would take
     * its blog past the limit. Only a new pin can: an entry already pinned is
     * one of {@code pinnedInWeblog}, and unpinning only shrinks the count.
     *
     * @param storedPinned    the entry's pin as stored
     * @param requestedPinned the pin the save would store
     * @param pinnedInWeblog  the blog's pinned posts, not trashed
     *                        ({@code WeblogEntryManager.getPinnedEntryCount})
     */
    public static boolean wouldExceed(boolean storedPinned, boolean requestedPinned, long pinnedInWeblog) {
        return requestedPinned && !storedPinned && pinnedInWeblog >= MAX_PER_WEBLOG;
    }
}
