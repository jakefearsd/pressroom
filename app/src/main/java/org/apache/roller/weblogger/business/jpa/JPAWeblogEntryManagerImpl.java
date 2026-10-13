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

import java.util.*;
import java.text.SimpleDateFormat;
import java.sql.Timestamp;
import jakarta.persistence.NoResultException;
import jakarta.persistence.Query;
import jakarta.persistence.TypedQuery;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.roller.util.RollerConstants;
import org.apache.roller.weblogger.WebloggerException;
import org.apache.roller.weblogger.business.Weblogger;
import org.apache.roller.weblogger.pojos.RollerEvent;
import org.apache.roller.weblogger.pojos.User;
import org.apache.roller.weblogger.pojos.WeblogEntrySearchCriteria;
import org.apache.roller.weblogger.pojos.TagStat;
import org.apache.roller.weblogger.pojos.TagStatComparator;
import org.apache.roller.weblogger.pojos.TagStatCountComparator;
import org.apache.roller.weblogger.pojos.WeblogCategory;
import org.apache.roller.weblogger.pojos.WeblogEntry;
import org.apache.roller.weblogger.pojos.WeblogEntry.PubStatus;
import org.apache.roller.weblogger.pojos.WeblogEntryTagAggregate;
import org.apache.roller.weblogger.pojos.WeblogEntryTag;
import org.apache.roller.weblogger.pojos.Weblog;
import org.apache.roller.weblogger.pojos.WeblogEntryAttribute;
import org.apache.roller.weblogger.pojos.WeblogEntryRevision;
import org.apache.roller.weblogger.config.WebloggerRuntimeConfig;
import org.apache.roller.util.DateUtil;
import org.apache.roller.weblogger.business.WeblogEntryManager;


/**
 * JPAWeblogManagerImpl.java
 *
 * Created on May 31, 2006, 4:08 PM
 *
 */
public class JPAWeblogEntryManagerImpl implements WeblogEntryManager {
    
    private static final Logger log = LoggerFactory.getLogger(JPAWeblogEntryManagerImpl.class);
    
    private final Weblogger roller;
    private final JPAPersistenceStrategy strategy;
    
    // cached mapping of entryAnchors -> entryIds
    private final Map<String, String> entryAnchorToIdMap = Collections.synchronizedMap(new HashMap<>());
    
    private static final Comparator<TagStat> TAG_STAT_NAME_COMPARATOR = new TagStatComparator();
    
    private static final Comparator<TagStat> TAG_STAT_COUNT_REVERSE_COMPARATOR =
            Collections.reverseOrder(TagStatCountComparator.getInstance());


    public JPAWeblogEntryManagerImpl(Weblogger roller, JPAPersistenceStrategy strategy) {
        log.debug("Instantiating JPA Weblog Manager");
        this.roller = roller;
        this.strategy = strategy;
    }
    
    /**
     * @inheritDoc
     */
    @Override
    public void saveWeblogCategory(WeblogCategory cat) throws WebloggerException {
        boolean exists = getWeblogCategory(cat.getId()) != null;
        if (!exists && isDuplicateWeblogCategoryName(cat)) {
            throw new WebloggerException("Duplicate category name, cannot save category");
        }

        // update weblog last modified date.  date updated by saveWebsite()
        roller.getWeblogManager().saveWeblog(cat.getWeblog());
        this.strategy.store(cat);
    }
    
    /**
     * @inheritDoc
     */
    @Override
    public void removeWeblogCategory(WeblogCategory cat)
    throws WebloggerException {
        // All entries, trash included -- see WeblogEntrySearchCriteria.forCategory
        // for why a trashed entry still blocks deleting its category.
        if(!getWeblogEntries(WeblogEntrySearchCriteria.forCategory(cat, false)).isEmpty()) {
            throw new WebloggerException("Cannot remove category with entries");
        }

        // A weblog must always keep at least one category, because
        // saveWeblogEntry() below falls back to "the first category found"
        // whenever an entry arrives without one -- which is what happens when
        // the editor's category <select> has nothing to offer. Categories.jsp
        // already hides Delete on the last row, but that only stops the UI; a
        // direct POST to categoryRemove!remove.rol took the last category out
        // from under that fallback and every subsequent entry save died on
        // NoSuchElementException, with no way back through the UI since adding
        // a category is a different screen than the one that now fails.
        if (cat.getWeblog().getWeblogCategories().size() <= 1) {
            throw new WebloggerException("Cannot remove a weblog's last category");
        }

        cat.getWeblog().getWeblogCategories().remove(cat);

        // remove cat
        this.strategy.remove(cat);

        // update weblog last modified date.  date updated by saveWebsite()
        roller.getWeblogManager().saveWeblog(cat.getWeblog());
    }

    /**
     * @inheritDoc
     */
    @Override
    public void moveWeblogCategoryContents(WeblogCategory srcCat,
            WeblogCategory destCat)
            throws WebloggerException {
        
        // get all entries in category and subcats
        // Trash included: a trashed entry must move with the category, not be
        // left pointing at a row about to be removed (WeblogEntrySearchCriteria.forCategory).
        List<WeblogEntry> results = getWeblogEntries(WeblogEntrySearchCriteria.forCategory(srcCat, false));
        
        // Loop through entries in src cat, assign them to dest cat
        Weblog website = destCat.getWeblog();
        for (WeblogEntry entry : results) {
            entry.setCategory(destCat);
            entry.setWebsite(website);
            this.strategy.store(entry);
        }
    }
    
