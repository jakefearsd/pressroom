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

package com.wikantik.pressroom.ui.rendering.model;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.business.URLStrategy;
import com.wikantik.pressroom.business.Weblogger;
import com.wikantik.pressroom.business.UserManager;
import com.wikantik.pressroom.business.WeblogEntryManager;
import com.wikantik.pressroom.business.jpa.JPAWeblogEntryManagerImpl;
import com.wikantik.pressroom.pojos.TagStat;
import com.wikantik.pressroom.pojos.ThemeTemplate;
import com.wikantik.pressroom.pojos.User;
import com.wikantik.pressroom.pojos.WeblogEntry;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.pojos.WeblogPermission;
import com.wikantik.pressroom.pojos.wrapper.UserWrapper;
import com.wikantik.pressroom.pojos.wrapper.WeblogEntryWrapper;
import com.wikantik.pressroom.pojos.wrapper.WeblogWrapper;
import com.wikantik.pressroom.ui.rendering.pagers.Pager;
import com.wikantik.pressroom.ui.rendering.pagers.UsersPager;
import com.wikantik.pressroom.ui.rendering.pagers.WeblogEntriesListPager;
import com.wikantik.pressroom.ui.rendering.pagers.WeblogsPager;
import com.wikantik.pressroom.ui.rendering.util.WeblogFeedRequest;
import com.wikantik.pressroom.ui.rendering.util.WeblogPageRequest;
import com.wikantik.pressroom.ui.rendering.util.WeblogRequest;


/**
 * Page model that provides access to site-wide users, weblogs and entries.
 */
public class SiteModel implements Model {
    
    private static final Logger log = LoggerFactory.getLogger(SiteModel.class);   
    
    private Weblog weblog = null;
    private WeblogRequest weblogRequest = null;
    private WeblogFeedRequest feedRequest = null;
    private List<String> tags = Collections.emptyList();
    private String pageLink = null;
    private int pageNum = 0;
    
    private URLStrategy urlStrategy = null;

    /** The business-tier facade, handed in through {@code initData} (plan Task 10). */
    private Weblogger weblogger = null;


    @Override
    public String getModelName() {
        return "site";
    }
    
    @Override
    public void init(Map<String, Object> initData) throws WebloggerException {
        
        // we expect the init data to contain a weblogRequest object
        this.weblogRequest = (WeblogRequest) initData.get("parsedRequest");
        if(this.weblogRequest == null) {
            throw new WebloggerException("expected weblogRequest from init data");
        }
        
        if (weblogRequest instanceof WeblogPageRequest) {
            ThemeTemplate weblogPage = ((WeblogPageRequest)weblogRequest).getWeblogPage();
            pageLink = (weblogPage != null) ? weblogPage.getLink() : null;
            pageNum = ((WeblogPageRequest)weblogRequest).getPageNum();
            tags = ((WeblogPageRequest)weblogRequest).getTags();
        } else if (weblogRequest instanceof WeblogFeedRequest) {
            this.feedRequest = (WeblogFeedRequest) weblogRequest;
            tags = feedRequest.getTags();
            pageNum = feedRequest.getPage();
        }
        
        // the facade and the url strategy are both required -- no fallback
        weblogger = ModelLoader.requireWeblogger(initData);
        urlStrategy = ModelLoader.requireUrlStrategy(initData);

        // extract weblog object
        weblog = weblogRequest.getWeblog();
    }
    
    //----------------------------------------------------------------- Pagers
    
