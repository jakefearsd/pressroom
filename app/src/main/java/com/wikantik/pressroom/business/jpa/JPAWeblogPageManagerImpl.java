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

import java.sql.Timestamp;
import java.util.Collections;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import jakarta.persistence.FlushModeType;
import jakarta.persistence.EntityTransaction;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.EntityNotFoundException;
import jakarta.persistence.Query;
import jakarta.persistence.TypedQuery;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.business.SiteSlugClashException;
import com.wikantik.pressroom.business.WeblogPageManager;
import com.wikantik.pressroom.business.Weblogger;
import com.wikantik.pressroom.business.search.IndexManager;
import com.wikantik.pressroom.config.ProtectedPaths;
import com.wikantik.pressroom.pojos.WeblogPageRevision;
import com.wikantik.pressroom.pojos.User;
import com.wikantik.pressroom.pojos.ReservedSlugs;
import com.wikantik.pressroom.ui.rendering.util.LocaleSegment;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.pojos.WeblogPage;

/**
 * JPA implementation of {@link WeblogPageManager}.
 */
public class JPAWeblogPageManagerImpl implements WeblogPageManager {

    private static final Logger log = LoggerFactory.getLogger(JPAWeblogPageManagerImpl.class);

    private static final String LOCK_PAGE_CONTENT =
            "SELECT content FROM pressroom_weblogpage WHERE id = ?1 FOR UPDATE";

    private final Weblogger pressroom;
    private final JPAPersistenceStrategy strategy;
    private final RevisionLog<WeblogPage, WeblogPageRevision> revisions;

    /**
     * Each weblog's published page ids, in nav order, at the stamp they were
     * read at: every render's nav asks for them.
     */
    private final WeblogStampedCache<List<String>> publishedPageIds;

    protected JPAWeblogPageManagerImpl(Weblogger pressroom, JPAPersistenceStrategy strategy) {
        log.debug("Instantiating JPA Weblog Page Manager");
        this.pressroom = pressroom;
        this.strategy = strategy;
        this.publishedPageIds = new WeblogStampedCache<>(strategy);
        this.revisions = new RevisionLog<>(strategy, new RevisionLog.Binding<>(
                RevisionRetention.Kind.PAGE, WeblogPage.class, WeblogPageRevision.class,
                "WeblogPageRevision.getByPage", WeblogPage::getId,
                WeblogPageRevision::setPage, WeblogPageRevision::getPage,
                WeblogPageRevision::getNote, WeblogPageRevision::setNote,
                WeblogPageRevision::isKept, WeblogPageRevision::setKept));
    }

    /** Whether the published pages are remembered for this weblog; for tests. */
    boolean remembersPagesOf(String weblogId) {
        return publishedPageIds.remembers(weblogId);
    }

    @Override
    public void savePage(WeblogPage page) throws WebloggerException {
        savePage(page, null, null);
    }

    @Override
    public void savePage(WeblogPage page, User editor, String revisionNote) throws WebloggerException {
        save(page, editor, revisionNote, false);
    }

    @Override
    public void restoreRevision(WeblogPage page, WeblogPageRevision revision, User editor)
            throws WebloggerException {
        page.setTitle(revision.getTitle());
        page.setContent(revision.getContent());
        save(page, editor, null, true);
    }

