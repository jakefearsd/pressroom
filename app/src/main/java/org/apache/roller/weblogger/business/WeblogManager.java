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

package org.apache.roller.weblogger.business;

import java.util.Date;
import java.util.List;
import java.util.Map;
import org.apache.roller.weblogger.WebloggerException;
import org.apache.roller.weblogger.pojos.CustomTemplateRendition;
import org.apache.roller.weblogger.pojos.ThemeTemplate.ComponentType;
import org.apache.roller.weblogger.pojos.User;
import org.apache.roller.weblogger.pojos.Weblog;
import org.apache.roller.weblogger.pojos.WeblogTemplate;


/**
 * Interface to weblog and weblog custom template management.
 */
public interface WeblogManager {
    
    /**
     * Add new website, give creator admin permission,
     * creates categories and other objects required for new website.
     * @param newWebsite New website to be created, must have creator.
     */
    void addWeblog(Weblog newWebsite) throws WebloggerException;
    
    
    /**
     * Store a single weblog.
     */
    void saveWeblog(Weblog data) throws WebloggerException;

    /**
     * Remove website object.
     */
    void removeWeblog(Weblog website) throws WebloggerException;
    
    /**
     * Get website object by name.
     */
    Weblog getWeblog(String id) throws WebloggerException;
    
    
    /**
     * The hidden site space weblog, or null when it has not been created yet.
     */
    Weblog getSiteSpace() throws WebloggerException;

    /**
     * The site space, created (owned by no one, with no permission rows) when
     * absent. Throws when another weblog already holds its handle.
     */
    Weblog getOrCreateSiteSpace(User creator) throws WebloggerException;

    /**
     * Get website specified by handle (or null if enabled website not found).
     * @param handle  Handle of website
     */
    Weblog getWeblogByHandle(String handle) throws WebloggerException;
    
    
    /**
     * Get website specified by handle with option to return only enabled websites.
     * @param handle  Handle of website
     */
    Weblog getWeblogByHandle(String handle, Boolean enabled)
        throws WebloggerException;

    /**
     * Get the weblog whose newsletter list uuid matches, or null when no
     * weblog is configured with it. A subscribe request names its target list
     * by uuid, never by weblog handle, so this is the only way to recover
     * which weblog (and therefore which {@code EventManager} bookkeeping) a
     * subscription belongs to.
     *
     * <p>The uuid is not required to be unique across weblogs -- sharing one
     * newsletter list across a family of blogs is permitted -- so when more
     * than one weblog is configured with the same uuid, the first one by
     * handle is returned deterministically, and it is the one credited with
     * the subscribe event.
     * @param listUuid The newsletter list uuid to look up
     */
    Weblog getWeblogByNewsletterListUuid(String listUuid) throws WebloggerException;

    /**
     * The weblog served at the given hostname, or null when no weblog claims
     * it. Unlike getWeblogByNewsletterListUuid, custom_domain carries a unique
     * index, so at most one row can ever match.
     *
     * @param host a hostname, already lowercased and stripped of any port
     */
    Weblog getWeblogByCustomDomain(String host) throws WebloggerException;

    /**
     * Get websites optionally restricted by user, enabled and active status.
     * @param enabled   Get all with this enabled state (or null or all)
     * @param active    Get all with this active state (or null or all)
     * @param startDate Restrict to those created after (or null for all)
     * @param endDate   Restrict to those created before (or null for all)
     * @param offset    Offset into results (for paging)
     * @param length    Maximum number of results to return (for paging)
     * @return List of Weblog objects.
     */
    List<Weblog> getWeblogs(
            Boolean  enabled,
            Boolean  active,
            Date     startDate,
            Date     endDate,
            int      offset,
            int      length)
            throws WebloggerException;
    
    
    /**
     * Get websites of a user.
     * @param user        Get all weblogs for this user
     * @param enabledOnly Include only enabled weblogs?
     * @return List of Weblog objects.
     */
    List<Weblog> getUserWeblogs(User user, boolean enabledOnly) throws WebloggerException;
    
    
    /**
     * Get users of a weblog.
     * @param weblog Weblog to retrieve users for
     * @param enabledOnly Include only enabled users?
     * @return List of WebsiteData objects.
     */
    List<User> getWeblogUsers(Weblog weblog, boolean enabledOnly) throws WebloggerException;


    
    /**
     * Get map with 26 entries, one for each letter A-Z and
     * containing integers reflecting the number of weblogs whose
     * names start with each letter.
     */
    Map<String, Long> getWeblogHandleLetterMap() throws WebloggerException;
    
    
    /** 
     * Get collection of weblogs whose handles begin with specified letter 
     */
    List<Weblog> getWeblogsByLetter(char letter, int offset, int length)
        throws WebloggerException;
    
    /**
     * Store a custom weblog template.
     */
    void saveTemplate(WeblogTemplate data) throws WebloggerException;
    
    
    /**
     * Remove a custom template.
     */
    void removeTemplate(WeblogTemplate template) throws WebloggerException;
    
    
    /**
     * Get a custom template by its id.
     */
    WeblogTemplate getTemplate(String id) throws WebloggerException;
    
    
    /**
     * Get a custom template by the action it supports.
     */
    WeblogTemplate getTemplateByAction(Weblog w, ComponentType a) throws WebloggerException;
    
    
    /**
     * Get a custom template by its name.
     */
    WeblogTemplate getTemplateByName(Weblog w, String p) throws WebloggerException;
    
    
    /**
     * Get a custom template by its link.
     */
    WeblogTemplate getTemplateByLink(Weblog w, String p)
        throws WebloggerException;

    /**
     * Save a custom template rendition
     */
    void saveTemplateRendition(CustomTemplateRendition templateCode) throws WebloggerException;

    /**
     * Get all custom templates for a weblog
     */
    List<WeblogTemplate> getTemplates(Weblog w) throws WebloggerException;
   
    
    /**
     * Get count of active weblogs
     */    
    long getWeblogCount() throws WebloggerException;
    
    
    /**
     * Release any resources held by manager.
     */
    void release();

}
