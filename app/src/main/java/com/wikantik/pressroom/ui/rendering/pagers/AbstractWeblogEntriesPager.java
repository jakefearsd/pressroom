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

import com.wikantik.pressroom.ui.rendering.util.LocaleSegment;
import java.text.ParsePosition;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.apache.commons.lang3.StringUtils;
import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.config.WebloggerRuntimeConfig;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.pojos.WeblogEntry;
import com.wikantik.pressroom.pojos.WeblogEntrySearchCriteria;
import com.wikantik.pressroom.pojos.wrapper.WeblogEntryWrapper;
import com.wikantik.pressroom.util.DateUtil;
import com.wikantik.pressroom.business.URLStrategy;
import com.wikantik.pressroom.business.Weblogger;
import com.wikantik.pressroom.util.I18nMessages;

/**
 * An abstract implementation of a WeblogEntriesPager.
 *
 * This implementation lays out the basic functionality of an entries pager so
 * that subclasses can easily tweak only the few things necessary to handle
 * paging their own way.
 */
public abstract class AbstractWeblogEntriesPager implements WeblogEntriesPager {
    
    // message utils for doing i18n messages
    final I18nMessages messageUtils;
    
    // url strategy for building urls
    final URLStrategy urlStrategy;
    
    final Weblog weblog;
    final String locale;
    /** The business tier this pager queries; handed in by the model that built it (plan Task 11). */
    final Weblogger weblogger;
    final String pageLink;
    final String entryAnchor;
    final String dateString;
    final String catName;
    
    final int offset;
    final int page;
    final int length;
    
