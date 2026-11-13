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

import java.util.Date;

/**
 * How much a blog has published: its published, not-future posts and the
 * newest one's publication time, for the blog line under a post's title
 * ({@code $model.blogStanding}). {@code latest} is null on a blog with none.
 *
 * <p>A record, but read by Velocity, which does not see record accessors:
 * the JavaBean getters below are what a template reads.
 */
public record BlogStanding(long articles, Date latest) {

    /** A blog with nothing published, and the value when the count could not be read. */
    public static final BlogStanding NONE = new BlogStanding(0, null);

    public long getArticles() {
        return articles;
    }

    public Date getLatest() {
        return latest;
    }
}