    /**
     * @inheritDoc
     */
    // TODO: perhaps the createAnchor() and queuePings() items should go outside this method?
    @Override
    public void saveWeblogEntry(WeblogEntry entry) throws WebloggerException {

        if (entry.getCategory() == null) {
            // Entry is invalid without category, so use the first one found.
            // removeWeblogCategory() guarantees there is one; if that invariant
            // is ever breached, say so rather than letting an iterator throw
            // NoSuchElementException with no hint of what is actually wrong.
            Iterator<WeblogCategory> cats =
                    entry.getWebsite().getWeblogCategories().iterator();
            if (!cats.hasNext()) {
                throw new WebloggerException("Weblog "
                        + entry.getWebsite().getHandle()
                        + " has no categories; cannot save an entry without one");
            }
            entry.setCategory(cats.next());
        }

        // Entry is invalid without local. if missing use weblog default
        if (entry.getLocale() == null) {
            entry.setLocale(entry.getWebsite().getLocale());
        }
        
        if (entry.getAnchor() == null || entry.getAnchor().isBlank()) {
            entry.setAnchor(this.createAnchor(entry));
        }
        
        if (entry.isPublished()) {
            // tag aggregates are updated only when entry published in order for
            // tag cloud counts to match published articles
            if (entry.getRefreshAggregates()) {
                // blog entry wasn't published before, so all tags need to be incremented
                for (WeblogEntryTag tag : entry.getTags()) {
                    updateTagCount(tag.getName(), entry.getWebsite(), 1);
                }
            } else {
                // only new tags need to be incremented
                for (WeblogEntryTag tag : entry.getAddedTags()) {
                    updateTagCount(tag.getName(), entry.getWebsite(), 1);
                }
            }
        } else {
            if (entry.getRefreshAggregates()) {
                // blog entry no longer published so need to reduce aggregate count
                for (WeblogEntryTag tag : entry.getTags()) {
                    updateTagCount(tag.getName(), entry.getWebsite(), -1);
                }
            }
        }

        for (WeblogEntryTag tag : entry.getRemovedTags()) {
            removeWeblogEntryTag(tag);
        }

        // if the entry was published to future, set status as SCHEDULED
        // we only consider an entry future published if it is scheduled
        // more than 1 minute into the future
        if (PubStatus.PUBLISHED.equals(entry.getStatus()) &&
                entry.getPubTime().after(new Date(System.currentTimeMillis() + RollerConstants.MIN_IN_MS))) {
            entry.setStatus(PubStatus.SCHEDULED);
        }
        
        // Store value object (creates new or updates existing)
        entry.setUpdateTime(new Timestamp(new Date().getTime()));

        this.strategy.store(entry);

        // AFTER storing the entry, never before: the revision carries a
        // foreign key to it, and an entry that is not yet in the persistence
        // context resolves to a null entryid rather than to a row. What the
        // revision RECORDS is still the pre-save content -- that was captured
        // when the entry was loaded, not now.
        recordRevision(entry);

        // First arrival at PUBLISHED is a first-party outcome the analytics
        // tier joins against traffic. Best-effort: an event insert must never
        // fail the save that produced it.
        if (entry.getStatus() == PubStatus.PUBLISHED
                && entry.getLoadedStatus() != PubStatus.PUBLISHED) {
            try {
                RollerEvent event = new RollerEvent();
                event.setWeblog(entry.getWebsite());
                event.setEventType(RollerEvent.EventType.ENTRY_PUBLISHED);
                event.setEntryAnchor(entry.getAnchor());
                event.setOccurredAt(new Timestamp(System.currentTimeMillis()));
                roller.getEventManager().record(event);
            } catch (Exception ex) {
                log.warn("Could not record entry_published event for {}",
                        entry.getAnchor(), ex);
            }
        }

        // The entry's new content is now what a further save in this session
        // would be replacing.
        entry.snapshotLoadedContent();
        
        // update weblog last modified date.  date updated by saveWebsite()
        if(entry.isPublished()) {
            roller.getWeblogManager().saveWeblog(entry.getWebsite());
        }
        
    }
    
    /**
     * Records what this save is about to displace, and prunes to the
     * configured retention.
     *
     * <p>Retention lives in {@code entry.revisions.retention}: -1 (the
     * default) keeps everything, 0 records nothing, and a positive n keeps the
     * n newest. Pruning happens in the same transaction as the save, so the
     * table cannot drift above the cap between calls.
     *
     * <p>A failure here must not cost the author their save -- revisions are a
     * convenience, the entry is the work -- so problems are logged rather than
     * thrown.
     */
    private void recordRevision(WeblogEntry entry) {
        try {
            int retention = WebloggerRuntimeConfig.getIntProperty("entry.revisions.retention");
            if (retention == 0) {
                return;
            }

            WeblogEntryRevision revision = entry.contentBeingReplaced(entry.getCreatorUserName());
            if (revision == null) {
                return;
            }
            // Point at the instance that was just stored. The snapshot was
            // built at load time and may hold a reference that has since been
            // detached, which writes as a null foreign key.
            revision.setWeblogEntry(entry);

            // Read the existing rows BEFORE storing the new one: the store is
            // still pending at this point, so a query would not count it and
            // the table would settle one row above the cap. Keeping
            // retention-1 of the old rows plus the new one is what makes the
            // total come out at exactly retention.
            List<WeblogEntryRevision> existing =
                    retention > 0 ? getRevisions(entry) : List.<WeblogEntryRevision>of();

            this.strategy.store(revision);

            // Only a positive retention prunes. A negative one keeps
            // everything, and starting this loop at retention-1 would index
            // the empty list at -2.
            if (retention > 0) {
                for (int i = retention - 1; i < existing.size(); i++) {
                    this.strategy.remove(existing.get(i));
                }
            }
        } catch (Exception e) {
            log.warn("Could not record a revision for entry {}; the save itself is unaffected",
                    entry.getId(), e);
        }
    }