    /**
     * Get pager ofWeblogEntry objects across all weblogs, in reverse chrono order by pubTime.
     * @param sinceDays Limit to past X days in past (or -1 for no limit)
     * @param length    Max number of results to return
     */
    public Pager<WeblogEntryWrapper> getWeblogEntriesPager(int sinceDays, int length) {
        
        String pagerUrl;
        
        if (feedRequest != null) {
            pagerUrl = urlStrategy.getWeblogFeedURL(weblog, 
                    weblogRequest.getLocale(), feedRequest.getType(),
                    feedRequest.getFormat(), feedRequest.getWeblogCategoryName(), null,
                    feedRequest.getTags(), feedRequest.isExcerpts(), true);
        } else {        
            pagerUrl = urlStrategy.getWeblogPageURL(weblog, 
                weblogRequest.getLocale(), pageLink, 
                null, null, null, tags, 0, false);
        }
        
        return new WeblogEntriesListPager(
            urlStrategy, weblogger,
            pagerUrl, null, null, null,
            tags,
            weblogRequest.getLocale(),
            sinceDays,
            pageNum, 
            length);
    }
    
       
    /**
     * Get pager ofWeblogEntry objects across all weblogs,
     * in reverse chrono order by pubTime.
     * @param queryWeblog Restrict to this weblog
     * @param sinceDays   Limit to past X days in past (or -1 for no limit)
     * @param length      Max number of results to return
     */   
    public Pager<WeblogEntryWrapper> getWeblogEntriesPager(WeblogWrapper queryWeblog, int sinceDays, int length) {
        return getWeblogEntriesPager(queryWeblog, null, null, sinceDays, length);
    }

    /**
     * Get pager ofWeblogEntry objects across all weblogs,
     * in reverse chrono order by pubTime.
     * @param queryWeblog Restrict to this weblog
     * @param user        Restrict to this user
     * @param sinceDays   Limit to past X days in past (or -1 for no limit)
     * @param length      Max number of results to return
     */   
    public Pager<WeblogEntryWrapper> getWeblogEntriesPager(WeblogWrapper queryWeblog, User user, int sinceDays, int length) {
        return getWeblogEntriesPager(queryWeblog, user, null, sinceDays, length);
    }

    /**
     * Get pager ofWeblogEntry objects across all weblogs,
     * in reverse chrono order by pubTime.
     * @param queryWeblog Restrict to this weblog
     * @param user        Restrict to this user
     * @param cat         Restrict to this category
     * @param sinceDays   Limit to past X days in past (or -1 for no limit)
     * @param length      Max number of results to return
     */   
    public Pager<WeblogEntryWrapper> getWeblogEntriesPager(WeblogWrapper queryWeblog, User user, String cat, int sinceDays, int length) {
        
        String pagerUrl;
        if (feedRequest != null) {
            pagerUrl = urlStrategy.getWeblogFeedURL(weblog, 
                    weblogRequest.getLocale(), feedRequest.getType(),
                    feedRequest.getFormat(), feedRequest.getWeblogCategoryName(), null,
                    feedRequest.getTags(), feedRequest.isExcerpts(), true);
        } else {
            pagerUrl = urlStrategy.getWeblogPageURL(weblog, 
                weblogRequest.getLocale(), pageLink, 
                null, null, null, tags, 0, false);
        }
       
        return new WeblogEntriesListPager(
            urlStrategy, weblogger,
            pagerUrl, queryWeblog.getPojo(), user, cat,
            tags,
            weblogRequest.getLocale(),
            sinceDays,
            pageNum, 
            length);
    }    
    


    /* Get pager of users whose names begin with specified letter */
    public Pager<UserWrapper> getUsersByLetterPager(String letter, int sinceDays, int length) {
        
        String pagerUrl;
        if (feedRequest != null) {
            pagerUrl = urlStrategy.getWeblogFeedURL(weblog, 
                    weblogRequest.getLocale(), feedRequest.getType(),
                    feedRequest.getFormat(), null, null, null, feedRequest.isExcerpts(), true);
        } else {        
            pagerUrl = urlStrategy.getWeblogPageURL(weblog, 
                weblogRequest.getLocale(), pageLink, 
                null, null, null, null, 0, false);
        }        
        
        if(letter != null && StringUtils.isEmpty(letter)) {
            letter = null;
        }
        
        return new UsersPager(
            urlStrategy, weblogger,
            pagerUrl,
            letter,
            weblogRequest.getLocale(),
            sinceDays,
            pageNum, 
            length);
    }      
    
    
    /** Get pager of weblogs whose handles begin with specified letter */
    public Pager<WeblogWrapper> getWeblogsByLetterPager(String letter, int sinceDays, int length) {
        
        String pagerUrl = urlStrategy.getWeblogPageURL(weblog, 
                weblogRequest.getLocale(), pageLink, 
                null, null, null, null, 0, false);
        
        if(letter != null && StringUtils.isEmpty(letter)) {
            letter = null;
        }
        
        return new WeblogsPager(
            urlStrategy, weblogger,
            pagerUrl,
            letter,
            weblogRequest.getLocale(),
            sinceDays,
            pageNum, 
            length);
    }   
    