    /** The save; {@code restore} marks one that puts a revision back, which is never coalesced. */
    private void save(WeblogPage page, User editor, String revisionNote, boolean restore)
            throws WebloggerException {
        String slug = page.getSlug() == null ? null : page.getSlug().trim();
        if (slug == null || slug.isBlank()) {
            throw new WebloggerException("page slug is required");
        }
        if (slug.indexOf('/') >= 0) {
            throw new WebloggerException("page slug may not contain '/': " + slug);
        }
        if (ReservedSlugs.isReserved(slug)) {
            throw new WebloggerException("page slug is reserved: " + slug);
        }
        if (LocaleSegment.isLocale(slug)) {
            // The request parser reads a leading "xx" or "xx_YY" segment as a
            // locale before it can be a slug, so such a page is never served.
            throw new WebloggerException("page slug is reserved (it reads as a locale): " + slug);
        }
        if (page.getWeblog() != null && page.getWeblog().isSiteSpace()) {
            checkSiteSlug(slug);
        }
        page.setSlug(slug);
        // The rename check inside persist reads the loaded slug, and the
        // revision the loaded content: both before the baseline moves.
        persist(page);
        recordRevision(page, editor, revisionNote, restore);
        // What was just stored is what a further save in this session
        // would be replacing.
        page.snapshotLoaded();
    }

    /**
     * Records what this save displaced, and prunes to
     * {@code page.revisions.retention} (-1 keeps all, 0 records none, n
     * keeps the n newest), once the save has committed. Only
     * {@link #savePage} records: a trash, a restore and a reorder change no
     * prose.
     *
     * <p>As for entries ({@code JPAWeblogEntryManagerImpl.recordRevision}),
     * and for the reason given there: written in the save's own transaction,
     * a revision row the database refused would fail the commit and take the
     * save with it. So it is written after that commit, in a transaction of
     * its own, where a failure is logged and costs only the revision.
     */
    private void recordRevision(WeblogPage page, User editor, String revisionNote, boolean restore) {
        String actor = editor != null ? editor.getUserName() : null;
        revisions.record(page, () -> page.contentBeingReplaced(actor), revisionNote, actor, restore);
    }

    @Override
    public List<WeblogPageRevision> getRevisions(WeblogPage page) throws WebloggerException {
        return revisions.list(page);
    }

    @Override
    public WeblogPageRevision getRevision(String id) throws WebloggerException {
        return revisions.get(id);
    }

    @Override
    public void setRevisionKept(WeblogPageRevision revision, boolean kept) throws WebloggerException {
        revisions.setKept(revision, kept);
    }

    @Override
    public String lockPageContent(WeblogPage page) throws WebloggerException {
        Query q = strategy.getNativeQuery(LOCK_PAGE_CONTENT);
        q.setFlushMode(FlushModeType.COMMIT);
        q.setParameter(1, page.getId());
        List<?> rows = q.getResultList();
        return rows.isEmpty() ? null : (String) rows.get(0);
    }

    /**
     * The write itself, and everything that must follow any write of a page.
     * Runs no slug check: {@link #trashPage} and {@link #restorePage} change
     * no address, and a slug saved before a rule existed must not strand its
     * page outside the trash or inside it.
     */
    private void persist(WeblogPage page) throws WebloggerException {
        String slug = page.getSlug();
        Timestamp now = new Timestamp(System.currentTimeMillis());
        if (page.getCreated() == null) {
            page.setCreated(now);
        }
        page.setUpdated(now);

        strategy.store(page);

        // Automatic slug history: a rename mints the redirect that keeps the
        // old URL alive. loadedSlug is the JPA post-load snapshot (null on a
        // brand-new page), so this fires exactly when a persisted slug
        // changed. Deliberately NOT best-effort -- same transaction as the
        // save, because a rename whose history row failed to write is a
        // rename that silently killed a URL, which is the exact failure the
        // redirect feature exists to prevent.
        String loadedSlug = page.getLoadedSlug();
        if (loadedSlug != null && !loadedSlug.equals(slug)) {
            pressroom.getWeblogRedirectManager().recordRename(
                    page.getWeblog(), "/" + loadedSlug, "/" + slug);
        }

        // update weblog last modified date.  date updated by saveWeblog().
        // WeblogPageCache has no CacheHandler -- a rendered page expires
        // lazily by comparing itself against weblog.lastModified (see
        // saveTemplate/saveComment for the same pattern) -- so without this
        // a published page never reaches a reader holding a cached copy.
        // The front door is kept truthful at the write: the front page no
        // longer PUBLISHED stops being the front page, and republishing does
        // not restore it. Cleared before the save below so one save carries both.
        Weblog owner = page.getWeblog();
        if (owner != null && page.getId() != null && page.getId().equals(owner.getFrontPageId())
                && page.getStatus() != WeblogPage.PubStatus.PUBLISHED) {
            owner.setFrontPageId(null);
        }
        pressroom.getWeblogManager().saveWeblog(page.getWeblog());

        // Search follows every save, whoever made it (editor, Pages screen,
        // API, a strand's parts): published and not noindex is indexed,
        // anything else taken out. Once committed, and queued: the reindex
        // runs on another thread, re-reads the row, and never holds up or
        // fails the save.
        IndexManager index = pressroom.getIndexManager();
        strategy.runAfterCommit(() -> index.reindexPage(page));
    }

