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
package com.wikantik.pressroom.business;

import com.wikantik.pressroom.WebloggerException;

/**
 * Refuses to mark one more revision of an item as kept: kept revisions are
 * exempt from every retention rule, so their number per item is bounded
 * ({@code RevisionRetention.MAX_KEPT}), or "kept" would be a way round every
 * limit.
 */
public class RevisionKeepLimitException extends WebloggerException {

    private static final long serialVersionUID = 1L;

    public RevisionKeepLimitException() {
        super("At most 10 kept revisions for one item.");
    }
}
