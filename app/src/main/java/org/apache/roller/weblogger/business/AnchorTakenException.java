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
package org.apache.roller.weblogger.business;

import org.apache.roller.weblogger.WebloggerException;
import org.apache.roller.weblogger.pojos.WeblogEntry.PubStatus;

/**
 * A save asked for an entry anchor another entry of the same weblog already
 * holds. Thrown by {@code saveWeblogEntry} only for an anchor the caller
 * chose; one Roller generates always takes the next free suffix instead.
 */
public class AnchorTakenException extends WebloggerException {

    private static final long serialVersionUID = 1L;

    private final String anchor;
    private final PubStatus holderStatus;

    public AnchorTakenException(String anchor, PubStatus holderStatus) {
        super("Anchor '" + anchor + "' is already held by another entry of the weblog");
        this.anchor = anchor;
        this.holderStatus = holderStatus;
    }

    public String getAnchor() {
        return anchor;
    }

    /** The holding entry's status: a TRASHED holder frees the anchor only when deleted forever. */
    public PubStatus getHolderStatus() {
        return holderStatus;
    }
}