    final List<String> tags;
    
    
    public AbstractWeblogEntriesPager(
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
        
        this.urlStrategy = strat;
        this.weblogger = weblogger;
        
        this.weblog = weblog;
        this.locale = locale;
        this.pageLink = pageLink;
        this.entryAnchor = entryAnchor;
        this.dateString = dateString;
        this.catName = catName;
        
        this.tags = tags != null ? tags : Collections.emptyList();
        
        // make sure offset, length, and page are valid
        int maxLength = WebloggerRuntimeConfig.getIntProperty("site.pages.maxEntries");
        this.length = Math.min(weblog.getEntryDisplayCount(), maxLength);
        
        this.page = Math.max(0, page);
        // NOTE: must multiply by the clamped field, not the raw parameter. The
        // page number arrives straight off the query string and is not clamped
        // by WeblogPageRequest, so ?page=-1 used to produce a negative offset.
        // The entry query rejects that, the pager swallowed the failure, and the
        // weblog rendered with no entries at all.
        this.offset = length * this.page;
        
        // get a message utils instance to handle i18n of messages.
        // Never null: I18nMessages.getMessages dereferences its argument.
        Locale viewLocale = locale != null
                ? LocaleSegment.toLocale(locale)
                : weblog.getLocaleInstance();
        this.messageUtils = I18nMessages.getMessages(viewLocale);
    }
    
    
    public boolean hasMoreEntries() {
        return false;
    }
    
    
    @Override
    public String getHomeLink() {
        return createURL(0, 0, weblog, locale, pageLink, entryAnchor, dateString, catName, tags);
    }
    
    
    @Override
    public String getHomeName() {
        return messageUtils.getString("weblogEntriesPager.latest.home");
    }
    
    
    @Override
    public String getNextLink() {
        if (hasMoreEntries()) {
            return createURL(page, 1, weblog, locale, pageLink, entryAnchor, dateString, catName, tags);
        }
        return null;
    }
    
    
    @Override
    public String getNextName() {
        if (hasMoreEntries()) {
            return messageUtils.getString("weblogEntriesPager.latest.next");
        }
        return null;
    }
    
    
    @Override
    public String getPrevLink() {
        if (page > 0) {
            return createURL(page, -1, weblog, locale, pageLink, entryAnchor, dateString, catName, tags);
        }
        return null;
    }
    
    
    @Override
    public String getPrevName() {
        if (page > 0) {
            return messageUtils.getString("weblogEntriesPager.latest.prev");
        }
        return null;
    }
    
    
    @Override
    public String getNextCollectionLink() {
        return null;
    }
    
    
    @Override
    public String getNextCollectionName() {
        return null;
    }
    
    
    @Override
    public String getPrevCollectionLink() {
        return null;
    }
    
    
    @Override
    public String getPrevCollectionName() {
        return null;
    }
    
    
    /**
     * Parse data as either 6-char or 8-char format.
     */
    protected Date parseDate(String dateString) {
        Date ret = null;
        SimpleDateFormat char8DateFormat = DateUtil.get8charDateFormat();
        SimpleDateFormat char6DateFormat = DateUtil.get6charDateFormat();
        Calendar cal = Calendar.getInstance(
                weblog.getTimeZoneInstance(), weblog.getLocaleInstance());
        if (   dateString!=null
                && dateString.length()==8
                && StringUtils.isNumeric(dateString) ) {
        	char8DateFormat.setCalendar(cal);
            ParsePosition pos = new ParsePosition(0);
            ret = char8DateFormat.parse( dateString, pos );
            
            // make sure the requested date is not in the future
            Date today = getToday();
            if (ret.after(today)) {
                ret = today;
            }
        }
        if (   dateString!=null
                && dateString.length()==6
                && StringUtils.isNumeric(dateString) ) {
        	char6DateFormat.setCalendar(cal);
            ParsePosition pos = new ParsePosition(0);
            ret = char6DateFormat.parse( dateString, pos );
            
            // make sure the requested date is not in the future
            Date today = getToday();
            if (ret.after(today)) {
                ret = today;
            }
        }
        return ret;
    }
    
    
    /**
     * Return today based on current blog's timezone/locale.
     */
    protected Date getToday() {
        Calendar todayCal = Calendar.getInstance(weblog.getTimeZoneInstance(), weblog.getLocaleInstance());
        todayCal.setTime(new Date());
        return todayCal.getTime();
    }
    
    
    /**
     * Runs the entry search described by {@code wesc} and wraps the results
     * into {@code target}, the same way for every subclass. The caller has
     * already set whatever date range applies -- day, month, or none at all
     * for the latest pager -- since that part is the one thing that
     * genuinely differs between them. Returns whether more entries exist
     * beyond what was requested, which the caller assigns to its own
     * {@code more} field.
     *
     * <p>Moved here from WeblogEntriesDayPager/MonthPager/LatestPager, which
     * were otherwise identical apart from that date-range setup. The caller
     * keeps its own try/catch around this call (and its own per-class
     * logger), so an exception here is reported exactly as it was before the
     * extraction, and {@code target} -- pre-initialized by the caller and
     * mutated in place -- keeps whatever entries were added before a
     * mid-loop failure, also exactly as before.
     */
    protected boolean queryEntries(WeblogEntrySearchCriteria wesc,
            Map<Date, List<WeblogEntryWrapper>> target) throws WebloggerException {

        wesc.setWeblog(weblog);
        wesc.setCatName(catName);
        wesc.setTags(tags);
        wesc.setStatus(WeblogEntry.PubStatus.PUBLISHED);
        wesc.setLocale(locale);
        wesc.setOffset(offset);
        wesc.setMaxResults(length + 1);
        Map<Date, List<WeblogEntry>> mmap =
                weblogger.getWeblogEntryManager().getWeblogEntryObjectMap(wesc);

        boolean moreEntries = false;

        // need to wrap pojos
        int count = 0;
        for (Map.Entry<Date, List<WeblogEntry>> entry : mmap.entrySet()) {
            // now we need to go through each entry in a day and wrap
            List<WeblogEntryWrapper> wrapped = new ArrayList<>();
            List<WeblogEntry> unwrapped = entry.getValue();
            for (int i = 0; i < unwrapped.size(); i++) {
                if (count++ < length) {
                    wrapped.add(i, WeblogEntryWrapper.wrap(unwrapped.get(i), urlStrategy, weblogger));
                } else {
                    moreEntries = true;
                }
            }

            // done with that day, put it in the map
            if (!wrapped.isEmpty()) {
                target.put(entry.getKey(), wrapped);
            }
        }

        return moreEntries;
    }


    /**
     * Create URL that encodes pager state using most appropriate forms of URL.
     * @param pageAdd To be added to page number, or 0 for no page number
     */
    protected String createURL(
            int                page,
            int                pageAdd,
            Weblog             website,
            String             locale,
            String             pageLink,
            String             entryAnchor,
            String             dateString,
            String             catName,
            List<String>       tags) {
        
        int pageNum = page + pageAdd;
        
        if (pageLink != null) {
            return urlStrategy.getWeblogPageURL(website, locale, pageLink, entryAnchor, catName, dateString, tags, pageNum, false);
        } else if (entryAnchor != null) {
            return urlStrategy.getWeblogEntryURL(website, locale, entryAnchor, true);
        }
        
        return urlStrategy.getWeblogCollectionURL(website, locale, catName, dateString, tags, pageNum, false);
    }
    
}