    @Override
    public List<WeblogEntryRevision> getRevisions(WeblogEntry entry) throws WebloggerException {
        if (entry == null || entry.getId() == null) {
            return Collections.emptyList();
        }
        TypedQuery<WeblogEntryRevision> query = strategy.getNamedQuery(
                "WeblogEntryRevision.getByEntry", WeblogEntryRevision.class);
        query.setParameter(1, entry);
        return query.getResultList();
    }

    @Override
    public WeblogEntryRevision getRevision(String id) throws WebloggerException {
        if (id == null) {
            return null;
        }
        return (WeblogEntryRevision) strategy.load(WeblogEntryRevision.class, id);
    }

    @Override
    public void removeWeblogEntry(WeblogEntry entry) throws WebloggerException {
        Weblog weblog = entry.getWebsite();

        // Revisions FK the entry, so they go first. The database would cascade
        // anyway, but doing it here keeps the persistence context in step --
        // EclipseLink does not know about a cascade it did not issue.
        Query removeRevisions = strategy.getNamedUpdate("WeblogEntryRevision.removeByEntry");
        removeRevisions.setParameter(1, entry);
        removeRevisions.executeUpdate();

        // reader comments cascade in the database too; removing the entities
        // keeps the shared cache from serving them after the entry is gone
        roller.getCommentManager().removeCommentsForEntry(entry);

        // remove tag & tag aggregates
        if (entry.getTags() != null) {
            for (WeblogEntryTag tag : entry.getTags()) {
                removeWeblogEntryTag(tag);
            }
        }
        
        // remove attributes
        if (entry.getEntryAttributes() != null) {
            for (Iterator<WeblogEntryAttribute> it = entry.getEntryAttributes().iterator(); it.hasNext(); ) {
                WeblogEntryAttribute att = it.next();
                it.remove();
                this.strategy.remove(att);
            }
        }

        // remove entry
        this.strategy.remove(entry);
        
        // update weblog last modified date.  date updated by saveWebsite()
        if (entry.isPublished()) {
            roller.getWeblogManager().saveWeblog(weblog);
        }
        
        // remove entry from cache mapping
        this.entryAnchorToIdMap.remove(entry.getWebsite().getHandle()+":"+entry.getAnchor());
    }

    /**
     * @inheritDoc
     *
     * <p>Goes through {@code saveWeblogEntry} like every other status change
     * in this class, but its two most consequential effects -- the tag
     * aggregate adjustment and the weblog {@code lastModified} bump -- are
     * both gated inside {@code saveWeblogEntry} on the entry's NEW status
     * being PUBLISHED, which TRASHED never is. Neither happens for free, so
     * this method drives both explicitly itself, while it can still see
     * that the entry WAS published (before the status flip below):
     * <ul>
     *   <li>{@code entry.setRefreshAggregates(true)} so
     *       {@code saveWeblogEntry}'s tag-count adjustment fires and
     *       decrements this entry's tags out of the tag cloud -- otherwise
     *       they stay counted until the entry is purged, which with the
     *       default "keep forever" retention is effectively never.</li>
     *   <li>An explicit {@code saveWeblog} call bumps
     *       {@code weblog.lastModified} directly -- {@code WeblogPageCache}
     *       has no CacheHandler and expires a rendered page only by
     *       comparing it against that timestamp (see the Templates section
     *       of CLAUDE.md), so without this a reader keeps seeing a trashed
     *       post on the cached home page until something unrelated happens
     *       to bump it.</li>
     * </ul>
     *
     * <p>{@code saveWeblogEntry}'s revision recording compares only
     * title/text/summary, so a pure status change deposits no revision, and
     * its {@code ENTRY_PUBLISHED} event gate requires the NEW status to be
     * PUBLISHED, which TRASHED never is -- so trashing a published entry
     * fires no event either.
     */
    @Override
    public void trashWeblogEntry(WeblogEntry entry) throws WebloggerException {
        boolean wasPublished = entry.isPublished();
        if (wasPublished) {
            entry.setRefreshAggregates(true);
        }
        entry.setStatus(PubStatus.TRASHED);
        entry.setTrashedAt(new Timestamp(System.currentTimeMillis()));
        saveWeblogEntry(entry);

        if (wasPublished) {
            roller.getWeblogManager().saveWeblog(entry.getWebsite());
        }
    }

    /**
     * @inheritDoc
     *
     * <p>Always DRAFT -- never the status the entry held before it was
     * trashed, because nothing records what that was. Restoring to whatever
     * came before would mean an undelete of a formerly-published entry could
     * silently republish it to feeds, the sitemap and every subscriber; one
     * extra click to hit Publish again is the safer failure mode, so this
     * intentionally does not try to remember or infer the prior status.
     *
     * <p>Unlike {@link #trashWeblogEntry}, this needs neither a tag
     * aggregate refresh nor an explicit {@code weblog.lastModified} bump.
     * TRASHED and DRAFT are both absent from every reader-facing query path
     * (see {@code WeblogEntrySearchCriteria}'s default exclusion of
     * TRASHED, and DRAFT was never included to begin with), so nothing a
     * reader can see changes by moving an entry between them, and
     * {@code trashWeblogEntry} already decremented the tag counts on the
     * way in whenever the entry being restored had been published -- there
     * is nothing left here to undo.
     */
    @Override
    public void restoreWeblogEntry(WeblogEntry entry) throws WebloggerException {
        entry.setStatus(PubStatus.DRAFT);
        entry.setTrashedAt(null);
        saveWeblogEntry(entry);
    }

