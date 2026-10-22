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

package org.apache.roller.weblogger.business.jpa;

import org.apache.commons.lang3.BooleanUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.roller.weblogger.WebloggerException;
import org.apache.roller.weblogger.config.WebloggerConfig;

import jakarta.persistence.NoResultException;
import jakarta.persistence.Query;
import jakarta.persistence.TypedQuery;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.apache.roller.weblogger.business.MediaFileManager;
import org.apache.roller.weblogger.business.UserManager;
import org.apache.roller.weblogger.business.VirtualHostRegistry;
import org.apache.roller.weblogger.business.WeblogEntryManager;
import org.apache.roller.weblogger.business.WeblogManager;
import org.apache.roller.weblogger.business.Weblogger;
import org.apache.roller.weblogger.pojos.CustomTemplateRendition;
import org.apache.roller.weblogger.pojos.TagStat;
import org.apache.roller.weblogger.pojos.ThemeTemplate.ComponentType;
import org.apache.roller.weblogger.pojos.User;
import org.apache.roller.weblogger.pojos.Weblog;
import org.apache.roller.weblogger.pojos.WeblogCategory;
import org.apache.roller.weblogger.pojos.WeblogEntry;
import org.apache.roller.weblogger.pojos.WeblogEntryTag;
import org.apache.roller.weblogger.pojos.WeblogPermission;
import org.apache.roller.weblogger.pojos.WeblogTemplate;


/*
 * JPAWeblogManagerImpl.java
 * Created on May 31, 2006, 4:08 PM
 */
public class JPAWeblogManagerImpl implements WeblogManager {
    
    private static final Logger log = LoggerFactory.getLogger(JPAWeblogManagerImpl.class);

    private final Weblogger roller;
    private final JPAPersistenceStrategy strategy;
    private final VirtualHostRegistry virtualHosts;
    private final TagAggregateStore tagAggregates;
    
    // cached mapping of weblogHandles -> weblogIds
    private final Map<String, String> weblogHandleToIdMap = Collections.synchronizedMap(new HashMap<>());

    public JPAWeblogManagerImpl(Weblogger roller, JPAPersistenceStrategy strat,
                                VirtualHostRegistry virtualHosts) {
        this.virtualHosts = virtualHosts;
        log.debug("Instantiating JPA Weblog Manager");
        this.roller = roller;
        this.strategy = strat;
        this.tagAggregates = new TagAggregateStore(strat);
    }
    
    
    @Override
    public void release() {}
    
    
    /**
     * Update existing weblog.
     */
    @Override
    public void saveWeblog(Weblog weblog) throws WebloggerException {

        weblog.setLastModified(new Date());
        strategy.store(weblog);

        // The host map is derived from this column, so any save may change
        // it. Cheap: the map is rebuilt lazily on the next read, not here.
        //
        // This pre-commit invalidate() alone is not enough (I5): the actual
        // commit happens later, in the caller's weblogger.flush(). A
        // concurrent request in that window can rebuild the map from the
        // still-uncommitted (pre-change) row and cache it, and with nothing
        // left to invalidate it again once the commit lands, that stale map
        // would stay cached until an unrelated weblog save happened to
        // invalidate it, or the JVM restarted -- while this save's own
        // caller sees "Saved changes". runAfterCommit() is what closes that
        // window: it re-invalidates once the surrounding transaction has
        // actually committed.
        virtualHosts.invalidate();
        strategy.runAfterCommit(virtualHosts::invalidate);
    }

    @Override
    public void removeWeblog(Weblog weblog) throws WebloggerException {

        // remove contents first, then remove weblog
        this.removeWeblogContents(weblog);
        this.strategy.remove(weblog);

        // The host map is derived from this column, so any save may change
        // it. Cheap: the map is rebuilt lazily on the next read, not here.
        // See saveWeblog's comment above for why this alone is not enough
        // (I5) and runAfterCommit() is needed too.
        virtualHosts.invalidate();
        strategy.runAfterCommit(virtualHosts::invalidate);

        // remove entry from cache mapping
        this.weblogHandleToIdMap.remove(weblog.getHandle());
    }
    
