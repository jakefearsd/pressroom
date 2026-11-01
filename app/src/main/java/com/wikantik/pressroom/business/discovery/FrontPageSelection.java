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

import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.pojos.WeblogEntry;

import java.util.List;

/**
 * What the discovery front page shows, section by section. No entry appears
 * in more than one section.
 *
 * @param headline the top-ranked post (the newest with no scores); null only
 *                 when nothing is eligible
 * @param popular "popular right now"
 * @param blogs "explore the blogs", in blog-score order
 * @param different "something different", all random
 * @param allBlogs every eligible weblog, by name ignoring case
 */
public record FrontPageSelection(WeblogEntry headline, List<WeblogEntry> popular, List<BlogCard> blogs,
        List<WeblogEntry> different, List<Weblog> allBlogs) {

    public FrontPageSelection {
        popular = List.copyOf(popular);
        blogs = List.copyOf(blogs);
        different = List.copyOf(different);
        allBlogs = List.copyOf(allBlogs);
    }

    /** Nothing to show. */
    public static FrontPageSelection empty() {
        return new FrontPageSelection(null, List.of(), List.of(), List.of(), List.of());
    }
}