    /**
     * @inheritDoc
     *
     * <p>Routed through {@link #getWeblogEntries(WeblogEntrySearchCriteria)}
     * like every other entry listing, rather than a hand-written query --
     * the TRASHED dimension is meant to live in exactly one query path (see
     * the W5 trash design). An explicit {@code status == TRASHED} names the
     * entries directly, and {@link WeblogEntrySearchCriteria.SortBy#TRASH_TIME}
     * sorts by {@code trashedAt}, the only timestamp guaranteed non-null for
     * an entry that was trashed before ever being published.
     */
    @Override
    public List<WeblogEntry> getTrashedEntries(Weblog weblog) throws WebloggerException {
        if (weblog == null) {
            throw new WebloggerException("weblog is null");
        }
        WeblogEntrySearchCriteria wesc = new WeblogEntrySearchCriteria();
        wesc.setWeblog(weblog);
        wesc.setStatus(PubStatus.TRASHED);
        wesc.setSortBy(WeblogEntrySearchCriteria.SortBy.TRASH_TIME);
        wesc.setSortOrder(WeblogEntrySearchCriteria.SortOrder.DESCENDING);
        return getWeblogEntries(wesc);
    }

    /**
     * @inheritDoc
     *
     * <p>Deletes one entry at a time through {@link #removeWeblogEntry},
     * deliberately: that is the only permanent-deletion path in this class
     * (also used by "delete forever" and the weblog-deletion cascade), and a
     * bulk JPQL delete here would bypass its revision/tag/attribute cleanup
     * the same way a bulk entry delete would.
     */
    @Override
    public int purgeTrash(Weblog weblog, int retentionDays) throws WebloggerException {
        if (retentionDays < 0) {
            // -1 (and, defensively, any other negative value) means "keep
            // trash forever" -- purge nothing.
            return 0;
        }

        Timestamp cutoff = Timestamp.from(
                java.time.Instant.now().minus(java.time.Duration.ofDays(retentionDays)));

        int purged = 0;
        for (WeblogEntry entry : getTrashedEntries(weblog)) {
            if (entry.getTrashedAt() != null && entry.getTrashedAt().before(cutoff)) {
                removeWeblogEntry(entry);
                purged++;
            }
        }
        return purged;
    }

    private List<WeblogEntry> getNextPrevEntries(WeblogEntry current, String catName,
            String locale, int maxEntries, boolean next)
            throws WebloggerException {

		if (current == null) {
			log.debug("current WeblogEntry cannot be null");
			return Collections.emptyList();
		}

        TypedQuery<WeblogEntry> query;
        WeblogCategory category;
        
        List<Object> params = new ArrayList<>();
        int size = 0;
        String queryString = "SELECT e FROM WeblogEntry e WHERE ";
        StringBuilder whereClause = new StringBuilder();

        params.add(size++, current.getWebsite());
        whereClause.append("e.website = ?").append(size);
        
        params.add(size++, PubStatus.PUBLISHED);
        whereClause.append(" AND e.status = ?").append(size);
                
        if (next) {
            params.add(size++, current.getPubTime());
            whereClause.append(" AND e.pubTime > ?").append(size);
        } else {
            // pub time null if current article not yet published, in Draft view
            if (current.getPubTime() != null) {
                params.add(size++, current.getPubTime());
                whereClause.append(" AND e.pubTime < ?").append(size);
            }
        }
        
        if (catName != null) {
            category = getWeblogCategoryByName(current.getWebsite(), catName);
            if (category != null) {
                params.add(size++, category);
                whereClause.append(" AND e.category = ?").append(size);
            } else {
                throw new WebloggerException("Cannot find category: " + catName);
            } 
        }
        
        if(locale != null) {
            params.add(size++, locale + '%');
            whereClause.append(" AND e.locale like ?").append(size);
        }
        
        if (next) {
            whereClause.append(" ORDER BY e.pubTime ASC");
        } else {
            whereClause.append(" ORDER BY e.pubTime DESC");
        }
        query = strategy.getDynamicQuery(queryString + whereClause.toString(), WeblogEntry.class);
        bindParams(query, params);
        query.setMaxResults(maxEntries);
        
        return query.getResultList();
    }
    
    /**
     * @inheritDoc
     */
    @Override
    public List<WeblogCategory> getWeblogCategories(Weblog website)
    throws WebloggerException {
        if (website == null) {
            throw new WebloggerException("website is null");
        }
        
        TypedQuery<WeblogCategory> q = strategy.getNamedQuery(
                "WeblogCategory.getByWeblog", WeblogCategory.class);
        q.setParameter(1, website);
        return q.getResultList();
    }

