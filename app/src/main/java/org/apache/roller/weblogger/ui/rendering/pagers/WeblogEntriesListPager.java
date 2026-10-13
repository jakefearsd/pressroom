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

import org.apache.roller.weblogger.WebloggerException;
import java.sql.Timestamp;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import org.apache.roller.weblogger.business.URLStrategy;
import org.apache.roller.weblogger.business.Weblogger;
import org.apache.roller.weblogger.pojos.User;
import org.apache.roller.weblogger.pojos.WeblogEntry;
import org.apache.roller.weblogger.pojos.Weblog;
import org.apache.roller.weblogger.pojos.WeblogEntrySearchCriteria;
import org.apache.roller.weblogger.pojos.wrapper.WeblogEntryWrapper;


/**
 * Simple pager for list of weblog entries.
 */
public class WeblogEntriesListPager extends AbstractPager<WeblogEntryWrapper> {
    
    
    /** The business tier this pager queries; handed in by the model that built it (plan Task 11). */
    private final Weblogger weblogger;

    private String locale;
    private int sinceDays;
    
    private Weblog queryWeblog;
    private User queryUser;
    private String queryCat;
    private List<String> queryTags;
    
    // entries for the pager
    
    // are there more entries?
    
    // most recent update time of current set of entries
    private Date lastUpdated = null;    
    
    
    public WeblogEntriesListPager(
            URLStrategy    strat,
            Weblogger      weblogger,
            String         baseUrl,
            Weblog         queryWeblog,
            User           queryUser,
            String         queryCat,
            List<String>   queryTags,
            String         locale,
            int            sinceDays,
            int            pageNum,
            int            length) {
        
        super(strat, baseUrl, pageNum, length);
        this.weblogger = weblogger;
        
        // store the data
        this.queryWeblog = queryWeblog;
        this.queryUser = queryUser;
        this.queryCat = queryCat;
        this.queryTags = queryTags;
        this.locale = locale;
        this.sinceDays = sinceDays;
        
        // initialize the pager collection
        getItems();
    }
    
    
    @Override
    protected List<WeblogEntryWrapper> fetchPage(int offset, int limit)
            throws WebloggerException {

        Date startDate = null;
        if (sinceDays > 0) {
            Calendar cal = Calendar.getInstance();
            cal.setTime(new Date());
            cal.add(Calendar.DATE, -1 * sinceDays);
            startDate = cal.getTime();
        }

        WeblogEntrySearchCriteria wesc = new WeblogEntrySearchCriteria();
        wesc.setWeblog(queryWeblog);
        wesc.setUser(queryUser);
        wesc.setStartDate(startDate);
        wesc.setCatName(queryCat);
        wesc.setTags(queryTags);
        wesc.setStatus(WeblogEntry.PubStatus.PUBLISHED);
        wesc.setLocale(locale);
        wesc.setOffset(offset);
        wesc.setMaxResults(limit);

        return weblogger.getWeblogEntryManager()
                .getWeblogEntries(wesc).stream()
                .map(e -> WeblogEntryWrapper.wrap(e, urlStrategy, weblogger))
                .toList();
    }


    @Override
    protected String itemLabel() {
        return "weblog entry";
    }
    
    

    /** Get last updated time from items in pager */
    public Date getLastUpdated() {
        if (lastUpdated == null) {
            // feeds are sorted by pubtime, so first might not be last updated.
            // getItems() can never return null -- it always returns the
            // cached `entries` list or a freshly built (possibly empty) one
            // -- so only emptiness needs checking here.
            List<WeblogEntryWrapper> items = getItems();
            if (!items.isEmpty()) {
                Timestamp newest = items.get(0).getUpdateTime();
                for (WeblogEntryWrapper e : items) {
                    if (e.getUpdateTime().after(newest)) {
                        // NOTE: must store the update time we just compared. Storing
                        // the publication time instead dragged the running maximum
                        // back to a much older instant for any entry published long
                        // ago and edited recently, so the site-wide feed advertised
                        // an <updated> older than its own entries and aggregators
                        // holding a newer If-Modified-Since never saw the change.
                        newest = e.getUpdateTime();
                    }
                }
                lastUpdated = new Date(newest.getTime());
            } else {
                // no update so we assume it's brand new
                lastUpdated = new Date();
            }
        }
        return lastUpdated;
    }
}
