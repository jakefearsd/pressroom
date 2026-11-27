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

import java.time.Instant;

import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.pojos.WeblogEntry;
import com.wikantik.pressroom.pojos.WeblogEntry.PubStatus;

/**
 * The visible-entries rule for everything a reader keeps (spec Part E): an
 * entry is visible when it is published with a pubTime that has come, is not
 * in the trash, and sits on a weblog that is active and publicly visible.
 * Drafts, pending and scheduled entries never qualify, nor does anything on a
 * hidden weblog. {@code ReaderStore}'s SQL states the same rule, so a list
 * can be limited in the database; every row is checked here as well. Pure.
 */
public final class ReaderVisibility {

    private ReaderVisibility() {
    }

    /** {@link #isVisible(WeblogEntry, Instant)} now. */
    public static boolean isVisible(WeblogEntry entry) {
        return isVisible(entry, Instant.now());
    }

    /** Whether readers may see {@code entry} at {@code now}. */
    public static boolean isVisible(WeblogEntry entry, Instant now) {
        return entry != null
                && entry.getStatus() == PubStatus.PUBLISHED
                && entry.getPubTime() != null
                && !entry.getPubTime().toInstant().isAfter(now)
                && entry.getTrashedAt() == null
                && isVisible(entry.getWebsite());
    }

    /** Whether readers may see {@code weblog}: active and publicly visible. */
    public static boolean isVisible(Weblog weblog) {
        return weblog != null
                && Boolean.TRUE.equals(weblog.getActive())
                && Boolean.TRUE.equals(weblog.getVisible());
    }
}