    /**
     * @inheritDoc
     */
    @Override
    public List<WeblogEntry> getWeblogEntries(WeblogEntrySearchCriteria wesc) throws WebloggerException {

        WeblogCategory cat = null;
        if (StringUtils.isNotEmpty(wesc.getCatName()) && wesc.getWeblog() != null) {
            cat = getWeblogCategoryByName(wesc.getWeblog(), wesc.getCatName());
            if (cat == null) {
                // A category was asked for and no such category exists, so
                // nothing can match. Previously the filter was simply left off
                // and every entry came back -- a url naming a category that had
                // been renamed answered with the weblog's whole archive, which
                // reads as "here is everything" rather than "there is nothing
                // here". Callers that do not care about a category pass no name
                // at all and are unaffected.
                return Collections.emptyList();
            }
        }

        List<Object> params = new ArrayList<>();
        int size = 0;
        StringBuilder queryString = new StringBuilder();
        
        if (wesc.getTags() == null || wesc.getTags().isEmpty()) {
            queryString.append("SELECT e FROM WeblogEntry e WHERE ");
        } else {
            queryString.append("SELECT e FROM WeblogEntry e JOIN e.tags t WHERE ");
            queryString.append("(");
            for (int i = 0; i < wesc.getTags().size(); i++) {
                if (i != 0) {
                    queryString.append(" OR ");
                }
                params.add(size++, wesc.getTags().get(i));
                queryString.append(" t.name = ?").append(size);                
            }
            queryString.append(") AND ");
        }
        
        if (wesc.getWeblog() != null) {
            params.add(size++, wesc.getWeblog().getId());
            queryString.append("e.website.id = ?").append(size);
        } else {
            params.add(size++, Boolean.TRUE);
            queryString.append("e.website.visible = ?").append(size);
        }
        
        if (wesc.getUser() != null) {
            params.add(size++, wesc.getUser().getUserName());
            queryString.append(" AND e.creatorUserName = ?").append(size);
        }
        
        if (wesc.getStartDate() != null) {
            Timestamp start = new Timestamp(wesc.getStartDate().getTime());
            params.add(size++, start);
            queryString.append(" AND e.pubTime >= ?").append(size);
        }
        
        if (wesc.getEndDate() != null) {
            Timestamp end = new Timestamp(wesc.getEndDate().getTime());
            params.add(size++, end);
            queryString.append(" AND e.pubTime <= ?").append(size);
        }
        
        if (cat != null) {
            params.add(size++, cat.getId());
            queryString.append(" AND e.category.id = ?").append(size);
        }
                
        if (wesc.getStatus() != null) {
            // An explicit status wins outright: a caller asking for TRASHED
            // gets trash, and a caller asking for anything else cannot get
            // it -- that asymmetry is the property the whole trash design
            // rests on, so it must not be second-guessed here.
            params.add(size++, wesc.getStatus());
            queryString.append(" AND e.status = ?").append(size);
        } else if (!wesc.isIncludeTrashed()) {
            // No explicit status and trash was not asked for: exclude it.
            // This is the ONE place a new, thoughtless caller is protected --
            // getWeblogEntries() backs 23 call sites, 11 of which set no
            // status, and every one of them must default to never seeing a
            // deleted entry. The alternative (require every caller to
            // remember to exclude TRASHED) fails open: forget it once and a
            // deleted entry is back on a public page.
            params.add(size++, PubStatus.TRASHED);
            queryString.append(" AND e.status <> ?").append(size);
        }
        // else: no explicit status, includeTrashed true -- no status
        // condition at all (only the trash screen does this).

        if (wesc.getLocale() != null) {
            params.add(size++, wesc.getLocale() + '%');
            queryString.append(" AND e.locale like ?").append(size);
        }
        
        if (StringUtils.isNotEmpty(wesc.getText())) {
            params.add(size++, '%' + wesc.getText() + '%');
            queryString.append(" AND ( e.text LIKE ?").append(size);
            queryString.append("    OR e.summary LIKE ?").append(size);
            queryString.append("    OR e.title LIKE ?").append(size);
            queryString.append(") ");
        }

        if (wesc.getSortBy() != null && wesc.getSortBy().equals(WeblogEntrySearchCriteria.SortBy.UPDATE_TIME)) {
            queryString.append(" ORDER BY e.updateTime ");
        } else if (wesc.getSortBy() != null && wesc.getSortBy().equals(WeblogEntrySearchCriteria.SortBy.TRASH_TIME)) {
            queryString.append(" ORDER BY e.trashedAt ");
        } else {
            queryString.append(" ORDER BY e.pubTime ");
        }
        
        if (wesc.getSortOrder() != null && wesc.getSortOrder().equals(WeblogEntrySearchCriteria.SortOrder.ASCENDING)) {
            queryString.append("ASC ");
        } else {
            queryString.append("DESC ");
        }
        
        
        TypedQuery<WeblogEntry> query = strategy.getDynamicQuery(queryString.toString(), WeblogEntry.class);
        bindParams(query, params);

        setFirstMax( query, wesc.getOffset(), wesc.getMaxResults() );
        return query.getResultList();
    }
    
    /**
     * @inheritDoc
     */
    @Override
    public List<WeblogEntry> getWeblogEntriesPinnedToMain(Integer max)
    throws WebloggerException {
        TypedQuery<WeblogEntry> query = strategy.getNamedQuery(
                "WeblogEntry.getByPinnedToMain&statusOrderByPubTimeDesc", WeblogEntry.class);
        query.setParameter(1, Boolean.TRUE);
        query.setParameter(2, PubStatus.PUBLISHED);
        if (max != null) {
            query.setMaxResults(max);
        }
        return query.getResultList();
    }
    
    @Override
    public void removeWeblogEntryAttribute(String name, WeblogEntry entry)
    throws WebloggerException {

        // seems silly, why is this not done in WeblogEntry?

        for (Iterator<WeblogEntryAttribute> it = entry.getEntryAttributes().iterator(); it.hasNext();) {
            WeblogEntryAttribute entryAttribute = it.next();
            if (entryAttribute.getName().equals(name)) {

                //Remove it from database
                this.strategy.remove(entryAttribute);

                //Remove it from the collection
                it.remove();
            }
        }
    }
    
    private void removeWeblogEntryTag(WeblogEntryTag tag) throws WebloggerException {
        if (tag.getWeblogEntry().isPublished()) {
            updateTagCount(tag.getName(), tag.getWeblogEntry().getWebsite(), -1);
        }
        this.strategy.remove(tag);
    }

