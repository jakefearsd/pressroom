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

import java.util.List;
import java.util.Optional;

import com.wikantik.pressroom.pojos.WeblogEntry;

/**
 * A post's place in its strand: "Part {@code number} of {@code total}" and the
 * neighbours in the strand (null at either end).
 *
 * @param number   1-based rank among the reader-visible parts
 * @param total    how many reader-visible parts there are
 * @param previous the part before, or null
 * @param next     the part after, or null
 */
public record StrandPlace(int number, int total, WeblogEntry previous, WeblogEntry next) {

    /**
     * The place of {@code entry} among {@code readerVisibleParts} (in strand
     * order), compared by id; empty when it is not one of them.
     */
    public static Optional<StrandPlace> of(List<WeblogEntry> readerVisibleParts, WeblogEntry entry) {
        for (int i = 0; i < readerVisibleParts.size(); i++) {
            if (readerVisibleParts.get(i).getId().equals(entry.getId())) {
                return Optional.of(new StrandPlace(i + 1, readerVisibleParts.size(),
                        i > 0 ? readerVisibleParts.get(i - 1) : null,
                        i + 1 < readerVisibleParts.size() ? readerVisibleParts.get(i + 1) : null));
            }
        }
        return Optional.empty();
    }
}