    /**
     * A site page lives at {@code /<slug>}, in the namespace of blog handles
     * and the application's protected paths. Compared case-insensitively, as
     * the reserved-path check is, and against the site space's own handle.
     */
    @SuppressFBWarnings(
            value = "IMPROPER_UNICODE",
            justification = "Lower-cases a slug only to compare it with handles and protected paths, "
                    + "which are ASCII; a mismatch can only miss a clash, never let one name stand for "
                    + "another, and the database unique index on the handle remains the backstop.")
    private void checkSiteSlug(String slug) throws WebloggerException {
        String lower = slug.toLowerCase(Locale.ROOT);
        // sitemap-<handle>.xml and sitemap-site-pages.xml are root routes that
        // are not in the protected set; a dot in a slug could shadow them.
        if (ProtectedPaths.site().contains(lower) || lower.startsWith("sitemap-")) {
            throw new SiteSlugClashException("pageEdit.error.slugIsReservedPath", slug);
        }
        if (Weblog.SITE_SPACE_HANDLE.equals(lower)) {
            throw new SiteSlugClashException("pageEdit.error.slugIsWeblog", Weblog.SITE_SPACE_HANDLE);
        }
        // Lower-cased both sides: a legacy mixed-case handle (TrAvel) still
        // clashes with TRAVEL. The site space is excluded by its flag.
        List<Weblog> blogs = strategy.getDynamicQuery(
                "SELECT w FROM Weblog w WHERE LOWER(w.handle) = ?1", Weblog.class)
                .setParameter(1, lower).getResultList();
        for (Weblog blog : blogs) {
            if (!blog.isSiteSpace()) {
                throw new SiteSlugClashException("pageEdit.error.slugIsWeblog", blog.getHandle());
            }
        }
    }

    @Override
    public void trashPage(WeblogPage page) throws WebloggerException {
        if (page.getStatus() == WeblogPage.PubStatus.TRASHED) {
            throw new WebloggerException("page is already in the trash: " + page.getId());
        }
        page.setStatus(WeblogPage.PubStatus.TRASHED);
        page.setTrashedAt(new Timestamp(System.currentTimeMillis()));
        // No longer PUBLISHED: persist clears the front door, moves the
        // stamp and takes the page out of search. Strand parts stay.
        persist(page);
    }

    @Override
    public void restorePage(WeblogPage page) throws WebloggerException {
        if (page.getStatus() != WeblogPage.PubStatus.TRASHED) {
            throw new WebloggerException("page is not in the trash: " + page.getId());
        }
        page.setStatus(WeblogPage.PubStatus.DRAFT);
        page.setTrashedAt(null);
        persist(page);
    }

    @Override
    public List<WeblogPage> getTrashedPages(Weblog weblog) throws WebloggerException {
        TypedQuery<WeblogPage> query = strategy.getNamedQuery(
                "WeblogPage.getTrashedByWeblog", WeblogPage.class);
        query.setParameter(1, weblog);
        query.setParameter(2, WeblogPage.PubStatus.TRASHED);
        return query.getResultList();
    }