    /**
     * @inheritDoc
     */
    @Override
    public WeblogEntry getWeblogEntryByAnchor(Weblog website,
            String anchor) throws WebloggerException {
        
        if (website == null) {
            throw new WebloggerException("Website is null");
        }
        
        if (anchor == null) {
            throw new WebloggerException("Anchor is null");
        }
        
        // mapping key is combo of weblog + anchor
        String mappingKey = website.getHandle() + ":" + anchor;
        
        // check cache first
        // NOTE: if we ever allow changing anchors then this needs updating
        if(this.entryAnchorToIdMap.containsKey(mappingKey)) {
            
            WeblogEntry entry = this.getWeblogEntry(this.entryAnchorToIdMap.get(mappingKey));
            if(entry != null) {
                log.debug("entryAnchorToIdMap CACHE HIT - {}", mappingKey);
                return entry;
            } else {
                // mapping hit with lookup miss?  mapping must be old, remove it
                this.entryAnchorToIdMap.remove(mappingKey);
            }
        }
        
        // cache failed, do lookup
        TypedQuery<WeblogEntry> q = strategy.getNamedQuery(
                "WeblogEntry.getByWebsite&AnchorOrderByPubTimeDesc", WeblogEntry.class);
        q.setParameter(1, website);
        q.setParameter(2, anchor);
        WeblogEntry entry;
        try {
            entry = q.getSingleResult();
        } catch (NoResultException e) {
            entry = null;
        }
        
        // add mapping to cache
        if(entry != null) {
            log.debug("entryAnchorToIdMap CACHE MISS - {}", mappingKey);
            this.entryAnchorToIdMap.put(mappingKey, entry.getId());
        }
        return entry;
    }
    
    /**
     * @inheritDoc
     */
    @Override
    public String createAnchor(WeblogEntry entry) throws WebloggerException {
        // Word separator for generated anchors. Read per call rather than
        // cached in a static: the setting is runtime-settable, and a value
        // latched at class load could not see a change made from the site
        // settings. It only affects anchors generated from here on -- anchors
        // already stored on entries keep whichever separator was in force when
        // they were created, which is what keeps existing permalinks working
        // across a change.
        char separator = WebloggerRuntimeConfig
                .getBooleanProperty("weblogentry.title.useUnderscoreSeparator") ? '_' : '-';
        // Check for uniqueness of anchor
        String base = entry.createAnchorBase(separator);
        String name = base;
        int count = 0;
        
        while (true) {
            if (count > 0) {
                name = base + count;
            }
            
            TypedQuery<WeblogEntry> q = strategy.getNamedQuery(
                    "WeblogEntry.getByWebsite&Anchor", WeblogEntry.class);
            q.setParameter(1, entry.getWebsite());
            q.setParameter(2, name);
            List<WeblogEntry> results = q.getResultList();
            
            if (results.isEmpty()) {
                break;
            } else {
                count++;
            }
        }
        return name;
    }
    
    /**
     * @inheritDoc
     */
    @Override
    public boolean isDuplicateWeblogCategoryName(WeblogCategory cat)
    throws WebloggerException {
        return getWeblogCategoryByName(
                cat.getWeblog(), cat.getName()) != null;
    }
    
    /**
     * @inheritDoc
     */
    @Override
    public boolean isWeblogCategoryInUse(WeblogCategory cat)
    throws WebloggerException {
        TypedQuery<WeblogEntry> q = strategy.getNamedQuery("WeblogEntry.getByCategory", WeblogEntry.class);
        q.setParameter(1, cat);
        int entryCount = q.getResultList().size();
        return entryCount > 0;
    }
    
    /**
     * @inheritDoc
     */
    @Override
    public WeblogCategory getWeblogCategory(String id)
    throws WebloggerException {
        return (WeblogCategory) this.strategy.load(
                WeblogCategory.class, id);
    }
    
    //--------------------------------------------- WeblogCategory Queries
    
    /**
     * @inheritDoc
     */
    @Override
    public WeblogCategory getWeblogCategoryByName(Weblog weblog,
            String categoryName) throws WebloggerException {
        TypedQuery<WeblogCategory> q = strategy.getNamedQuery(
                "WeblogCategory.getByWeblog&Name", WeblogCategory.class);
        q.setParameter(1, weblog);
        q.setParameter(2, categoryName);
        try {
            return q.getSingleResult();
        } catch (NoResultException e) {
            return null;
        }
    }

    /**
     * @inheritDoc
     */
    @Override
    public WeblogEntry getWeblogEntry(String id) throws WebloggerException {
        return (WeblogEntry)strategy.load(WeblogEntry.class, id);
    }
    
    /**
     * @inheritDoc
     */
    @Override
    public Map<Date, List<WeblogEntry>> getWeblogEntryObjectMap(WeblogEntrySearchCriteria wesc) throws WebloggerException {
        Map<Date, List<WeblogEntry>> map = new TreeMap<>(Collections.reverseOrder());

        List<WeblogEntry> entries = getWeblogEntries(wesc);

        Calendar cal = Calendar.getInstance();
        if (wesc.getWeblog() != null) {
            cal.setTimeZone(wesc.getWeblog().getTimeZoneInstance());
        }

        for (WeblogEntry entry : entries) {
            Date sDate = DateUtil.getNoonOfDay(entry.getPubTime(), cal);
            List<WeblogEntry> dayEntries = map.computeIfAbsent(sDate, k -> new ArrayList<>());
            dayEntries.add(entry);
        }
        return map;
    }

    /**
     * @inheritDoc
     */
    @Override
    public Map<Date, String> getWeblogEntryStringMap(WeblogEntrySearchCriteria wesc) throws WebloggerException {
        Map<Date, String> map = new TreeMap<>(Collections.reverseOrder());

        List<WeblogEntry> entries = getWeblogEntries(wesc);

        Calendar cal = Calendar.getInstance();
        SimpleDateFormat formatter = DateUtil.get8charDateFormat();
        if (wesc.getWeblog() != null) {
            TimeZone tz = wesc.getWeblog().getTimeZoneInstance();
            cal.setTimeZone(tz);
            formatter.setTimeZone(tz);
        }

        for (WeblogEntry entry : entries) {
            Date sDate = DateUtil.getNoonOfDay(entry.getPubTime(), cal);
            if (map.get(sDate) == null) {
                map.put(sDate, formatter.format(sDate));
            }
        }
        return map;
    }

