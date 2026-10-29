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

import java.util.Date;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.business.URLStrategy;
import com.wikantik.pressroom.business.Weblogger;
import com.wikantik.pressroom.business.WeblogEntryManager;
import com.wikantik.pressroom.pojos.WeblogEntry;
import com.wikantik.pressroom.pojos.WeblogEntry.PubStatus;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.pojos.wrapper.WeblogEntryWrapper;
import com.wikantik.pressroom.util.Utilities;


/**
 *
 */
public class WeblogEntriesPermalinkPager extends AbstractWeblogEntriesPager {
    
    private static final Logger log = LoggerFactory.getLogger(WeblogEntriesPermalinkPager.class);
    
    WeblogEntry currEntry = null;
    WeblogEntry nextEntry = null;
    WeblogEntry prevEntry = null;
    
    // collection for the pager
    Map<Date, List<WeblogEntryWrapper>> entries = null;
    
    
    public WeblogEntriesPermalinkPager(
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
        
        getEntries();
    }
    
    
    @Override
    public Map<Date, List<WeblogEntryWrapper>> getEntries() {
        if (entries == null) {
            try {
                WeblogEntryManager wmgr = weblogger.getWeblogEntryManager();
                currEntry = wmgr.getWeblogEntryByAnchor(weblog, entryAnchor);
                if (currEntry != null && currEntry.getStatus().equals(PubStatus.PUBLISHED)) {
                    entries = Map.of(new Date(currEntry.getPubTime().getTime()), List.of(WeblogEntryWrapper.wrap(currEntry, urlStrategy, weblogger)));
                }
            } catch (Exception e) {
                log.error("ERROR: fetching entry");
            }
        }
        
        return entries;
    }
    
    
    @Override
    public String getHomeLink() {
        return createURL(0, 0, weblog, locale, pageLink, null, dateString, catName, tags);
    }
    
    
    @Override
    public String getHomeName() {
        return messageUtils.getString("weblogEntriesPager.single.home");
    }
    
    
    @Override
    public String getNextLink() {
        if (getNextEntry() != null) {
            return createURL(0, 0, weblog, locale, pageLink, nextEntry.getAnchor(), dateString, catName, tags);
        }
        return null;
    }
    
    
    @Override
    public String getNextName() {
        if (getNextEntry() != null) {
            String title = Utilities.truncateNicely(getNextEntry().getTitle(), 15, 20, "...");
            return messageUtils.getString("weblogEntriesPager.single.next", new Object[] {title});
        }
        return null;
    }
    
    
    @Override
    public String getPrevLink() {
        if (getPrevEntry() != null) {
            return createURL(0, 0, weblog, locale, pageLink, prevEntry.getAnchor(), dateString, catName, tags);
        }
        return null;
    }
    
    
    @Override
    public String getPrevName() {
        if (getPrevEntry() != null) {
            String title = Utilities.truncateNicely(getPrevEntry().getTitle(), 15, 20, "...");
            return messageUtils.getString("weblogEntriesPager.single.prev", new Object[] {title});
        }
        return null;
    }
    
    
    /**
     * The newer neighbouring entry, or null at the top of the weblog.
     *
     * <p>Public so a theme can render its own next/previous affordance from the
     * real entry. {@link #getNextName()} is not a substitute: it truncates the
     * title to 15-20 characters, which is fine for the shared
     * {@code #showNextPrevEntriesControl} macro's one-line "&laquo; a | Main |
     * b &raquo;" strip but produces "Why We Stopped Publi..." in a theme that
     * gives the neighbour a line of its own. The journal theme renders this
     * directly; see themes/journal/permalink.vm.
     */
    public WeblogEntry getNextEntry() {
        if (nextEntry == null) {
            try {
                WeblogEntryManager wmgr = weblogger.getWeblogEntryManager();
                nextEntry = wmgr.getNextEntry(currEntry, null, locale);
                // make sure that entry is published and not to future
                if (nextEntry != null && nextEntry.getPubTime().after(new Date())
                        && nextEntry.getStatus().equals(PubStatus.PUBLISHED)) {
                    nextEntry = null;
                }
            } catch (WebloggerException e) {
                log.error("ERROR: getting next entry", e);
            }
        }

        return nextEntry;
    }
    
    
    /** The older neighbouring entry, or null at the foot of the weblog. Public for the same reason as {@link #getNextEntry()}. */
    public WeblogEntry getPrevEntry() {
        if (prevEntry == null) {
            try {
                WeblogEntryManager wmgr = weblogger.getWeblogEntryManager();
                prevEntry = wmgr.getPreviousEntry(currEntry, null, locale);
                // make sure that entry is published and not to future
                if (prevEntry != null && prevEntry.getPubTime().after(new Date())
                        && prevEntry.getStatus().equals(PubStatus.PUBLISHED)) {
                    prevEntry = null;
                }
            } catch (WebloggerException e) {
                log.error("ERROR: getting prev entry", e);
            }
        }

        return prevEntry;
    }
    
}
