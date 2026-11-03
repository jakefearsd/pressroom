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

package com.wikantik.pressroom.ui.rendering.util.cache;

import com.wikantik.pressroom.util.cache.CachedContent;

/**
 * A rendered page as the page caches hold it: the content and the
 * {@link com.wikantik.pressroom.util.DiscoveryGeneration} its render read.
 * The generation is compared on its own rather than folded into the weblog's
 * stamp: as the larger of the two, a reshuffle dated before the weblog's last
 * change was invisible to a page rendered from the old snapshot.
 */
record RenderedPage(CachedContent content, long generation) {

    /**
     * The content of {@code cached}, or null when there is none or it was
     * rendered from an older generation than {@code current}.
     */
    static CachedContent servable(Object cached, long current) {
        return cached instanceof RenderedPage page && page.generation() >= current ? page.content() : null;
    }
}
