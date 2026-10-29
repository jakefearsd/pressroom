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

import com.wikantik.pressroom.WebloggerException;

/**
 * A save gave up waiting for its weblog's anchor lock: another save of the
 * same weblog held it longer than {@code weblogentry.anchor.lockTimeoutSeconds}.
 * The save's transaction is rolled back, and nothing about the entry is
 * wrong, so it is transient: the same request is expected to succeed when
 * retried. Callers that answer a client (the API, MCP) can say so instead of
 * reporting an internal error.
 */
public class AnchorLockTimeoutException extends WebloggerException {

    private static final long serialVersionUID = 1L;

    private final String weblogHandle;
    private final int timeoutSeconds;

    public AnchorLockTimeoutException(String weblogHandle, int timeoutSeconds) {
        super("Timed out after " + timeoutSeconds + "s waiting to claim an anchor in weblog "
                + weblogHandle + ": another save of the weblog holds the lock");
        this.weblogHandle = weblogHandle;
        this.timeoutSeconds = timeoutSeconds;
    }

    /** Handle of the weblog whose anchor lock was not granted in time. */
    public String getWeblogHandle() {
        return weblogHandle;
    }

    /** How long the save waited, in seconds; a client may retry after about this long. */
    public int getTimeoutSeconds() {
        return timeoutSeconds;
    }
}