    //--------------------------------------------------- User/weblog directory 

    /**
     * Get map with 26 entries, one for each letter A-Z and
     * containing integers reflecting the number of users whose
     * names start with each letter.
     */
    public Map<String, Long> getUserNameLetterMap() {
        try {
            Weblogger roller = weblogger;
            UserManager umgr = roller.getUserManager();
            return umgr.getUserNameLetterMap();
        } catch (Exception e) {
            log.error("ERROR: fetching username letter map", e);
        }
        return Collections.emptyMap();
    }
    
    
    /**
     * Get map with 26 entries, one for each letter A-Z and
     * containing integers reflecting the number of weblogs whose
     * names start with each letter.
     */
    public Map<String, Long> getWeblogHandleLetterMap() {
        try {
            return weblogger.getWeblogManager().getWeblogHandleLetterMap();
        } catch (Exception e) {
            log.error("ERROR: fetching weblog handle letter map", e);
        }
        return Collections.emptyMap();
    }
    

    /** 
     * Return list of weblogs that user belongs to.
     */
    public List<WeblogWrapper> getUsersWeblogs(String userName) {
        List<WeblogWrapper> results = new ArrayList<>();
        try {
            Weblogger roller = weblogger;
            UserManager umgr = roller.getUserManager();
            User user = umgr.getUserByUserName(userName);
            List<WeblogPermission> perms = umgr.getWeblogPermissions(user);
            for (WeblogPermission perm : perms) {
                results.add(WeblogWrapper.wrap(
                        weblogger.getWeblogManager().getWeblogByHandle(perm.getObjectId(), null),
                        urlStrategy, weblogger));
            }
        } catch (Exception e) {
            log.error("ERROR: fetching weblog list", e);
        }
        return results;
    }
    
    
    /** 
     * Return list of users that belong to website.
     */
    public List<UserWrapper> getWeblogsUsers(String handle) {
        List<UserWrapper> results = new ArrayList<>();
        try {
            Weblogger roller = weblogger;
            UserManager umgr = roller.getUserManager();
            Weblog website = weblogger.getWeblogManager().getWeblogByHandle(handle);
            List<WeblogPermission> perms = umgr.getWeblogPermissions(website);
            for (WeblogPermission perm : perms) {
                results.add(UserWrapper.wrap(umgr.getUserByUserName(perm.getUserName())));
            }
        } catch (Exception e) {
            log.error("ERROR: fetching weblog list", e);
        }
        return results;
    }
    
    
    
    /** Get User object by username */
    public UserWrapper getUser(String username) {
        UserWrapper wrappedUser = null;
        try {            
            Weblogger roller = weblogger;
            UserManager umgr = roller.getUserManager();
            User user = umgr.getUserByUserName(username, Boolean.TRUE);
            wrappedUser = UserWrapper.wrap(user);
        } catch (Exception e) {
            log.error("ERROR: fetching users by letter", e);
        }
        return wrappedUser;
    }
    
    
    /** Get Website object by handle */
    public WeblogWrapper getWeblog(String handle) {
        WeblogWrapper wrappedWebsite = null;
        try {            
            Weblog website = weblogger.getWeblogManager().getWeblogByHandle(handle);
            wrappedWebsite = WeblogWrapper.wrap(website, urlStrategy, weblogger);
        } catch (Exception e) {
            log.error("ERROR: fetching users by letter", e);
        }
        return wrappedWebsite;
    }
    
        
    //------------------------------------------------------- Small collections
    
