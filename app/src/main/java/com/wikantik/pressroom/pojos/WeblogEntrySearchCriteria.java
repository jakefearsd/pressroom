/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  The ASF licenses this file to You
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
package com.wikantik.pressroom.pojos;

import java.util.Date;
import java.util.List;
import com.wikantik.pressroom.pojos.WeblogEntry.PubStatus;

public class WeblogEntrySearchCriteria {

    /**
     * Every entry in one category. {@code publishedOnly} is the theme's view
     * (published entries, trash excluded by construction); the other form is
     * what category deletion's in-use guard and the move-contents-then-delete
     * sequence rely on, and it must still see trashed entries. A trashed entry
     * still blocks deleting its category (there is nothing to restore it into
     * once the category is gone), and moving a category's contents must carry
     * a trashed entry along to the destination rather than silently leaving
     * it pointed at a row about to be removed. Without {@code includeTrashed},
     * the default exclusion this class applies to a query naming no explicit
     * status makes a category whose only entries are trashed look empty here
     * even though {@code WeblogEntry.getByCategory} ({@code isWeblogCategoryInUse}'s
     * query) still counts it as in use -- and {@code moveWeblogCategoryContents}
     * would move zero entries out of the way before the category is removed
     * out from under them.
     *
     * <p>This used to be {@code WeblogCategory.retrieveWeblogEntries(boolean)},
     * an entity method that located the entry manager statically; the query
     * spec lives here so the wrapper (the template API) and the entry manager
     * build the same one.
     */
    public static WeblogEntrySearchCriteria forCategory(WeblogCategory category, boolean publishedOnly) {
        WeblogEntrySearchCriteria wesc = new WeblogEntrySearchCriteria();
        wesc.setWeblog(category.getWeblog());
        wesc.setCatName(category.getName());
        if (publishedOnly) {
            wesc.setStatus(PubStatus.PUBLISHED);
        } else {
            wesc.setIncludeTrashed(true);
        }
        return wesc;
    }

    public enum SortOrder {ASCENDING, DESCENDING}
    // TRASH_TIME sorts by e.trashedAt, the only field guaranteed non-null
    // for a trashed entry -- pubTime is null for a draft that was trashed
    // before ever being published, and updateTime does not reflect when the
    // entry was trashed at all. Only the trash screen (includeTrashed /
    // status == TRASHED) has any reason to use it.
    public enum SortBy {PUBLICATION_TIME, UPDATE_TIME, TRASH_TIME}

    // TODO: See if can switch from name of Category to Category object

    // Weblog or null to get for all weblogs.
    private Weblog weblog;
    // User or null to get for all users.
    private User user;
    // Start date or null for no start date.
    private Date startDate;
    // End date or null for no end date.
    private Date endDate;
    // Category name or null for all categories.
    private String catName;
    // If provided, array of tags to search blog entries for, just one needs to match to retrieve entry
    private List<String> tags;
    // Publication status of the weblog entry (DRAFT, PUBLISHED, etc.)
    private PubStatus status;
    // Whether a query naming no explicit status should still see trashed
    // entries. Default false: a caller that thinks about nothing must get the
    // safe behaviour, since the unsafe direction puts deleted entries back on
    // a public page. Only the trash screen sets this true; a caller wanting
    // trash specifically should ask for status == PubStatus.TRASHED instead.
    private boolean includeTrashed = false;
    // Text appearing in the text or summary, or null for all
    private String text;
    // Date field to sort by
    private SortBy sortBy = SortBy.PUBLICATION_TIME;
    // Order of sort
    private SortOrder sortOrder = SortOrder.DESCENDING;

    private String locale;
    // Offset into results for paging
    private int offset = 0;

    private int maxResults = -1;

    public Weblog getWeblog() {
        return weblog;
    }

    public void setWeblog(Weblog weblog) {
        this.weblog = weblog;
    }

    public User getUser() {
        return user;
    }

    public void setUser(User user) {
        this.user = user;
    }

    public Date getStartDate() {
        return startDate;
    }

    public void setStartDate(Date startDate) {
        this.startDate = startDate;
    }

    public Date getEndDate() {
        return endDate;
    }

    public void setEndDate(Date endDate) {
        this.endDate = endDate;
    }

    public String getCatName() {
        return catName;
    }

    public void setCatName(String catName) {
        this.catName = catName;
    }

    public List<String> getTags() {
        return tags;
    }

    public void setTags(List<String> tags) {
        this.tags = tags;
    }

    public PubStatus getStatus() {
        return status;
    }

    public void setStatus(PubStatus status) {
        this.status = status;
    }

    public boolean isIncludeTrashed() {
        return includeTrashed;
    }

    public void setIncludeTrashed(boolean includeTrashed) {
        this.includeTrashed = includeTrashed;
    }

    public String getText() {
        return text;
    }

    public void setText(String text) {
        this.text = text;
    }

    public SortBy getSortBy() {
        return sortBy;
    }

    public void setSortBy(SortBy sortBy) {
        this.sortBy = sortBy;
    }

    public SortOrder getSortOrder() {
        return sortOrder;
    }

    public void setSortOrder(SortOrder sortOrder) {
        this.sortOrder = sortOrder;
    }

    public String getLocale() {
        return locale;
    }

    public void setLocale(String locale) {
        this.locale = locale;
    }

    public int getOffset() {
        return offset;
    }

    public void setOffset(int offset) {
        this.offset = offset;
    }

    public int getMaxResults() {
        return maxResults;
    }

    public void setMaxResults(int maxResults) {
        this.maxResults = maxResults;
    }

}
