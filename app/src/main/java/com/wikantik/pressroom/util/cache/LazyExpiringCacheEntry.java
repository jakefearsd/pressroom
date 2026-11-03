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

package com.wikantik.pressroom.util.cache;

import java.io.Serializable;


/**
 * A cache entry that expires lazily: it is stored with the basis it was built
 * from -- a monotonic stamp such as the weblog's {@code lastModified} as the
 * render read it -- and each read passes the stamp that is current now. An
 * entry whose basis is older than that is not served.
 *
 * <p>Nothing has to hunt through the cache for the entries of a weblog that
 * changed; they are passed over as they are asked for.
 *
 * <p>The basis is what the work READ, never when it finished: an entry
 * stamped at its put let a render that read old data before an author's save,
 * and was stored after it, pass as fresh (code review, 2026-11-03).
 */
public class LazyExpiringCacheEntry implements Serializable {

    private static final long serialVersionUID = 1L;

    private final transient Object value;
    private final long basis;


    /**
     * @param item  what to cache
     * @param basis the stamp the item was built from, taken before building
     *              it read anything
     */
    public LazyExpiringCacheEntry(Object item, long basis) {
        this.value = item;
        this.basis = basis;
    }


    /**
     * The value, or null when it was built from a basis older than
     * {@code current}.
     */
    public Object getValue(long current) {
        if(this.isInvalid(current)) {
            return null;
        } else {
            return this.value;
        }
    }


    /**
     * Whether the entry was built from a basis older than {@code current}.
     * Equal is current: the stamps say what the build read, so there is no
     * same-millisecond ambiguity to resolve.
     */
    public boolean isInvalid(long current) {
        return this.basis < current;
    }


    public long getBasis() {
        return basis;
    }

}
