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
package com.wikantik.pressroom.ui.restapi.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.sql.Timestamp;
import java.time.Instant;
import com.wikantik.pressroom.pojos.WeblogRedirect;

/**
 * Views of a redirect rule for the automation API. The hit count and
 * last-hit timestamp ride along on purpose -- they are the observability
 * half of the redirects design, and the API list is where an operator reads
 * them without a database session.
 */
public final class RedirectDtos {

    private RedirectDtos() {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record RedirectView(String id, String source, String target,
            String origin, long hitCount, Instant lastHitAt, Instant createdAt) {
    }

    /** A create. The API can only ever mint MANUAL rules. */
    public record RedirectWrite(String source, String target) {
    }

    public static RedirectView toView(WeblogRedirect rule) {
        return new RedirectView(
                rule.getId(),
                rule.getSourcePath(),
                rule.getTargetPath(),
                rule.getOrigin() == null ? null : rule.getOrigin().name(),
                rule.getHitCount(),
                instant(rule.getLastHitAt()),
                instant(rule.getCreatedAt()));
    }

    private static Instant instant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }
}
