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
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.TimeZone;
import com.wikantik.pressroom.business.URLStrategy;
import com.wikantik.pressroom.business.Weblogger;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.pojos.WeblogEntrySearchCriteria;
import com.wikantik.pressroom.util.DateUtil;

/**
 * A pager over one calendar period, a day or a month, with links to the
 * neighbouring periods. {@link WeblogEntriesDayPager} and
 * {@link WeblogEntriesMonthPager} only choose the {@link Period}.
 */
public class WeblogEntriesPeriodPager extends QueriedEntriesPager {

    /** The two periods, each knowing its range, neighbours, date format and message keys. */
    public enum Period {
        DAY("weblogEntriesPager.day.dateFormat", "weblogEntriesPager.day.home", "weblogEntriesPager.day.next", "weblogEntriesPager.day.prev", "weblogEntriesPager.day.nextCollection", "weblogEntriesPager.day.prevCollection") {
            @Override
            void range(Date date, Calendar cal, WeblogEntrySearchCriteria wesc) {
                wesc.setStartDate(DateUtil.getStartOfDay(date, cal));
                wesc.setEndDate(DateUtil.getEndOfDay(date, cal));
            }

            @Override
            Date next(Date current, Calendar cal) {
                cal.setTime(current);
                cal.add(Calendar.DAY_OF_MONTH, 1);
                cal.set(Calendar.HOUR, 0);
                cal.set(Calendar.MINUTE, 0);
                cal.set(Calendar.SECOND, 0);
                return cal.getTime();
            }

            @Override
            Date prev(Date current, Calendar cal) {
                cal.setTime(current);
                cal.add(Calendar.DAY_OF_MONTH, -1);
                cal.set(Calendar.HOUR, 23);
                cal.set(Calendar.MINUTE, 59);
                cal.set(Calendar.SECOND, 59);
                return cal.getTime();
            }

            @Override
            Date endOf(Date date, Calendar cal) {
                return DateUtil.getEndOfDay(date, cal);
            }

            @Override
            String format(Date date, TimeZone tz) {
                return DateUtil.format8chars(date, tz);
            }
        },
        MONTH("weblogEntriesPager.month.dateFormat", "weblogEntriesPager.month.home", "weblogEntriesPager.month.next", "weblogEntriesPager.month.prev", "weblogEntriesPager.month.nextCollection", "weblogEntriesPager.month.prevCollection") {
            @Override
            void range(Date date, Calendar cal, WeblogEntrySearchCriteria wesc) {
                cal.setTime(date);
                cal.add(Calendar.DATE, 1);
                Date shifted = cal.getTime();
                wesc.setStartDate(DateUtil.getStartOfMonth(shifted, cal));
                wesc.setEndDate(DateUtil.getEndOfMonth(shifted, cal));
            }

            @Override
            Date next(Date current, Calendar cal) {
                cal.setTime(current);
                cal.add(Calendar.MONTH, 1);
                return cal.getTime();
            }

            @Override
            Date prev(Date current, Calendar cal) {
                cal.setTime(current);
                cal.add(Calendar.MONTH, -1);
                return cal.getTime();
            }

            @Override
            Date endOf(Date date, Calendar cal) {
                return DateUtil.getEndOfMonth(date, cal);
            }

            @Override
            String format(Date date, TimeZone tz) {
                return DateUtil.format6chars(date, tz);
            }
        };

        // Whole-key literals, so MessageKeyTest sees each bundle key used.
        private final String dateFormat;
        private final String home;
        private final String next;
        private final String prev;
        private final String nextCollection;
        private final String prevCollection;

        Period(String dateFormat, String home, String next, String prev,
                String nextCollection, String prevCollection) {
            this.dateFormat = dateFormat;
            this.home = home;
            this.next = next;
            this.prev = prev;
            this.nextCollection = nextCollection;
            this.prevCollection = prevCollection;
        }

        abstract void range(Date date, Calendar cal, WeblogEntrySearchCriteria wesc);

        abstract Date next(Date current, Calendar cal);

        abstract Date prev(Date current, Calendar cal);

        abstract Date endOf(Date date, Calendar cal);

        abstract String format(Date date, TimeZone tz);
    }

    private final Period period;
    private final SimpleDateFormat periodFormat;
    private final Date current;
    private Date nextPeriod;
    private Date prevPeriod;

    protected WeblogEntriesPeriodPager(Period period, URLStrategy strat, Weblogger weblogger,
            Weblog weblog, String locale, String pageLink, String entryAnchor, String dateString,
            String catName, List<String> tags, int page) {
        super(strat, weblogger, weblog, locale, pageLink, entryAnchor, dateString, catName, tags, page);
        this.period = period;

        TimeZone tz = weblog.getTimeZoneInstance();
        periodFormat = new SimpleDateFormat(
            messageUtils.getString(period.dateFormat), messageUtils.getLocale());
        periodFormat.setTimeZone(tz);

        getEntries();

        current = parseDate(dateString);
        Calendar cal = Calendar.getInstance(tz);

        nextPeriod = period.next(current, cal);
        if (nextPeriod.after(getToday())) {
            nextPeriod = null;
        }

        prevPeriod = period.prev(current, cal);
        Date weblogInitialDate = weblog.getDateCreated() != null ? weblog.getDateCreated() : new Date(0);
        if (period.endOf(prevPeriod, cal).before(weblogInitialDate)) {
            prevPeriod = null;
        }
    }

    @Override
    protected void narrow(WeblogEntrySearchCriteria wesc) {
        period.range(parseDate(dateString),
                Calendar.getInstance(weblog.getTimeZoneInstance()), wesc);
    }

    @Override
    public String getHomeLink() {
        return createURL(0, 0, weblog, locale, pageLink, null, null, catName, tags);
    }

    @Override
    public String getHomeName() {
        return messageUtils.getString(period.home);
    }

    @Override
    public String getNextLink() {
        if (hasMoreEntries()) {
            return createURL(page, 1, weblog, locale, pageLink, null, dateString, catName, tags);
        }
        return null;
    }

    @Override
    public String getNextName() {
        if (getNextLink() != null) {
            return named(period.next, current);
        }
        return null;
    }

    @Override
    public String getPrevLink() {
        if (page > 0) {
            return createURL(page, -1, weblog, locale, pageLink, null, dateString, catName, tags);
        }
        return null;
    }

    @Override
    public String getPrevName() {
        if (getPrevLink() != null) {
            return named(period.prev, current);
        }
        return null;
    }

    @Override
    public String getNextCollectionLink() {
        return collectionLink(nextPeriod);
    }

    @Override
    public String getNextCollectionName() {
        return nextPeriod == null ? null : named(period.nextCollection, nextPeriod);
    }

    @Override
    public String getPrevCollectionLink() {
        return collectionLink(prevPeriod);
    }

    @Override
    public String getPrevCollectionName() {
        return prevPeriod == null ? null : named(period.prevCollection, prevPeriod);
    }

    private String collectionLink(Date target) {
        if (target == null) {
            return null;
        }
        return createURL(0, 0, weblog, locale, pageLink, null,
                period.format(target, weblog.getTimeZoneInstance()), catName, tags);
    }

    private String named(String messageKey, Date date) {
        return messageUtils.getString(messageKey, new Object[] {periodFormat.format(date)});
    }
}
