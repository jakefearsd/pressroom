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

package org.apache.roller.weblogger.ui.rendering.pagers;

import java.util.Map;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.roller.weblogger.WebloggerException;
import org.apache.roller.weblogger.business.URLStrategy;
import org.apache.roller.weblogger.util.URLUtilities;


/**
 * Abstract base for simple pagers.
 */
public abstract class AbstractPager<T> implements Pager<T> {

    private static final Logger log = LoggerFactory.getLogger(AbstractPager.class);

    final URLStrategy urlStrategy;
    private String url;
    private int page = 0;

    /** How many items make up one page. */
    private final int length;

    /** The page, fetched once and kept -- a template may ask for it repeatedly. */
    private List<T> items;

    /** Whether the fetch turned up more than this page holds. */
    private boolean more = false;


    protected AbstractPager(URLStrategy strat, String baseUrl, int pageNum, int length) {

        this.urlStrategy = strat;
        this.url = baseUrl;
        if(pageNum > 0) {
            this.page = pageNum;
        }
        this.length = length;
    }


    /**
     * This page's items, fetched on first use.
     *
     * <p>Final because every pager did this identically and independently:
     * cache-check, compute the offset, ask for one more row than a page holds,
     * keep the first {@code length} of them and let the extra row mean "there
     * is a next page". Getting that last part subtly wrong is how a Next link
     * appears on the last page, or fails to appear on the second-to-last.
     */
    @Override
    public final List<T> getItems() {
        if (items == null) {
            items = loadPage();
        }
        return items;
    }


    /**
     * Whether a further page follows this one.
     *
     * <p>Fetches the page first if it has not been fetched. Before this was
     * shared, each pager set its own flag inside its own getItems(), so asking
     * a pager for its next link WITHOUT having asked for its items answered
     * "no" -- silently, and only in that order. Templates happen to iterate the
     * items first, which is why nothing had noticed.
     *
     * <p>Not on the Pager interface; templates reach it through the concrete
     * type.
     */
    public final boolean hasMoreItems() {
        getItems();
        return more;
    }


    private List<T> loadPage() {

        List<T> results = new ArrayList<>();
        try {
            // one more than a page holds: the extra row is how we know
            // whether there is a next page, without a second count query
            List<T> fetched = fetchPage(page * length, length + 1);

            int count = 0;
            for (T item : fetched) {
                if (count++ < length) {
                    results.add(item);
                } else {
                    more = true;
                }
            }

        } catch (Exception e) {
            log.error("ERROR: fetching {} list", itemLabel(), e);
        }
        return results;
    }


    /**
     * Fetches and wraps at most {@code limit} items starting at {@code offset}.
     *
     * <p>The query and the wrapping are all that differ between pagers; the
     * caching and the paging arithmetic above are not.
     */
    protected abstract List<T> fetchPage(int offset, int limit) throws WebloggerException;


    /** What these items are called, for the log line when a fetch fails. */
    protected abstract String itemLabel();
    
    
    @Override
    public String getHomeLink() {
        return url;
    }
    
    
    @Override
    public String getHomeName() {
        return "Home";
    }
    
    
    @Override
    public String getNextLink() {
        if(hasMoreItems()) {
            return createURL(url, pageParams(page + 1));
        }
        return null;
    }


    /**
     * The query parameters one paging link carries: the page number, plus
     * whatever else the pager needs to keep across pages.
     *
     * <p>UsersPager and WeblogsPager both browse by first letter, and both used
     * to override BOTH link methods identically to keep that letter -- the same
     * twelve lines twice, in two classes. Dropping the letter is not a
     * cosmetic bug: page two of "weblogs starting with B" would show page two
     * of all weblogs.
     */
    private Map<String, String> pageParams(int forPage) {
        Map<String, String> params = new java.util.HashMap<>(linkParams());
        params.put("page", String.valueOf(forPage));
        return params;
    }


    /**
     * Parameters this pager keeps across pages, beyond the page number itself.
     * Empty unless a subclass is browsing a filtered view.
     */
    protected Map<String, String> linkParams() {
        return Map.of();
    }
    
    
    @Override
    public String getNextName() {
        if(hasMoreItems()) {
            return "Next";
        }
        return null;
    }
    
    
    @Override
    public String getPrevLink() {
        if (page > 0) {
            return createURL(url, pageParams(page - 1));
        }
        return null;
    }
    
    
    @Override
    public String getPrevName() {
        if (page > 0) {
            return "Previous";
        }
        return null;
    }
    
    
    protected String createURL(String url, Map<String, String> params) {
        return url + URLUtilities.getQueryString(params);
    }

    
    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public int getPage() {
        return page;
    }

    public void setPage(int page) {
        this.page = page;
    }
    
}