    /*
     * Get most collection of Website objects,
     * in reverse chrono order by creationDate.
     * @param offset   Offset into results (for paging)
     * @param len      Max number of results to return
     */
    public List<WeblogWrapper> getNewWeblogs(int sinceDays, int length) {
        List<WeblogWrapper> results = new ArrayList<>();
        Date startDate = JPAWeblogEntryManagerImpl.getStartDateNow(sinceDays);
        try {            
            List<Weblog> weblogs = weblogger.getWeblogManager().getWeblogs(
                Boolean.TRUE, Boolean.TRUE, startDate, null, 0, length);
            for (Weblog website : weblogs) {
                results.add(WeblogWrapper.wrap(website, urlStrategy, weblogger));
            }
        } catch (Exception e) {
            log.error("ERROR: fetching weblog list", e);
        }
        return results;
    }
           

    /*
     * Get most recent User objects, in reverse chrono order by creationDate.
     * @param offset   Offset into results (for paging)
     * @param len      Max number of results to return
     */
    public List<UserWrapper> getNewUsers(int sinceDays, int length) {
        List<UserWrapper> results = new ArrayList<>();
        try {            
            Weblogger roller = weblogger;
            UserManager umgr = roller.getUserManager();
            List<User> users = umgr.getUsers(Boolean.TRUE, null, null, 0, length);
            for (User user : users) {
                results.add(UserWrapper.wrap(user));
            }
        } catch (Exception e) {
            log.error("ERROR: fetching weblog list", e);
        }
        return results;
    }   
    
    
    /**
     * Get pinned entries.
     * @param length    Max number of results to return
     */
    public List<WeblogEntryWrapper> getPinnedWeblogEntries(int length) {
        List<WeblogEntryWrapper> results = new ArrayList<>();
        try {            
            Weblogger roller = weblogger;
            WeblogEntryManager wmgr = roller.getWeblogEntryManager();
            List<WeblogEntry> weblogEntries = wmgr.getWeblogEntriesPinnedToMain(length);
            for (WeblogEntry entry : weblogEntries) {
                results.add(WeblogEntryWrapper.wrap(entry, urlStrategy, weblogger));
            }
        } catch (Exception e) {
            log.error("ERROR: fetching pinned weblog entries", e);
        }
        return results;
    }
        
    /**
     * 
     * @param sinceDays
     * @param length
     * @return
     */
    public List<TagStat> getPopularTags(int sinceDays, int length) {
        Date startDate = null;
        if(sinceDays > 0) {
            Calendar cal = Calendar.getInstance();
            cal.setTime(new Date());
            cal.add(Calendar.DATE, -1 * sinceDays);        
            startDate = cal.getTime();     
        }
        
        try {            
            Weblogger roller = weblogger;
            WeblogEntryManager wmgr = roller.getWeblogEntryManager();
            return wmgr.getPopularTags(null, startDate, 0, length);
        } catch (Exception e) {
            log.error("ERROR: fetching site tags list", e);
        }
        return Collections.emptyList();
    }   
    
    
    public long getEntryCount() {
        long count = 0;
        try {
            Weblogger roller = weblogger;
            WeblogEntryManager mgr = roller.getWeblogEntryManager();
            count = mgr.getEntryCount();            
        } catch (WebloggerException e) {
            log.error("Error getting entry count for site", e);
        }
        return count;
    }
    
    
    public long getWeblogCount() {
        long count = 0;
        try {
            count = weblogger.getWeblogManager().getWeblogCount();            
        } catch (WebloggerException e) {
            log.error("Error getting weblog count for site", e);
        }
        return count;
    } 
    
    
    public long getUserCount() {
        long count = 0;
        try {
            Weblogger roller = weblogger;
            UserManager mgr = roller.getUserManager();
            count = mgr.getUserCount();            
        } catch (WebloggerException e) {
            log.error("Error getting user count for site", e);
        }
        return count;
    }
    
}
