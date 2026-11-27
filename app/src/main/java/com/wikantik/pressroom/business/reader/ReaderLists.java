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

import java.util.List;
import java.util.Set;

import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.pojos.WeblogEntry;

/**
 * One reader's lists, as {@link ReaderStateService#lists} built them. A list
 * that was not asked for is empty. Entries and weblogs are managed, so they
 * belong to the request that built them.
 *
 * @param saved           {@link ReaderList#SAVED}
 * @param following       {@link ReaderList#FOLLOWING}
 * @param history         {@link ReaderList#HISTORY}
 * @param places          {@link ReaderList#PLACES}
 * @param continueReading {@link ReaderList#CONTINUE}
 * @param followedPosts   {@link ReaderList#FOLLOWED_POSTS}
 * @param readIds         {@link ReaderList#READ_IDS}, most recent read first
 */
public record ReaderLists(List<WeblogEntry> saved, List<Weblog> following, List<WeblogEntry> history,
        List<Weblog> places, List<WeblogEntry> continueReading, List<WeblogEntry> followedPosts,
        Set<String> readIds) {
}
