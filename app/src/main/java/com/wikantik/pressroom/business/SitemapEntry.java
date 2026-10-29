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

/**
 * The six facts about one published entry that a sitemap needs, read as
 * columns rather than as a
 * {@link com.wikantik.pressroom.pojos.WeblogEntry}: building whole
 * entities (text, summary and every other column) for a document that uses
 * none of them was most of what the sitemap cost. A projection, not an
 * entity, so it lives beside the manager that produces it, not in
 * {@code pojos}.
 *
 * @param anchor        the entry's anchor, from which its permalink is built
 * @param pubTime       publication time; null on a row that never had one
 * @param updateTime    last update time; null on a row that never had one
 * @param noindex       true when the entry asked to be kept out of search
 * @param publicImageId the featured image's id when it names a media file
 *                      in a directory that is not private, otherwise null --
 *                      a dangling id or a private file is never advertised
 * @param canonicalUrl  the entry's stored canonical-URL override, as stored
 *                      (null, blank, or not yet validated); the sitemap
 *                      leaves out an entry canonical to another address
 */
public record SitemapEntry(String anchor, Instant pubTime, Instant updateTime,
        boolean noindex, String publicImageId, String canonicalUrl) {

    /** The time a sitemap reports for the entry: its update, else its publication. */
    public Instant lastmod() {
        return updateTime != null ? updateTime : pubTime;
    }
}