    /**
     * convenience method for removing contents of a weblog.
     * TODO BACKEND: use manager methods instead of queries here
     */
    private void removeWeblogContents(Weblog weblog)
    throws  WebloggerException {
        
        UserManager        umgr = roller.getUserManager();
        WeblogEntryManager emgr = roller.getWeblogEntryManager();

        // remove tags
        TypedQuery<WeblogEntryTag> tagQuery = strategy.getNamedQuery("WeblogEntryTag.getByWeblog",
                WeblogEntryTag.class);
        tagQuery.setParameter(1, weblog);
        List<WeblogEntryTag> results = tagQuery.getResultList();
        
        for (WeblogEntryTag tagData : results) {
            if (tagData.getWeblogEntry() != null) {
                tagData.getWeblogEntry().getTags().remove(tagData);
            }
            this.strategy.remove(tagData);
        }
        
        // take this weblog's counts out of the site-wide tag aggregates (its
        // own rows fall to zero and go with them)
        Map<String, Integer> tagDeltas = new HashMap<>();
        for (TagStat stat : emgr.getTags(weblog, null, null, 0, -1)) {
            tagDeltas.merge(stat.getName(), -stat.getCount(), Integer::sum);
        }
        tagAggregates.apply(weblog, tagDeltas);
        
        // delete whatever weblog tag aggregates remain
        Query removeAggs= strategy.getNamedUpdate(
                "WeblogEntryTagAggregate.removeByWeblog");
        removeAggs.setParameter(1, weblog);
        removeAggs.executeUpdate();
        
        // remove associated templates
        TypedQuery<WeblogTemplate> templateQuery = strategy.getNamedQuery("WeblogTemplate.getByWeblog",
                WeblogTemplate.class);
        templateQuery.setParameter(1, weblog);
        List<WeblogTemplate> templates = templateQuery.getResultList();

        for (WeblogTemplate template : templates) {
            this.strategy.remove(template);
        }
        
        // remove static pages
        Query removePages = strategy.getNamedUpdate("WeblogPage.removeByWeblog");
        removePages.setParameter(1, weblog);
        removePages.executeUpdate();

        // roller_weblog_redirect rows FK the weblog with no cascade
        strategy.getNamedUpdate("WeblogRedirect.removeByWeblog")
                .setParameter(1, weblog).executeUpdate();

        // roller_event rows FK the weblog with no cascade
        strategy.getNamedUpdate("RollerEvent.removeByWeblog")
                .setParameter(1, weblog).executeUpdate();

        // roller_form_submission rows FK the weblog with no cascade
        strategy.getNamedUpdate("FormSubmission.removeByWeblog")
                .setParameter(1, weblog).executeUpdate();

        // remove mediafile metadata
        // remove uploaded files
        MediaFileManager mfmgr = roller.getMediaFileManager();
        mfmgr.removeAllFiles(weblog);
        //List<MediaFileDirectory> dirs = mmgr.getMediaFileDirectories(weblog);
        //for (MediaFileDirectory dir : dirs) {
            //this.strategy.remove(dir);
        //}
        this.strategy.flush();

        // remove entry revisions before the entries they hang off. Deleting
        // them per-entry would work too, but one statement is cheaper than one
        // per entry, and the FK means getting the order wrong is not a
        // subtle failure -- the weblog delete simply stops.
        Query removeRevisions = strategy.getNamedUpdate("WeblogEntryRevision.removeByWeblog");
        removeRevisions.setParameter(1, weblog);
        removeRevisions.executeUpdate();

        // remove entries
        TypedQuery<WeblogEntry> refQuery = strategy.getNamedQuery("WeblogEntry.getByWebsite", WeblogEntry.class);
        refQuery.setParameter(1, weblog);
        List<WeblogEntry> entries = refQuery.getResultList();
        for (WeblogEntry entry : entries) {
            emgr.removeWeblogEntry(entry);
        }
        this.strategy.flush();
        
        // delete all weblog categories
        Query removeCategories= strategy.getNamedUpdate("WeblogCategory.removeByWeblog");
        removeCategories.setParameter(1, weblog);
        removeCategories.executeUpdate();

        // remove permissions
        for (WeblogPermission perm : umgr.getWeblogPermissions(weblog)) {
            // enabled == null: a disabled member's permission must go too
            umgr.revokeWeblogPermission(weblog, umgr.getUserByUserName(perm.getUserName(), null),
                    WeblogPermission.ALL_ACTIONS);
        }
        
        // flush the changes before returning. This is required as there is a
        // circular dependency between WeblogCategory and Weblog
        this.strategy.flush();        
    }
    
    /**
     * @see org.apache.roller.weblogger.business.WeblogManager#saveTemplate(WeblogTemplate)
     */
    @Override
    public void saveTemplate(WeblogTemplate template) throws WebloggerException {
        this.strategy.store(template);
        
        // update weblog last modified date.  date updated by saveWeblog()
        roller.getWeblogManager().saveWeblog(template.getWeblog());
    }