    /**
     * @inheritDoc
     */
    @Override
    public WeblogEntry getNextEntry(WeblogEntry current,
            String catName, String locale) throws WebloggerException {
        WeblogEntry entry = null;
        List<WeblogEntry> entryList = getNextPrevEntries(current, catName, locale, 1, true);
        if (entryList != null && !entryList.isEmpty()) {
            entry = entryList.get(0);
        }
        return entry;
    }
    
    /**
     * @inheritDoc
     */
    @Override
    public WeblogEntry getPreviousEntry(WeblogEntry current,
            String catName, String locale) throws WebloggerException {
        WeblogEntry entry = null;
        List<WeblogEntry> entryList = getNextPrevEntries(current, catName, locale, 1, false);
        if (entryList != null && !entryList.isEmpty()) {
            entry = entryList.get(0);
        }
        return entry;
    }
    
    /**
     * @inheritDoc
     */
    @Override
    public void release() {}

    /**
     * @inheritDoc
     */
    @Override
    public List<TagStat> getPopularTags(Weblog website, Date startDate, int offset, int limit)
    throws WebloggerException {
        TypedQuery<TagStat> query;
        List<TagStat> queryResults;
        
        if (website != null) {
            if (startDate != null) {
                Timestamp start = new Timestamp(startDate.getTime());
                query = strategy.getNamedQuery(
                        "WeblogEntryTagAggregate.getPopularTagsByWebsite&StartDate", TagStat.class);
                query.setParameter(1, website);
                query.setParameter(2, start);
            } else {
                query = strategy.getNamedQuery(
                        "WeblogEntryTagAggregate.getPopularTagsByWebsite", TagStat.class);
                query.setParameter(1, website);
            }
        } else {
            if (startDate != null) {
                Timestamp start = new Timestamp(startDate.getTime());
                query = strategy.getNamedQuery(
                        "WeblogEntryTagAggregate.getPopularTagsByWebsiteNull&StartDate", TagStat.class);
                query.setParameter(1, start);
            } else {
                query = strategy.getNamedQuery(
                        "WeblogEntryTagAggregate.getPopularTagsByWebsiteNull", TagStat.class);
            }
        }
        setFirstMax( query, offset, limit);
        queryResults = query.getResultList();
        
        double min = Integer.MAX_VALUE;
        double max = Integer.MIN_VALUE;
        
        List<TagStat> results = new ArrayList<>(limit >= 0 ? limit : 25);
        
        if (queryResults != null) {
            for (Object obj : queryResults) {
                Object[] row = (Object[]) obj;
                TagStat t = new TagStat();
                t.setName((String) row[0]);
                t.setCount(((Number) row[1]).intValue());

                min = Math.min(min, t.getCount());
                max = Math.max(max, t.getCount());
                results.add(t);
            }
        }

        min = Math.log(1+min);
        max = Math.log(1+max);
        
        double range = Math.max(.01, max - min) * 1.0001;
        
        for (TagStat t : results) {
            t.setIntensity((int) (1 + Math.floor(5 * (Math.log(1+t.getCount()) - min) / range)));
        }

        // sort results by name, because query had to sort by total
        results.sort(TAG_STAT_NAME_COMPARATOR);
        
        return results;
    }
    
    /**
     * @inheritDoc
     */
    @Override
    public List<TagStat> getTags(Weblog website, String sortBy,
            String startsWith, int offset, int limit) throws WebloggerException {
        Query query;
        List<?> queryResults;
        boolean sortByName = sortBy == null || !"count".equals(sortBy);
                
        List<Object> params = new ArrayList<>();
        int size = 0;
        StringBuilder queryString = new StringBuilder();
        queryString.append("SELECT w.name, SUM(w.total) FROM WeblogEntryTagAggregate w WHERE ");
                
        if (website != null) {
            params.add(size++, website.getId());
            queryString.append(" w.weblog.id = ?").append(size);
        } else {
            queryString.append(" w.weblog IS NULL"); 
        }
                       
        if (startsWith != null && startsWith.length() > 0) {
            params.add(size++, startsWith + '%');
            queryString.append(" AND w.name LIKE ?").append(size);
        }
                    
        if (sortBy != null && "count".equals(sortBy)) {
            sortBy = "w.total DESC";
        } else {
            sortBy = "w.name";
        }
        queryString.append(" GROUP BY w.name, w.total ORDER BY ").append(sortBy);

        query = strategy.getDynamicQuery(queryString.toString());
        bindParams(query, params);
        setFirstMax( query, offset, limit);
        queryResults = query.getResultList();

        List<TagStat> results = new ArrayList<>();
        if (queryResults != null) {
            for (Object obj : queryResults) {
                Object[] row = (Object[]) obj;
                TagStat ce = new TagStat();
                ce.setName((String) row[0]);
                // The JPA query retrieves SUM(w.total) always as long
                ce.setCount(((Long) row[1]).intValue());
                results.add(ce);
            }
        }

        if (sortByName) {
            results.sort(TAG_STAT_NAME_COMPARATOR);
        } else {
            results.sort(TAG_STAT_COUNT_REVERSE_COMPARATOR);
        }
        
        return results;
    }
    
    
    /**
     * @inheritDoc
     */
    @Override
    public boolean getTagComboExists(List<String> tags, Weblog weblog) throws WebloggerException{
        
        if (tags == null || tags.isEmpty()) {
            return false;
        }
        
        StringBuilder queryString = new StringBuilder();
        queryString.append("SELECT DISTINCT w.name ");
        queryString.append("FROM WeblogEntryTagAggregate w WHERE w.name IN (");
        // Append tags as parameter markers to avoid potential escaping issues
        // The IN clause would be of form (?1, ?2, ?3, ..)
        List<Object> params = new ArrayList<>(tags.size() + 1);
        final String paramSeparator = ", ";
        int i;
        for (i=0; i < tags.size(); i++) {
            queryString.append('?').append(i+1).append(paramSeparator);
            params.add(tags.get(i));
        }
        
        // Remove the trailing paramSeparator
        queryString.delete(queryString.length() - paramSeparator.length(),
                queryString.length());
        // Close the brace of IN clause
        queryString.append(')');
        
        if(weblog != null) {
            queryString.append(" AND w.weblog = ?").append(i+1);
            params.add(weblog);
        } else {
            queryString.append(" AND w.weblog IS NULL");
        }
        
        TypedQuery<String> q = strategy.getDynamicQuery(queryString.toString(), String.class);
        bindParams(q, params);
        List<String> results = q.getResultList();
        
        //TODO: DatamapperPort: Since we are only interested in knowing whether
        //results.size() == tags.size(). This query can be optimized to just fetch COUNT
        //instead of objects as done currently
        return results != null && results.size() == tags.size();
    }

