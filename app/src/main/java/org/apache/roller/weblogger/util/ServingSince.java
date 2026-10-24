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
package org.apache.roller.weblogger.util;

/**
 * When this server began serving, and the rule that dates what it renders.
 *
 * <p>A weblog's rendered pages, feeds and stylesheet change when the weblog
 * does, and also when the code rendering them does: a deploy brings new
 * themes and templates without touching any weblog. So a rendering is as new
 * as the weblog's last change or this server's start, whichever is later.
 * Dated by the weblog alone, a reader's copy from before a deploy was
 * answered 304 and kept.
 */
public final class ServingSince {

    // Loaded on first use, which is after the deploy and before anything this
    // server rendered went out.
    private static final long STARTED = System.currentTimeMillis();

    private ServingSince() {
    }

    /** When this server started serving, in epoch milliseconds. */
    public static long started() {
        return STARTED;
    }

    /** {@code changed}, or when this server started if that is later. */
    public static long atLeast(long changed) {
        return Math.max(changed, STARTED);
    }
}