    @Override
    public void saveTemplateRendition(CustomTemplateRendition rendition) throws WebloggerException {
        this.strategy.store(rendition);

        // update weblog last modified date.  date updated by saveWeblog()
        roller.getWeblogManager().saveWeblog(rendition.getWeblogTemplate().getWeblog());
    }
    
    @Override
    public void removeTemplate(WeblogTemplate template) throws WebloggerException {
        this.strategy.remove(template);
        // update weblog last modified date.  date updated by saveWeblog()
        roller.getWeblogManager().saveWeblog(template.getWeblog());
    }
    
    @Override
    public void addWeblog(Weblog newWeblog) throws WebloggerException {
        this.strategy.store(newWeblog);
        this.strategy.flush();
        this.addWeblogContents(newWeblog);
    }
    
    private void addWeblogContents(Weblog newWeblog)
    throws WebloggerException {
        
        // grant weblog creator ADMIN permission
        List<String> actions = new ArrayList<>();
        actions.add(WeblogPermission.ADMIN);
        roller.getUserManager().grantWeblogPermission(
                newWeblog, roller.getUserManager().getUserByUserName(newWeblog.getCreatorUserName()), actions);
        
        String cats = WebloggerConfig.getProperty("newuser.categories");
        if (cats != null) {
            String[] splitcats = cats.split(",");
            for (String split : splitcats) {
                if (split.isBlank()) {
                    continue;
                }
                WeblogCategory c = new WeblogCategory(
                        newWeblog,
                        split,
                        null,
                        null );
                this.strategy.store(c);
            }
        }

        this.strategy.store(newWeblog);

        roller.getMediaFileManager().createDefaultMediaFileDirectory(newWeblog);

        // flush so that all data up to this point can be available in db
        this.strategy.flush();

    }
    
    @Override
    public Weblog getWeblog(String id) throws WebloggerException {
        return (Weblog) this.strategy.load(Weblog.class, id);
    }
    
    @Override
    public Weblog getWeblogByHandle(String handle) throws WebloggerException {
        return getWeblogByHandle(handle, Boolean.TRUE);
    }
    
