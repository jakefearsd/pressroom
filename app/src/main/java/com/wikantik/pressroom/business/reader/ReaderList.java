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
 * The lists {@link ReaderStateService#lists} can build. Each is filtered by
 * {@link ReaderVisibility}.
 */
public enum ReaderList {
    /** Saved entries, newest save first, up to the caller's limit. */
    SAVED,
    /** Followed weblogs, newest follow first, up to the caller's limit. */
    FOLLOWING,
    /** Read entries, most recent read first, up to the caller's limit. */
    HISTORY,
    /** The last {@value ReaderStateService#PLACES} distinct weblogs read, most recent first. */
    PLACES,
    /** The {@value ReaderStateService#CONTINUE} most recently read entries. */
    CONTINUE,
    /** The {@value ReaderStateService#FOLLOWED_POSTS} newest entries across followed weblogs. */
    FOLLOWED_POSTS,
    /** The ids of every entry in the history (at most {@value ReaderStateService#HISTORY_CAP}). */
    READ_IDS
}
