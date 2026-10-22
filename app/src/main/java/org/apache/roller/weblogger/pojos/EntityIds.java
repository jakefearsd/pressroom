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
package org.apache.roller.weblogger.pojos;

import java.util.function.Supplier;

import org.apache.roller.util.UUIDGenerator;

/**
 * The id an entity starts life with.
 *
 * <p>Every entity initialises its id field from {@link #newId()}, so an object
 * made with {@code new} has a unique random id before anything can read it:
 * it can go into an id-keyed map, build a URL or be wired to other objects
 * before it is flushed.
 *
 * <p>The persistence layer builds objects through the same no-argument
 * constructor to hold rows it loads (and for its unit-of-work and backup
 * clones), and sets the stored id on each straight afterwards. It constructs
 * them inside {@link #materialise}, where {@code newId()} answers null instead
 * of drawing a UUID that would be overwritten at once: drawing one costs a
 * {@code SecureRandom} read under a JVM-wide lock, which profiling put at
 * ~7% of CPU under reader load.
 */
public final class EntityIds {

    private static final ThreadLocal<Boolean> MATERIALISING = new ThreadLocal<>();

    private EntityIds() {
    }

    /**
     * A fresh random UUID, or null while this thread is inside
     * {@link #materialise}.
     */
    public static String newId() {
        return Boolean.TRUE.equals(MATERIALISING.get()) ? null : UUIDGenerator.generateUUID();
    }

    /**
     * Run {@code constructor} with id generation suspended on this thread, for
     * an object whose id the caller sets itself: from a stored row, or (for
     * an entry's revision snapshot) only once it is going to be stored.
     * Generation resumes when it returns or throws.
     */
    public static <T> T materialise(Supplier<T> constructor) {
        Boolean outer = MATERIALISING.get();
        MATERIALISING.set(Boolean.TRUE);
        try {
            return constructor.get();
        } finally {
            if (outer == null) {
                MATERIALISING.remove();
            } else {
                MATERIALISING.set(outer);
            }
        }
    }
}
