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

package com.wikantik.pressroom.ui.controllers.editor;

import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Date;
import java.util.Locale;
import java.util.List;
import org.apache.commons.lang3.StringUtils;
import com.wikantik.pressroom.pojos.WeblogEntrySearchCriteria;
import com.wikantik.pressroom.util.Utilities;


/**
 * A bean for managing entries query data.
 */
public class EntriesBean {
    
    private String endDateString = null;
    private String startDateString = null;
    private String categoryName = null;
    private String tagsAsString = null;
    private String text = null;
    private String status = "ALL";
    private WeblogEntrySearchCriteria.SortBy sortBy = WeblogEntrySearchCriteria.SortBy.UPDATE_TIME;
    private int page = 0;
    
    
    public EntriesBean() {
    }
    
    // convenience method
    public List<String> getTags() {
        if(getTagsAsString() != null) {
            return Utilities.splitStringAsTags(getTagsAsString());
        } else {
            return List.of();
        }
    }
    
    public Date getStartDate() {
        if(!StringUtils.isEmpty(getStartDateString())) {
            return parseFilterDate(getStartDateString(), false);
        }
        return null;
    }

    /**
     * The search criteria filters {@code pubTime <= endDate}, so a date-only
     * end value must resolve to the LAST instant of that day -- otherwise an
     * end date of "the 30th" excludes every entry published that day except
     * one landing in the first millisecond, which is not what a reader who
     * typed that date means.
     */
    public Date getEndDate() {
        if(!StringUtils.isEmpty(getEndDateString())) {
            return parseFilterDate(getEndDateString(), true);
        }
        return null;
    }

    /**
     * Parses a search-date filter value. The sidebar's date inputs became
     * native {@code <input type="date">} elements (Task B4, replacing jQuery
     * UI's datepicker), which submit ISO-8601 {@code yyyy-MM-dd} -- tried
     * first -- but a bookmarked search URL from before that change still
     * carries the old {@code MM/dd/yy} format, so that is tried second
     * rather than dropped. Both formats are date-only, so {@code endOfDay}
     * applies the same 23:59:59.999 adjustment to either one.
     */
    private static Date parseFilterDate(String s, boolean endOfDay) {
        try {
            LocalDate localDate = LocalDate.parse(s, DateTimeFormatter.ISO_LOCAL_DATE);
            return toDate(localDate, endOfDay);
        } catch (DateTimeParseException ignored) {
            // Not an ISO yyyy-MM-dd value -- fall through to the legacy
            // format a bookmarked search URL may still carry.
        }
        try {
            DateFormat df = new SimpleDateFormat("MM/dd/yy", Locale.ROOT);
            Date parsed = df.parse(s);
            if (!endOfDay) {
                return parsed;
            }
            LocalDate localDate = parsed.toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
            return toDate(localDate, true);
        } catch (Exception ignored) {
            // A malformed hand-typed/bookmarked search-date is routine, not
            // an error -- the search simply proceeds without this date
            // bound.
        }
        return null;
    }

    private static Date toDate(LocalDate localDate, boolean endOfDay) {
        if (endOfDay) {
            return Date.from(localDate.atTime(23, 59, 59, 999_000_000)
                    .atZone(ZoneId.systemDefault()).toInstant());
        }
        return Date.from(localDate.atStartOfDay(ZoneId.systemDefault()).toInstant());
    }
    
    
    public String getCategoryName() {
        return categoryName;
    }
    
    public void setCategoryName(String categoryId) {
        this.categoryName = categoryId;
    }
    
    public String getTagsAsString() {
        return tagsAsString;
    }
    
    public void setTagsAsString(String tags) {
        this.tagsAsString = tags;
    }
    
    public String getText() {
        return text;
    }
    
    public void setText(String text) {
        this.text = text;
    }
    
    public String getStatus() {
        return status;
    }
    
    public void setStatus(String status) {
        this.status = status;
    }
    
    public void setSortBy(WeblogEntrySearchCriteria.SortBy sortBy) {
        this.sortBy = sortBy;
    }
    
    public WeblogEntrySearchCriteria.SortBy getSortBy() {
        return sortBy;
    }
    
    public int getPage() {
        return page;
    }

    public void setPage(int page) {
        this.page = page;
    }

    public String getEndDateString() {
        return endDateString;
    }

    public void setEndDateString(String endDateString) {
        this.endDateString = endDateString;
    }

    public String getStartDateString() {
        return startDateString;
    }

    public void setStartDateString(String startDateString) {
        this.startDateString = startDateString;
    }
    
    //------------------------------------------------------- Good citizenship
    
    @Override
    public String toString() {
        StringBuilder buf = new StringBuilder();
        
        buf.append("startDate = ").append(getStartDate()).append("\n");
        buf.append("endDate = ").append(getEndDate()).append("\n");
        buf.append("status = ").append(getStatus()).append("\n");
        buf.append("sortBy = ").append(getSortBy()).append("\n");
        buf.append("catName = ").append(getCategoryName()).append("\n");
        buf.append("tags = ").append(getTagsAsString()).append("\n");
        buf.append("text = ").append(getText()).append("\n");
        buf.append("page = ").append(getPage()).append("\n");
        
        return buf.toString();
    }
}
