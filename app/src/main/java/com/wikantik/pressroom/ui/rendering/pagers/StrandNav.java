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

package com.wikantik.pressroom.ui.rendering.pagers;

import com.wikantik.pressroom.pojos.wrapper.WeblogEntryWrapper;
import com.wikantik.pressroom.pojos.wrapper.WeblogPageWrapper;

/**
 * What a post in a strand shows in place of the blog's Older/Newer: its strand
 * page, its "Part {@code number} of {@code total}" place, and the parts either
 * side of it (null at either end).
 *
 * @param strand   the strand page
 * @param number   1-based rank among the reader-visible parts
 * @param total    how many reader-visible parts there are
 * @param previous the part before, or null
 * @param next     the part after, or null
 */
public record StrandNav(WeblogPageWrapper strand, int number, int total,
        WeblogEntryWrapper previous, WeblogEntryWrapper next) {
}