    /**
     * This method maintains the tag aggregate table up-to-date with total counts. More
     * specifically every time this method is called it will act upon exactly two rows
     * in the database (tag,website,count), one with website matching the argument passed
     * and one where website is null. If the count ever reaches zero, the row must be deleted.
     *
     * @param name      The tag name
     * @param website   The website to used when updating the stats.
     * @param amount    The amount to increment the tag count (it can be positive or negative).
     * @throws WebloggerException
     */
    private void updateTagCount(String name, Weblog website, int amount)
    throws WebloggerException {
        if (amount == 0) {
            throw new WebloggerException("Tag increment amount cannot be zero.");
        }
        
        if (website == null) {
            throw new WebloggerException("Website cannot be NULL.");
        }
        
        // The reason why add order lastUsed desc is to make sure we keep picking the most recent
        // one in the case where we have multiple rows (clustered environment)
        // eventually that second entry will have a very low total (most likely 1) and
        // won't matter
        TypedQuery<WeblogEntryTagAggregate> weblogQuery = strategy.getNamedQuery(
                "WeblogEntryTagAggregate.getByName&WebsiteOrderByLastUsedDesc", WeblogEntryTagAggregate.class);
        weblogQuery.setParameter(1, name);
        weblogQuery.setParameter(2, website);
        WeblogEntryTagAggregate weblogTagData;
        try {
            weblogTagData = weblogQuery.getSingleResult();
        } catch (NoResultException e) {
            weblogTagData = null;
        }

        TypedQuery<WeblogEntryTagAggregate> siteQuery = strategy.getNamedQuery(
                "WeblogEntryTagAggregate.getByName&WebsiteNullOrderByLastUsedDesc", WeblogEntryTagAggregate.class);
        siteQuery.setParameter(1, name);
        WeblogEntryTagAggregate siteTagData;
        try {
            siteTagData = siteQuery.getSingleResult();
        } catch (NoResultException e) {
            siteTagData = null;
        }
        Timestamp lastUsed = new Timestamp(new Date().getTime());

        upsertTagAggregate(weblogTagData, name, website, amount, lastUsed);
        upsertTagAggregate(siteTagData, name, null, amount, lastUsed);

        // delete all bad counts
        Query removeq = strategy.getNamedUpdate(
                "WeblogEntryTagAggregate.removeByTotalLessEqual");
        removeq.setParameter(1, 0);
        removeq.executeUpdate();
    }

    private void upsertTagAggregate(WeblogEntryTagAggregate tagData, String name,
            Weblog website, int amount, Timestamp lastUsed) throws WebloggerException {
        // create it only if we are going to need it.
        if (tagData == null && amount > 0) {
            tagData = new WeblogEntryTagAggregate(null, website, name, amount);
            tagData.setLastUsed(lastUsed);
            strategy.store(tagData);
        } else if (tagData != null) {
            tagData.setTotal(tagData.getTotal() + amount);
            tagData.setLastUsed(lastUsed);
            strategy.store(tagData);
        }
    }
    
    private static void setFirstMax( Query query, int offset, int length )  {
        if (offset != 0) {
            query.setFirstResult(offset);
        }
        if (length != -1) {
            query.setMaxResults(length);
        }
    }

    private static void bindParams(Query query, List<Object> params) {
        for (int i = 0; i < params.size(); i++) {
            query.setParameter(i + 1, params.get(i));
        }
    }


    public static Date getStartDateNow(int sinceDays) {
        Calendar cal = Calendar.getInstance();
        cal.setTime(new Date());
        cal.add(Calendar.DATE, -1 * sinceDays);
        return cal.getTime();
    }


    @Override
    public boolean hasEntriesCreatedBy(User user) throws WebloggerException {
        TypedQuery<String> q = strategy.getNamedQuery("WeblogEntry.getIdsByCreator", String.class);
        q.setParameter(1, user.getUserName());
        q.setMaxResults(1);
        return !q.getResultList().isEmpty();
    }

    /**
     * @inheritDoc
     */
    @Override
    public long getEntryCount() throws WebloggerException {
        TypedQuery<Long> q = strategy.getNamedQuery(
                "WeblogEntry.getCountDistinctByStatus", Long.class);
        q.setParameter(1, PubStatus.PUBLISHED);
        return q.getResultList().get(0);
    }
    
    /**
     * @inheritDoc
     */
    @Override
    public long getEntryCount(Weblog website) throws WebloggerException {
        TypedQuery<Long> q = strategy.getNamedQuery(
                "WeblogEntry.getCountDistinctByStatus&Website", Long.class);
        q.setParameter(1, PubStatus.PUBLISHED);
        q.setParameter(2, website);
        return q.getResultList().get(0);
    }

}
