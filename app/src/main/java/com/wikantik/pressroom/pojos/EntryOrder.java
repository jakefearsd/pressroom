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
package com.wikantik.pressroom.pojos;

import java.util.Locale;
import java.util.Optional;

/**
 * The order a blog lists its posts in on its front page and archive pages.
 * Stored as the enum name in {@code weblog.entry_order}.
 */
public enum EntryOrder {
    /** Latest post first: how every blog listed before the setting existed. */
    NEWEST,
    /** Earliest post first. */
    OLDEST;

    /**
     * Reads a stored value leniently: null, blank, an unknown or a lower-case
     * value never fails a page render. Anything but OLDEST is newest first.
     */
    public static EntryOrder of(String stored) {
        return parse(stored).orElse(NEWEST);
    }

    /**
     * Reads submitted input strictly: case-insensitive NEWEST or OLDEST, and
     * empty for anything else so the caller can refuse it.
     */
    public static Optional<EntryOrder> parse(String input) {
        if (input == null) {
            return Optional.empty();
        }
        String name = input.trim().toUpperCase(Locale.ROOT);
        for (EntryOrder order : values()) {
            if (order.name().equals(name)) {
                return Optional.of(order);
            }
        }
        return Optional.empty();
    }
}
