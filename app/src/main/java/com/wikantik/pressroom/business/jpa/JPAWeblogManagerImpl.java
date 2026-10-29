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

package com.wikantik.pressroom.business.jpa;

import org.apache.commons.lang3.BooleanUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.config.WebloggerConfig;

import jakarta.persistence.NoResultException;
import jakarta.persistence.Query;
import jakarta.persistence.TypedQuery;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import com.wikantik.pressroom.business.FileContentManager;
import com.wikantik.pressroom.business.MediaFileManager;
import com.wikantik.pressroom.business.UserManager;
import com.wikantik.pressroom.business.VirtualHostRegistry;
import com.wikantik.pressroom.business.WeblogEntryManager;
import com.wikantik.pressroom.business.WeblogManager;
import com.wikantik.pressroom.business.search.IndexManager;
import com.wikantik.pressroom.business.Weblogger;
import com.wikantik.pressroom.pojos.CustomTemplateRendition;
import com.wikantik.pressroom.pojos.MediaFile;
import com.wikantik.pressroom.pojos.MediaFileDirectory;
import com.wikantik.pressroom.pojos.ThemeTemplate.ComponentType;
import com.wikantik.pressroom.pojos.User;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.pojos.WeblogCategory;
import com.wikantik.pressroom.pojos.WeblogEntry;
import com.wikantik.pressroom.pojos.WeblogEntryTag;
import com.wikantik.pressroom.pojos.WeblogPermission;
import com.wikantik.pressroom.pojos.WeblogTemplate;


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

    /** A lookup found no site space; see {@link #getSiteSpace}. */
    private volatile boolean noSiteSpace;
    /** Counts site-space creations, so a lookup racing one cannot record "absent" after it. */
    private int siteSpaceCreations;
    private final Object siteSpaceLock = new Object();

    /**
     * Remembered for {@link #getTemplateByAction}: what was found for an
     * action is a template id. Real ids are UUIDs, never empty.
     */
    private static final String NO_TEMPLATE = "";

    /**
     * Each weblog's template ids by action, at the stamp they were read at.
     * Every render asks for the stylesheet override (and a custom theme for
     * its templates) and the answer is usually "none", which was never
     * remembered: one query per lookup, several per page.
     */
    private final WeblogStampedCache<Map<ComponentType, String>> templateIdsByAction;

    public JPAWeblogManagerImpl(Weblogger roller, JPAPersistenceStrategy strat,
                                VirtualHostRegistry virtualHosts) {
        this.virtualHosts = virtualHosts;
        log.debug("Instantiating JPA Weblog Manager");
        this.roller = roller;
        this.strategy = strat;
        this.tagAggregates = new TagAggregateStore(strat);
        this.templateIdsByAction = new WeblogStampedCache<>(strat);
    }
    
    
    @Override
    public void release() {}

    /** Whether template lookups are remembered for this weblog; for tests. */
    boolean remembersTemplatesOf(String weblogId) {
        return templateIdsByAction.remembers(weblogId);
    }
    
    
    /**
     * Update existing weblog.
     */
    @Override
    public void saveWeblog(Weblog weblog) throws WebloggerException {

        weblog.touchLastModified();
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

        // A deleted weblog's stamp never moves again, so nothing would ever
        // replace its slot; once the delete is durable it is simply dropped.
        String weblogId = weblog.getId();
        strategy.runAfterCommit(() -> templateIdsByAction.forget(weblogId));

        // Its search documents go too, once the delete is durable (a rolled
        // back delete keeps a weblog whose index is intact). Nothing removed
        // them before: search dropped them from the page, their entries being
        // gone, but they still counted towards every total.
        IndexManager index = roller.getIndexManager();
        strategy.runAfterCommit(() -> {
            try {
                index.removeWeblogIndex(weblog);
            } catch (WebloggerException e) {
                log.warn("Could not remove deleted weblog {} from the search index", weblog.getHandle(), e);
            }
        });
    }
    
    /**
     * Removes everything that hangs off a weblog, in the caller's transaction.
     *
     * <p>Nothing here commits: a failure anywhere leaves the weblog whole once
     * the caller's unit of work is released. Until 2026-10-22 this committed
     * three times on the way, so a failure after the first commit left a
     * weblog with no templates, pages or media, and after the second one with
     * no entries. The {@link #writePending} calls write what is queued so far
     * without committing: the bulk deletes after them must see the rows the
     * unit of work removed.
     *
     * <p>Media files leave the disk only once the deletion has committed
     * ({@link JPAPersistenceStrategy#runAfterCommit}): a file deleted before
     * the commit is gone for good if the commit then fails.
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
        
        Set<String> tagNames = new TreeSet<>();
        for (WeblogEntryTag tagData : results) {
            if (tagData.getWeblogEntry() != null) {
                tagData.getWeblogEntry().getTags().remove(tagData);
            }
            tagNames.add(tagData.getName());
            this.strategy.remove(tagData);
        }
        
        // Recount at commit, like any other change to tag rows, every tag
        // whose rows this removes -- named from the rows themselves, so a
        // tag a concurrent publish is counting is recounted after it -- and
        // every tag the weblog then still has an aggregate row for. Each
        // falls to zero and goes, and the site-wide sums lose it. Nothing
        // deletes the rows outright: that took them out of the lock order.
        tagAggregates.recount(weblog, tagNames);
        tagAggregates.recountWeblog(weblog);
        
        // remove associated templates
        TypedQuery<WeblogTemplate> templateQuery = strategy.getNamedQuery("WeblogTemplate.getByWeblog",
                WeblogTemplate.class);
        templateQuery.setParameter(1, weblog);
        List<WeblogTemplate> templates = templateQuery.getResultList();

        for (WeblogTemplate template : templates) {
            this.strategy.remove(template);
        }
        
        // remove static pages
        roller.getWeblogPageManager().removePages(weblog);

        // roller_weblog_redirect rows FK the weblog with no cascade
        strategy.getNamedUpdate("WeblogRedirect.removeByWeblog")
                .setParameter(1, weblog).executeUpdate();

        // roller_event rows FK the weblog with no cascade
        strategy.getNamedUpdate("PressroomEvent.removeByWeblog")
                .setParameter(1, weblog).executeUpdate();

        // roller_form_submission rows FK the weblog with no cascade
        strategy.getNamedUpdate("FormSubmission.removeByWeblog")
                .setParameter(1, weblog).executeUpdate();

        removeMediaFiles(weblog);
        writePending();

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
        writePending();
        
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
        
        // write the changes before returning. This is required as there is a
        // circular dependency between WeblogCategory and Weblog
        writePending();
    }

    /**
     * The weblog's media directories, files and file tags (the directory
     * cascades to its files, a file to its tags), and, once that commits,
     * each file's original, thumbnail and renditions on disk.
     * {@code MediaFileManager.removeAllFiles} is not used here: it commits per
     * directory.
     */
    private void removeMediaFiles(Weblog weblog) throws WebloggerException {
        List<String> fileIds = new ArrayList<>();
        MediaFileManager mfmgr = roller.getMediaFileManager();
        for (MediaFileDirectory dir : new ArrayList<>(mfmgr.getMediaFileDirectories(weblog))) {
            for (MediaFile file : dir.getMediaFiles()) {
                fileIds.add(file.getId());
            }
            weblog.getMediaFileDirectories().remove(dir);
            strategy.remove(dir);
        }
        if (fileIds.isEmpty()) {
            return;
        }
        FileContentManager content = roller.getFileContentManager();
        strategy.runAfterCommit(() -> {
            for (String fileId : fileIds) {
                JPAMediaFileManagerImpl.deleteContentQuietly(content, weblog, fileId);
            }
        });
    }

    /** Writes the unit of work's pending changes, inside its transaction, without committing. */
    private void writePending() {
        strategy.getEntityManager(true).flush();
    }
    
    /**
     * @see com.wikantik.pressroom.business.WeblogManager#saveTemplate(WeblogTemplate)
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
        this.addWeblogContents(newWeblog, true);
    }

    /**
     * The site space is asked for on hot paths -- the public mapper on every
     * single-segment url no blog claims, the footer's page reference -- so
     * neither answer costs a query once known. Found, it comes through
     * {@link #getWeblogByHandle}'s handle-to-id cache. Absent, that is
     * remembered here until {@link #getOrCreateSiteSpace} -- the one way a site
     * space comes to exist -- creates it or finds it (so a stale absence heals
     * when the admin's main menu next asks). The absence is recorded only by a
     * lookup no creation ran during, and a creation forgets it only after its
     * commit, so it cannot outlive a site space created here. A removed site
     * space needs no word: the next lookup finds nothing and remembers that.
     */
    @Override
    public Weblog getSiteSpace() throws WebloggerException {
        if (noSiteSpace) {
            return null;
        }
        Weblog byHandle = getWeblogByHandle(Weblog.SITE_SPACE_HANDLE, null);
        if (byHandle != null && byHandle.isSiteSpace()) {
            return byHandle;
        }
        int seen;
        synchronized (siteSpaceLock) {
            seen = siteSpaceCreations;
        }
        Weblog found = querySiteSpace();
        if (found == null) {
            synchronized (siteSpaceLock) {
                // a creation that ran while this looked must not be forgotten
                if (seen == siteSpaceCreations) {
                    noSiteSpace = true;
                }
            }
        }
        return found;
    }

    /**
     * Forget a remembered absence, and stop any lookup already under way from
     * recording one. Called once the site space is committed: when it is
     * created, and whenever getOrCreateSiteSpace finds it, so an absence that
     * went stale anyway (a site space that arrived some other way) heals the
     * next time the admin's main menu asks.
     */
    private void siteSpaceExists() {
        synchronized (siteSpaceLock) {
            siteSpaceCreations++;
            noSiteSpace = false;
        }
    }

    private Weblog querySiteSpace() throws WebloggerException {
        TypedQuery<Weblog> query = strategy.getDynamicQuery(
                "SELECT w FROM Weblog w WHERE w.siteSpace = true", Weblog.class);
        List<Weblog> found = query.getResultList();
        return found.isEmpty() ? null : found.get(0);
    }

    @Override
    public Weblog getOrCreateSiteSpace(User creator) throws WebloggerException {
        // asks the database, never the remembered absence: this is the path
        // that must not create a second site space
        Weblog existing = querySiteSpace();
        if (existing != null) {
            // it exists, whatever getSiteSpace remembers: a stale absence heals here
            siteSpaceExists();
            return existing;
        }
        Weblog holder = getWeblogByHandle(Weblog.SITE_SPACE_HANDLE, null);
        if (holder != null) {
            throw new WebloggerException("Cannot create the site space: weblog '"
                    + Weblog.SITE_SPACE_HANDLE + "' already exists and is not the site space");
        }
        Weblog site = new Weblog(Weblog.SITE_SPACE_HANDLE, creator.getUserName(), "Site", "",
                creator.getEmailAddress(), "frontpage", creator.getLocale(), creator.getTimeZone());
        site.setSiteSpace(true);
        site.setDateCreated(new Date());
        this.strategy.store(site);
        // flush() commits, so the row is visible to every lookup that starts
        // after the absence is forgotten below
        this.strategy.flush();
        siteSpaceExists();
        // system-owned: no permission row for the creator
        this.addWeblogContents(site, false);
        return site;
    }

    private void addWeblogContents(Weblog newWeblog, boolean grantCreator)
    throws WebloggerException {
        
        if (grantCreator) {
            // grant weblog creator ADMIN permission
            List<String> actions = new ArrayList<>();
            actions.add(WeblogPermission.ADMIN);
            roller.getUserManager().grantWeblogPermission(
                    newWeblog, roller.getUserManager().getUserByUserName(newWeblog.getCreatorUserName()), actions);
        }
        
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
        } else if (!Weblog.SITE_SPACE_HANDLE.equals(handle) && !isAlphanumeric(handle)) {
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
        // the site space is hidden from every listing
        whereClause.append(" w.siteSpace = false");

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
     * @see com.wikantik.pressroom.business.WeblogManager#getTemplateByAction(Weblog, ComponentType)
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

        Map<ComponentType, String> known = templateIdsByAction.get(weblog);
        String knownId = known == null ? null : known.get(action);
        if (NO_TEMPLATE.equals(knownId)) {
            return null;
        }
        if (knownId != null) {
            WeblogTemplate template = (WeblogTemplate) strategy.load(WeblogTemplate.class, knownId);
            // A row deleted without a stamp move (none should be) is a miss,
            // never an answer.
            if (template != null && template.getAction() == action
                    && weblog.getId().equals(template.getWeblog().getId())) {
                return template;
            }
        }
        
        TypedQuery<WeblogTemplate> query = strategy.getNamedQuery("WeblogTemplate.getByAction",
                WeblogTemplate.class);
        query.setParameter(1, weblog);
        query.setParameter(2, action);
        WeblogTemplate found;
        try {
            found = query.getSingleResult();
        } catch (NoResultException e) {
            found = null;
        }

        Map<ComponentType, String> remembered = new EnumMap<>(ComponentType.class);
        if (known != null) {
            remembered.putAll(known);
        }
        remembered.put(action, found == null ? NO_TEMPLATE : found.getId());
        templateIdsByAction.put(weblog, Collections.unmodifiableMap(remembered));
        return found;
    }
    
    /**
     * @see com.wikantik.pressroom.business.WeblogManager#getTemplateByName(Weblog, java.lang.String)
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
     * @see com.wikantik.pressroom.business.WeblogManager#getTemplates(Weblog)
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
