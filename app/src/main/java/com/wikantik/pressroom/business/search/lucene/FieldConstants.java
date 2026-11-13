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
/* Created on Jul 19, 2003 */
package com.wikantik.pressroom.business.search.lucene;


/**
 * Field constants for indexing blog entries.
 *
 * @author Mindaugas Idzelis (min@idzelis.com)
 */
public final class FieldConstants {

    private FieldConstants() {
    }

    public static final String ANCHOR = "anchor";
    public static final String UPDATED = "updated";
    public static final String ID = "id";
    public static final String CATEGORY = "cat";
    public static final String TITLE = "title";
    public static final String PUBLISHED = "published";
    public static final String CONTENT = "content";
    /** Entry summary, plain text, indexed not stored. */
    public static final String SUMMARY = "summary";
    /** Tag names, space-joined, indexed not stored. */
    public static final String TAGS = "tags";
    /** Category name as text (the keyword {@link #CATEGORY} stays for the filter). */
    public static final String CATEGORY_TEXT = "cattext";
    /** Weblog name and tagline, indexed not stored. */
    public static final String BLOG = "blog";
    /** Keyword {@link #SITE_V}, present only for a post {@code DiscoveryEligibility} accepts at index time. */
    public static final String SITE = "site";
    public static final String SITE_V = "1";
    /** Publish time in epoch millis, a numeric doc value for the recency factor. */
    public static final String PUBLISHED_NUM = "pubnum";
    public static final String CONSTANT = "constant";
    // CONSTANT_V must be lowercase or match transform rules of analyzer
    public static final String CONSTANT_V = "v";
    public static final String WEBSITE_HANDLE = "handle";
    public static final String LOCALE = "locale";
}
