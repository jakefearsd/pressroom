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

package com.wikantik.pressroom.ui.rendering.pagers;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.wikantik.pressroom.business.EntryPins;
import com.wikantik.pressroom.business.Weblogger;
import com.wikantik.pressroom.business.URLStrategy;
import com.wikantik.pressroom.pojos.EntryOrder;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.pojos.WeblogEntry;
import com.wikantik.pressroom.pojos.WeblogEntrySearchCriteria;
import com.wikantik.pressroom.pojos.wrapper.WeblogEntryWrapper;


/**
 * A pager over a collection of recent weblog entries.
 */
public class WeblogEntriesLatestPager extends AbstractWeblogEntriesPager {
    
    private static final Logger log = LoggerFactory.getLogger(WeblogEntriesLatestPager.class);
    
    // collection for the pager
    private Map<Date, List<WeblogEntryWrapper>> entries;
    
    // the blog's pinned posts, read once, for page one of the home listing
    private List<WeblogEntryWrapper> pinned;

    /**
     * The most pinned posts a blog shows: {@link EntryPins#MAX_PER_WEBLOG},
     * the limit pinning enforces, kept as a cap here too.
     */
    private static final int MAX_PINNED = EntryPins.MAX_PER_WEBLOG;

    // are there more pages?
    private boolean more;
    
    
    public WeblogEntriesLatestPager(
            URLStrategy        strat,
            Weblogger          weblogger,
            Weblog             weblog,
            String             locale,
            String             pageLink,
            String             entryAnchor,
            String             dateString,
            String             catName,
            List<String>       tags,
            int                page) {
        
        super(strat, weblogger, weblog, locale, pageLink, entryAnchor, dateString, catName, tags, page);
        
        // initialize the pager collection
        getEntries();
    }
    
    
    @Override
    public Map<Date, List<WeblogEntryWrapper>> getEntries() {

        if (entries == null) {
            entries = newDayMap();
            try {
                WeblogEntrySearchCriteria wesc = new WeblogEntrySearchCriteria();
                wesc.setEndDate(new Date());
                if (isHomeListing()) {
                    // Pinned posts are listed apart (getPinnedEntries), on
                    // every page's behalf: leaving them out here keeps the
                    // offsets counting regular posts only.
                    wesc.setPinned(Boolean.FALSE);
                }
                more = queryEntries(wesc, entries);
            } catch (Exception e) {
                log.error("ERROR: getting entry month map", e);
            }
        }

        return entries;
    }
    
    
    /** The home listing: no category and no tags (the latest pager never has a date). */
    private boolean isHomeListing() {
        return catName == null && (tags == null || tags.isEmpty());
    }

    /**
     * The blog's pinned posts, only on page one of the home listing. Same
     * conditions as the regular listing (published, this locale, not
     * scheduled for later), in the blog's order, capped at the pin limit.
     */
    @Override
    public List<WeblogEntryWrapper> getPinnedEntries() {
        if (pinned == null) {
            pinned = new ArrayList<>();
            if (isHomeListing() && page == 0) {
                try {
                    WeblogEntrySearchCriteria wesc = new WeblogEntrySearchCriteria();
                    wesc.setWeblog(weblog);
                    wesc.setStatus(WeblogEntry.PubStatus.PUBLISHED);
                    wesc.setLocale(locale);
                    wesc.setEndDate(new Date());
                    wesc.setPinned(Boolean.TRUE);
                    wesc.setMaxResults(MAX_PINNED);
                    wesc.setSortOrder(weblog.entryOrder() == EntryOrder.OLDEST
                            ? WeblogEntrySearchCriteria.SortOrder.ASCENDING
                            : WeblogEntrySearchCriteria.SortOrder.DESCENDING);
                    for (WeblogEntry entry : weblogger.getWeblogEntryManager().getWeblogEntries(wesc)) {
                        pinned.add(WeblogEntryWrapper.wrap(entry, urlStrategy, weblogger));
                    }
                } catch (Exception e) {
                    log.error("ERROR: getting pinned entries", e);
                }
            }
        }
        return pinned;
    }

    @Override
    public boolean hasMoreEntries() {
        return more;
    }
    
}
