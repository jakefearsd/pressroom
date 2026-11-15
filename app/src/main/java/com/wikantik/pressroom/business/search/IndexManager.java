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
package com.wikantik.pressroom.business.search;

import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.business.InitializationException;
import com.wikantik.pressroom.business.URLStrategy;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.pojos.WeblogEntry;
import com.wikantik.pressroom.pojos.WeblogPage;

/**
 * Interface to Pressroom's full-text search facility.
 * @author Dave Johnson
 */
public interface IndexManager {

    /**
     * Initialize the search system.
     * @throws InitializationException If there is a problem during initialization.
     */
    void initialize() throws InitializationException;

    /** Shutdown to be called on application shutdown */
    void shutdown();

    /**
     * Release all resources associated with Pressroom session.
     */
    void release();

    /** Does index need to be rebuilt */
    boolean isInconsistentAtStartup();

    /** Add entry to index, returns immediately and operates in background */
    void addEntryIndexOperation(WeblogEntry entry) throws WebloggerException;
    
    /** Re-index entry, returns immediately and operates in background */
    void addEntryReIndexOperation(WeblogEntry entry) throws WebloggerException;

    void rebuildWeblogIndex(Weblog weblog) throws WebloggerException;

    void rebuildWeblogIndex() throws WebloggerException;

    /** Remove weblog from index, returns immediately and operates in background */
    void removeWeblogIndex(Weblog weblog) throws WebloggerException;

    /** Remove entry from index, returns immediately and operates in background */
    void removeEntryIndexOperation(WeblogEntry entry) throws WebloggerException;

    /**
     * Notes a saved blog. Its name and tagline are searchable on each of its
     * posts, so a blog seen before whose name or tagline has changed has its
     * posts reindexed (in the background); the first sighting of a blog only
     * records it. Cheap, and never throws: it runs on every blog save.
     */
    void noteWeblog(Weblog weblog);

    /**
     * Runs a reader's search: ranked by relevance, all of the words first and
     * then any of them.
     *
     * @throws WebloggerException when the index cannot be searched or the
     *     reader's query cannot be run
     */
    SearchHits find(SearchSpec spec, URLStrategy urlStrategy) throws WebloggerException;

    /**
     * The posts most like {@code entry}, by what they say: ids of published,
     * not-future posts of the same blog, best first, never the entry's own.
     * A display path: empty when search is disabled, the entry is not in the
     * index, or anything fails (logged), never an exception.
     */
    java.util.List<String> moreLikeThis(WeblogEntry entry, int max);

    /**
     * Brings the page's document up to date, in the background: indexed when
     * the page is PUBLISHED and not {@code noindex}, taken out otherwise
     * (and when it is no longer in the database). The page is re-read by id
     * when the work runs, so call it once the save has committed. Cheap, and
     * never throws: it runs on every page save.
     */
    void reindexPage(WeblogPage page);

    /** Takes the page's document out, in the background. Never throws: it runs on every page removal. */
    void removePage(WeblogPage page);

    /**
     * The pages a reader's words find, ids best first, at most {@code max}:
     * the words are read as a post search reads them (all of them first,
     * then fewer). {@link SearchSpec.Scope#BLOG} finds that blog's pages;
     * {@link SearchSpec.Scope#SITE} the pages of the blogs a site search
     * covers plus the site space's; {@link SearchSpec.Scope#OTHER_BLOGS}
     * none. The spec's category, locale and window are not read (a page has
     * no category or locale). Ids only: the caller loads the pages and drops
     * any the database no longer has. A display path: empty when search is
     * disabled, the words find nothing or cannot be parsed, or anything fails
     * (logged), never an exception, except that a blog search without a
     * handle throws {@link IllegalArgumentException}, as {@link #find} does.
     */
    java.util.List<String> findPages(SearchSpec spec, int max);
}