    @Override
    public int purgeTrash(Weblog weblog, int retentionDays) throws WebloggerException {
        if (retentionDays < 0) {
            return 0;
        }
        long cutoff = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(retentionDays);
        int purged = 0;
        // One removePage per page, never a bulk delete: each takes its
        // strand's parts out, its search document and a stale front-door id.
        for (WeblogPage page : new ArrayList<>(getTrashedPages(weblog))) {
            Timestamp trashedAt = page.getTrashedAt();
            if (trashedAt != null && trashedAt.getTime() <= cutoff && removeIfStillTrashed(page)) {
                purged++;
            }
        }
        return purged;
    }

    /**
     * Removes a purge candidate, but only if its row is still trash now. The
     * sweep holds the page as it was when listed, and an author's restore
     * can commit before the delete: the row is read again under a write
     * lock, so a restore either is seen here or waits until this is done. A
     * page that left the trash, or is already gone, is left alone.
     *
     * @return whether the page was removed
     */
    boolean removeIfStillTrashed(WeblogPage page) throws WebloggerException {
        try {
            strategy.getEntityManager(true).refresh(page, LockModeType.PESSIMISTIC_WRITE);
        } catch (EntityNotFoundException gone) {
            return false;
        }
        if (page.getStatus() != WeblogPage.PubStatus.TRASHED) {
            return false;
        }
        removePage(page);
        return true;
    }

    @Override
    public void removePage(WeblogPage page) throws WebloggerException {
        // Parts are posts; they stay, outside any strand. The FK's SET NULL
        // would clear the page id alone and leave a stale position behind.
        // Bulk update: entries already loaded in this session are stale afterwards.
        Query detach = strategy.getNamedUpdate("WeblogEntry.clearStrandByPage");
        detach.setParameter(1, page.getId());
        detach.executeUpdate();
        // Revisions FK the page, so they go first. The database would cascade
        // anyway; doing it here keeps the persistence context in step.
        Query removeRevisions = strategy.getNamedUpdate("WeblogPageRevision.removeByPage");
        removeRevisions.setParameter(1, page);
        removeRevisions.executeUpdate();
        this.strategy.remove(page);

        // The FK's SET NULL clears the row, not the managed weblog: clear it
        // here so the save below does not write the stale id back.
        Weblog owner = page.getWeblog();
        if (owner != null && page.getId() != null && page.getId().equals(owner.getFrontPageId())) {
            owner.setFrontPageId(null);
        }

        // update weblog last modified date.  date updated by saveWeblog()
        pressroom.getWeblogManager().saveWeblog(page.getWeblog());

        // Out of search once the delete is durable (see savePage).
        IndexManager index = pressroom.getIndexManager();
        strategy.runAfterCommit(() -> index.removePage(page));
    }

    @Override
    public WeblogPage getPage(String id) throws WebloggerException {
        return (WeblogPage) strategy.load(WeblogPage.class, id);
    }

    @Override
    public WeblogPage getPageBySlug(Weblog weblog, String slug) throws WebloggerException {
        TypedQuery<WeblogPage> query = strategy.getNamedQuery(
                "WeblogPage.getByWeblogAndSlug", WeblogPage.class);
        query.setParameter(1, weblog);
        query.setParameter(2, slug);
        List<WeblogPage> results = query.getResultList();
        return results.isEmpty() ? null : results.get(0);
    }

    @Override
    public List<WeblogPage> getPages(Weblog weblog) throws WebloggerException {
        TypedQuery<WeblogPage> query = strategy.getNamedQuery(
                "WeblogPage.getByWeblog", WeblogPage.class);
        query.setParameter(1, weblog);
        query.setParameter(2, WeblogPage.PubStatus.TRASHED);
        return query.getResultList();
    }