    /**
     * Return weblog specified by handle.
     */
    @Override
    public Weblog getWeblogByHandle(String handle, Boolean visible) throws WebloggerException {
        
        if (handle == null) {
            throw new WebloggerException("Handle cannot be null");
        } else if (!isAlphanumeric(handle)) {
            throw new WebloggerException("Invalid handle: '"+handle+"'");
        }
        
        // check cache first
        // NOTE: if we ever allow changing handles then this needs updating
        String blogID = this.weblogHandleToIdMap.get(handle);
        if(blogID != null) {
            
            Weblog weblog = this.getWeblog(blogID);
            if (weblog != null) {
                // only return weblog if enabled status matches
                if(visible == null || visible.equals(weblog.getVisible())) {
                    log.debug("weblogHandleToId CACHE HIT - {}", handle);
                    return weblog;
                }
            } else {
                // mapping hit with lookup miss?  mapping must be old, remove it
                this.weblogHandleToIdMap.remove(handle);
            }
        }
        
        TypedQuery<Weblog> query = strategy.getNamedQuery("Weblog.getByHandle", Weblog.class);
        query.setParameter(1, handle);
        Weblog weblog;
        try {
            weblog = query.getSingleResult();
        } catch (NoResultException e) {
            weblog = null;
        }
        
        // add mapping to cache
        if(weblog != null) {
            log.debug("weblogHandleToId CACHE MISS - {}", handle);
            this.weblogHandleToIdMap.put(weblog.getHandle(), weblog.getId());
        }
        
        if(weblog != null &&
                (visible == null || visible.equals(weblog.getVisible()))) {
            return weblog;
        } else {
            return null;
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>Nothing enforces uniqueness on {@code newsletter_list_uuid} -- V016
     * adds no unique constraint, and settings validation only checks the
     * uuid's format -- so more than one weblog can legitimately share a
     * list (a family of blogs sharing one newsletter). {@code getResultList}
     * rather than {@code getSingleResult} tolerates that instead of throwing
     * {@code NonUniqueResultException}; the named query's {@code ORDER BY
     * handle} makes "the first result" the same weblog every time, and that
     * first-by-handle weblog is the one credited with the subscribe event.
     */
    @Override
    public Weblog getWeblogByNewsletterListUuid(String listUuid) throws WebloggerException {
        if (listUuid == null) {
            return null;
        }
        TypedQuery<Weblog> query = strategy.getNamedQuery(
                "Weblog.getByNewsletterListUuid", Weblog.class);
        query.setParameter(1, listUuid);
        List<Weblog> results = query.getResultList();
        return results.isEmpty() ? null : results.get(0);
    }

    @Override
    public Weblog getWeblogByCustomDomain(String host) throws WebloggerException {
        // A null host must not reach the query: it would match every weblog
        // whose custom_domain is NULL and resolve an unclaimed hostname to an
        // arbitrary weblog.
        if (host == null || host.isBlank()) {
            return null;
        }
        TypedQuery<Weblog> query = strategy.getNamedQuery(
                "Weblog.getByCustomDomain", Weblog.class);
        query.setParameter(1, host);
        List<Weblog> results = query.getResultList();
        return results.isEmpty() ? null : results.get(0);
    }

    /**
     * Get weblogs of a user
     */
    @Override
    public List<Weblog> getWeblogs(
            Boolean enabled, Boolean active,
            Date startDate, Date endDate, int offset, int length) throws WebloggerException {
        
        //if (endDate == null) endDate = new Date();
                      
        List<Object> params = new ArrayList<>();
        int size = 0;
        String queryString;
        StringBuilder whereClause = new StringBuilder();

        queryString = "SELECT w FROM Weblog w";

        if (startDate != null) {
            Timestamp start = new Timestamp(startDate.getTime());
            if (whereClause.length() > 0) {
                whereClause.append(" AND ");
            }
            params.add(size++, start);
            whereClause.append(" w.dateCreated > ?").append(size);
        }
        if (endDate != null) {
            Timestamp end = new Timestamp(endDate.getTime());
            if (whereClause.length() > 0) {
                whereClause.append(" AND ");
            }
            params.add(size++, end);
            whereClause.append(" w.dateCreated < ?").append(size);
        }
        if (enabled != null) {
            if (whereClause.length() > 0) {
                whereClause.append(" AND ");
            }
            params.add(size++, enabled);
            whereClause.append(" w.visible = ?").append(size);
        }
        if (active != null) {
            if (whereClause.length() > 0) {
                whereClause.append(" AND ");
            }
            params.add(size++, active);
            whereClause.append(" w.active = ?").append(size);
        }

        // The WHERE keyword may only appear when there is an actual predicate
        // to follow it -- with every filter left null (as VirtualHostRegistry
        // does, deliberately, to read every weblog regardless of enabled/
        // active status) whereClause is empty, and "WHERE" with nothing after
        // it is invalid JPQL that EclipseLink rejects at query-creation time.
        StringBuilder fullQuery = new StringBuilder(queryString);
        if (whereClause.length() > 0) {
            fullQuery.append(" WHERE").append(whereClause);
        }
        fullQuery.append(" ORDER BY w.dateCreated DESC, w.handle");

        TypedQuery<Weblog> query = strategy.getDynamicQuery(fullQuery.toString(), Weblog.class);
        if (offset != 0) {
            query.setFirstResult(offset);
        }
        if (length != -1) {
            query.setMaxResults(length);
        }
        bindParams(query, params);

        return query.getResultList();
    }

    @Override
    public List<Weblog> getUserWeblogs(User user, boolean enabledOnly) throws WebloggerException {
        List<Weblog> weblogs = new ArrayList<>();
        if (user == null) {
            return weblogs;
        }
        List<WeblogPermission> perms = roller.getUserManager().getWeblogPermissions(user);
        for (WeblogPermission perm : perms) {
            Weblog weblog = getWeblogByHandle(perm.getObjectId(), null);
            if ((!enabledOnly || weblog.getVisible()) && BooleanUtils.isTrue(weblog.getActive())) {
                weblogs.add(weblog);
            }
        }
        return weblogs;
    }
    
    @Override
    public List<User> getWeblogUsers(Weblog weblog, boolean enabledOnly) throws WebloggerException {
        List<User> users = new ArrayList<>();
        List<WeblogPermission> perms = roller.getUserManager().getWeblogPermissions(weblog);
        for (WeblogPermission perm : perms) {
            // enabled == null so that enabledOnly=false really lists disabled members
            User user = roller.getUserManager().getUserByUserName(perm.getUserName(), null);
            if (user == null) {
                log.error("ERROR user is null, userName:{}", perm.getUserName());
                continue;
            }
            if (!enabledOnly || user.getEnabled()) {
                users.add(user);
            }
        }
        return users;
    }

    @Override
    public WeblogTemplate getTemplate(String id) throws WebloggerException {
        // Don't hit database for templates stored on disk
        if (id != null && id.endsWith(".vm")) {
            return null;
        }
        
        return (WeblogTemplate)this.strategy.load(WeblogTemplate.class,id);
    }
    
    /**
     * Use JPA directly because Weblogger's Query API does too much allocation.
     */
    @Override
    public WeblogTemplate getTemplateByLink(Weblog weblog, String templateLink)
    throws WebloggerException {
        
        if (weblog == null) {
            throw new WebloggerException("userName is null");
        }

        if (templateLink == null) {
            throw new WebloggerException("templateLink is null");
        }

        TypedQuery<WeblogTemplate> query = strategy.getNamedQuery("WeblogTemplate.getByWeblog&Link",
                WeblogTemplate.class);
        query.setParameter(1, weblog);
        query.setParameter(2, templateLink);
        try {
            return query.getSingleResult();
        } catch (NoResultException e) {
            return null;
        }
    }
    
    /**
     * @see org.apache.roller.weblogger.business.WeblogManager#getTemplateByAction(Weblog, ComponentType)
     */
    @Override
    public WeblogTemplate getTemplateByAction(Weblog weblog, ComponentType action)
            throws WebloggerException {
        
        if (weblog == null) {
            throw new WebloggerException("weblog is null");
        }

        if (action == null) {
            throw new WebloggerException("Action name is null");
        }
        
        TypedQuery<WeblogTemplate> query = strategy.getNamedQuery("WeblogTemplate.getByAction",
                WeblogTemplate.class);
        query.setParameter(1, weblog);
        query.setParameter(2, action);
        try {
            return query.getSingleResult();
        } catch (NoResultException e) {
            return null;
        }        
    }
    
    /**
     * @see org.apache.roller.weblogger.business.WeblogManager#getTemplateByName(Weblog, java.lang.String)
     */
    @Override
    public WeblogTemplate getTemplateByName(Weblog weblog, String templateName)
    throws WebloggerException {
        
        if (weblog == null) {
            throw new WebloggerException("weblog is null");
        }
        
        if (templateName == null) {
            throw new WebloggerException("Template name is null");
        }
        
        TypedQuery<WeblogTemplate> query = strategy.getNamedQuery("WeblogTemplate.getByWeblog&Name",
                WeblogTemplate.class);
        query.setParameter(1, weblog);
        query.setParameter(2, templateName);
        try {
            return query.getSingleResult();
        } catch (NoResultException e) {
            return null;
        }
    }

    /**
     * @see org.apache.roller.weblogger.business.WeblogManager#getTemplates(Weblog)
     */
    @Override
    public List<WeblogTemplate> getTemplates(Weblog weblog) throws WebloggerException {
        if (weblog == null) {
            throw new WebloggerException("weblog is null");
        }
        TypedQuery<WeblogTemplate> q = strategy.getNamedQuery(
                "WeblogTemplate.getByWeblogOrderByName", WeblogTemplate.class);
        q.setParameter(1, weblog);
        return q.getResultList();
    }

    
    @Override
    public Map<String, Long> getWeblogHandleLetterMap() throws WebloggerException {
        String lc = "ABCDEFGHIJKLMNOPQRSTUVWXYZ";
        Map<String, Long> results = new TreeMap<>();
        TypedQuery<Long> query = strategy.getNamedQuery(
                "Weblog.getCountByHandleLike", Long.class);
        for (int i=0; i<26; i++) {
            char currentChar = lc.charAt(i);
            query.setParameter(1, currentChar + "%");
            List<Long> row = query.getResultList();
            Long count = row.get(0);
            results.put(String.valueOf(currentChar), count);
        }
        return results;
    }
    
    @Override
    public List<Weblog> getWeblogsByLetter(char letter, int offset, int length)
    throws WebloggerException {
        TypedQuery<Weblog> query = strategy.getNamedQuery(
                "Weblog.getByLetterOrderByHandle", Weblog.class);
        query.setParameter(1, letter + "%");
        if (offset != 0) {
            query.setFirstResult(offset);
        }
        if (length != -1) {
            query.setMaxResults(length);
        }
        return query.getResultList();
    }
    
    /**
     * Get count of weblogs, active and inactive
     */
    @Override
    public long getWeblogCount() throws WebloggerException {
        List<Long> results = strategy.getNamedQuery(
                "Weblog.getCountAllDistinct", Long.class).getResultList();
        return results.get(0);
    }

    /**
     * Returns true if alphanumeric or '_'.
     */
    private boolean isAlphanumeric(String str) {
        if (str == null) {
            return false;
        }
        for (int i = 0; i < str.length(); i++) {
            if (!Character.isLetterOrDigit(str.charAt(i)) && str.charAt(i) != '_') {
                return false;
            }
        }
        return true;
    }

    private static void bindParams(Query query, List<Object> params) {
        for (int i = 0; i < params.size(); i++) {
            query.setParameter(i + 1, params.get(i));
        }
    }

}
