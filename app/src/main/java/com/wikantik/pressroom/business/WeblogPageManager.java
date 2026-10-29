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
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.pojos.WeblogPage;

/**
 * Interface to static page management.
 *
 * <p>A {@link WeblogPage} is content outside the entry chronology, addressed
 * by a slug that is unique within its weblog.
 */
public interface WeblogPageManager {

    /**
     * Store a page.
     *
     * <p>Refuses a blank slug, a slug containing '/', and any slug in
     * ReservedSlugs -- a page that cannot be routed to is not a page.
     */
    void savePage(WeblogPage page) throws WebloggerException;

    /**
     * Remove a page.
     */
    void removePage(WeblogPage page) throws WebloggerException;

    /**
     * Look a page up by its id. Returns null when no page carries the id.
     */
    WeblogPage getPage(String id) throws WebloggerException;

    /**
     * Look a page up by its weblog and slug. Returns null when the weblog has
     * no page with that slug.
     */
    WeblogPage getPageBySlug(Weblog weblog, String slug) throws WebloggerException;

    /**
     * All pages belonging to a weblog, in nav order.
     */
    List<WeblogPage> getPages(Weblog weblog) throws WebloggerException;

    /**
     * The published pages belonging to a weblog, in nav order.
     */
    List<WeblogPage> getPublishedPages(Weblog weblog) throws WebloggerException;

    /**
     * Remove all pages belonging to a weblog.
     */
    void removePages(Weblog weblog) throws WebloggerException;
}