    @Override
    public List<WeblogPage> getPublishedPages(Weblog weblog) throws WebloggerException {
        List<String> known = publishedPageIds.get(weblog);
        if (known != null) {
            List<WeblogPage> remembered = new ArrayList<>(known.size());
            if (resolvePublished(known, remembered)) {
                return remembered;
            }
        }
        TypedQuery<WeblogPage> query = strategy.getNamedQuery(
                "WeblogPage.getByWeblogAndStatus", WeblogPage.class);
        query.setParameter(1, weblog);
        query.setParameter(2, WeblogPage.PubStatus.PUBLISHED);
        List<WeblogPage> pages = query.getResultList();
        publishedPageIds.put(weblog, pages.stream().map(WeblogPage::getId).toList());
        return pages;
    }

    /**
     * Loads the remembered pages through this unit of work into
     * {@code pages}; false when one of them is gone or no longer published --
     * a change that moved no stamp ({@link #removePages} deletes in bulk), so
     * the list must be read again.
     */
    private boolean resolvePublished(List<String> ids, List<WeblogPage> pages)
            throws WebloggerException {
        for (String id : ids) {
            WeblogPage page = (WeblogPage) strategy.load(WeblogPage.class, id);
            if (page == null || page.getStatus() != WeblogPage.PubStatus.PUBLISHED) {
                return false;
            }
            pages.add(page);
        }
        return true;
    }

    @Override
    public void setNavOrder(Weblog weblog, List<String> pageIds) throws WebloggerException {
        List<WeblogPage> current = getPages(weblog);
        Map<String, WeblogPage> byId = new LinkedHashMap<>();
        for (WeblogPage page : current) {
            byId.put(page.getId(), page);
        }
        // Named pages of THIS weblog first, in the order given. A stale id,
        // another weblog's id (never in byId: it is read from this weblog's
        // own list) and a repeat are skipped; pages not named follow in the
        // order they already have.
        Map<String, WeblogPage> ordered = new LinkedHashMap<>();
        if (pageIds != null) {
            for (String id : pageIds) {
                WeblogPage page = byId.get(id);
                if (page != null) {
                    ordered.putIfAbsent(id, page);
                }
            }
        }
        for (WeblogPage page : current) {
            ordered.putIfAbsent(page.getId(), page);
        }
        int position = 0;
        for (WeblogPage page : ordered.values()) {
            if (page.getNavOrder() != position) {
                page.setNavOrder(position);
                strategy.store(page);
            }
            position++;
        }
        // The rendered pages and the remembered published list expire only
        // through the weblog's stamp (see savePage). Left untouched: the
        // front door, which a reorder cannot change.
        pressroom.getWeblogManager().saveWeblog(weblog);
    }

    @Override
    public void removePages(Weblog weblog) throws WebloggerException {
        // Bulk update: entries already loaded in this session are stale afterwards.
        Query detach = strategy.getNamedUpdate("WeblogEntry.clearStrandByWeblog");
        detach.setParameter(1, weblog);
        detach.executeUpdate();
        Query removeRevisions = strategy.getNamedUpdate("WeblogPageRevision.removeByWeblog");
        removeRevisions.setParameter(1, weblog);
        removeRevisions.executeUpdate();
        Query removePages = strategy.getNamedUpdate("WeblogPage.removeByWeblog");
        removePages.setParameter(1, weblog);
        removePages.executeUpdate();
        // The FK would null the row; the managed weblog must not write it back.
        weblog.setFrontPageId(null);

        // No search work here: removeWeblog, the only caller, takes every
        // document of the weblog's handle (pages included) out of the index
        // once the delete commits.

        // Moves no stamp. A remembered page that no longer resolves is a miss
        // anyway; and removeWeblog deletes through here, after which the
        // weblog's stamp never moves again, so its slot is dropped once the
        // delete is durable.
        String weblogId = weblog.getId();
        strategy.runAfterCommit(() -> publishedPageIds.forget(weblogId));
    }
}
