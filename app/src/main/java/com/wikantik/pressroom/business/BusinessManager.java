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

import java.util.List;

import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.pojos.Business;
import com.wikantik.pressroom.pojos.Weblog;

/**
 * Stores the shared business records that blogs reference for their
 * structured data and booking links.
 */
public interface BusinessManager {

    /** Every business, ordered by name. */
    List<Business> getBusinesses() throws WebloggerException;

    /** The business with this id, or null if absent. */
    Business getBusiness(String id) throws WebloggerException;

    /**
     * Save a business and touch the lastModified of every weblog that uses
     * it, because rendered pages expire only through weblog.lastModified.
     */
    void saveBusiness(Business business) throws WebloggerException;

    /** Remove a business; throws if any weblog still uses it. */
    void removeBusiness(Business business) throws WebloggerException;

    /** How many weblogs reference this business. */
    long countWeblogsUsing(Business business) throws WebloggerException;

    /**
     * The weblogs that reference this business. After a save, the caller
     * drops their entries from the eager site-wide cache, which (unlike the
     * page and feed caches) ignores weblog.lastModified.
     */
    List<Weblog> getWeblogsUsing(Business business) throws WebloggerException;
}
