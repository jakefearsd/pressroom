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

import java.text.SimpleDateFormat;
import java.util.*;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.pojos.WeblogEntrySearchCriteria;
import com.wikantik.pressroom.pojos.wrapper.WeblogEntryWrapper;
import com.wikantik.pressroom.util.DateUtil;
import com.wikantik.pressroom.business.Weblogger;
import com.wikantik.pressroom.business.URLStrategy;


/**
 *
 */
public class WeblogEntriesMonthPager extends AbstractWeblogEntriesPager {
    
    private static final Logger log = LoggerFactory.getLogger(WeblogEntriesMonthPager.class);
    
    private SimpleDateFormat monthFormat;
    
    private Date month;
    private Date nextMonth;
    private Date prevMonth;
    
    // collection for the pager
    private Map<Date, List<WeblogEntryWrapper>> entries = null;
    
    // are there more pages?
    private boolean more = false;
    
    
    public WeblogEntriesMonthPager(
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

        TimeZone tz = weblog.getTimeZoneInstance();

        monthFormat = new SimpleDateFormat(
            messageUtils.getString("weblogEntriesPager.month.dateFormat"), messageUtils.getLocale());
        monthFormat.setTimeZone(tz);
        
        getEntries();
        
        month = parseDate(dateString);
        
        Calendar cal = Calendar.getInstance(tz);
        
        cal.setTime(month);
        cal.add(Calendar.MONTH, 1);
        nextMonth = cal.getTime();
        if (nextMonth.after(getToday())) {
            nextMonth = null;
        }
        
        cal.setTime(month);
        cal.add(Calendar.MONTH, -1);
        prevMonth = cal.getTime();
        Date endOfPrevMonth = DateUtil.getEndOfMonth(prevMonth,cal) ;
        Date weblogInitialDate = weblog.getDateCreated() != null ? weblog.getDateCreated() : new Date(0);
        if (endOfPrevMonth.before(weblogInitialDate)) {
            prevMonth = null;
        }
    }
    
    
    @Override
    public Map<Date, List<WeblogEntryWrapper>> getEntries() {
        Date date = parseDate(dateString);
        Calendar cal = Calendar.getInstance(weblog.getTimeZoneInstance());
        cal.setTime(date);
        cal.add(Calendar.DATE, 1);
        date = cal.getTime();
        Date startDate = DateUtil.getStartOfMonth(date, cal);
    // CPD-OFF -- The day and month pagers differ only in how they turn a date
    // string into a range and which message key names their home link;
    // everything between is identical. This IS extractable -- the same pull-up
    // that AbstractPager just received for its paging arithmetic -- and is
    // deferred rather than excused. It stays marked so the next reader knows it
    // was measured, not missed.
        Date endDate = DateUtil.getEndOfMonth(date, cal);

        if (entries == null) {
            entries = new TreeMap<>(Collections.reverseOrder());
            try {
                WeblogEntrySearchCriteria wesc = new WeblogEntrySearchCriteria();
                wesc.setStartDate(startDate);
                wesc.setEndDate(endDate);
                more = queryEntries(wesc, entries);
            } catch (Exception e) {
                log.error("ERROR: getting entry month map", e);
            }
        }
        return entries;
    }
    
    
    @Override
    public String getHomeLink() {
        return createURL(0, 0, weblog, locale, pageLink, null, null, catName, tags);
    }
    
    
    @Override
    public String getHomeName() {
        return messageUtils.getString("weblogEntriesPager.month.home");
    // CPD-ON
    }
    
    
    @Override
    public String getNextLink() {
        if (more) {
            return createURL(page, 1, weblog, locale, pageLink, null, dateString, catName, tags);
        }
        return null;
    }
    
    
    @Override
    public String getNextName() {
        if (getNextLink() != null) {
            return messageUtils.getString("weblogEntriesPager.month.next", new Object[] {monthFormat.format(month)});
        }
        return null;
    }
    
    
    @Override
    public String getPrevLink() {
        if (offset > 0) {
            return createURL(page, -1, weblog, locale, pageLink, null, dateString, catName, tags);
        }
        return null;
    }
    
    
    @Override
    public String getPrevName() {
        if (getPrevLink() != null) {
            return messageUtils.getString("weblogEntriesPager.month.prev", new Object[] {monthFormat.format(month)});
        }
        return null;
    }
    
    
    @Override
    public String getNextCollectionLink() {
        if (nextMonth != null) {
            String next = DateUtil.format6chars(nextMonth, weblog.getTimeZoneInstance());
            return createURL(0, 0, weblog, locale, pageLink, null, next, catName, tags);
        }
        return null;
    }
    
    
    @Override
    public String getNextCollectionName() {
        if (nextMonth != null) {
            return messageUtils.getString("weblogEntriesPager.month.nextCollection", new Object[] {monthFormat.format(nextMonth)});
        }
        return null;
    }
    

    @Override
    public String getPrevCollectionLink() {
        if (prevMonth != null) {
            String prev = DateUtil.format6chars(prevMonth, weblog.getTimeZoneInstance());
            return createURL(0, 0, weblog, locale, pageLink, null, prev, catName, tags);
        }
        return null;
    }
    
    
    @Override
    public String getPrevCollectionName() {
        if (prevMonth != null) {
            return messageUtils.getString("weblogEntriesPager.month.prevCollection", new Object[] {monthFormat.format(prevMonth)});
        }
        return null;
    }
    
}
