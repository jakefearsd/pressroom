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
package com.wikantik.pressroom.business;

import java.util.List;

import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.pojos.User;
import com.wikantik.pressroom.pojos.WeblogPage;
import com.wikantik.pressroom.pojos.WeblogPageRevision;

/**
 * Interface to static page management.
 *
 * <p>A {@link WeblogPage} is content outside the entry chronology, addressed
 * by a slug that is unique within its weblog.
 */
public interface WeblogPageManager {

    /**
     * Saves a page as {@code editor}'s work. When the save changes the
     * page's title or content, what it replaced is kept as a revision
     * credited to {@code editor} and labelled with {@code revisionNote};
     * a save that changes only settings leaves none. The revision is written
     * after the save commits and its failure never costs the save.
     *
     * <p>Throws {@link WebloggerException} for a slug that is blank, contains
     * a slash or is reserved, as before.
     *
     * @param editor the acting user, or null for a save no person made
     * @param revisionNote why the save was made, or null
     */
    void savePage(WeblogPage page, User editor, String revisionNote) throws WebloggerException;

    /**
     * Puts a revision's title and content back and saves as {@code editor}.
     * What it replaces becomes a revision of its own, always: a restore is
     * never coalesced with a save just before it. A revision holds no
     * settings, so nothing else about the page changes.
     */
    void restoreRevision(WeblogPage page, WeblogPageRevision revision, User editor) throws WebloggerException;

    /**
     * Marks a revision as kept, or releases it. A kept revision is exempt
     * from the per-item count, the age limit and the site-wide ceiling.
     * Releasing one removes nothing then: the item's next save, or the
     * sweep, applies the rules to it. Marking a kept revision kept again, or
     * releasing one that is not, changes nothing.
     *
     * @throws RevisionKeepLimitException when the item already has ten kept
     */
    void setRevisionKept(WeblogPageRevision revision, boolean kept) throws WebloggerException;

    /**
     * {@link #savePage(WeblogPage, User, String)} for a system caller: a
     * revision it leaves credits nobody and carries no note.
     */
    void savePage(WeblogPage page) throws WebloggerException;

    /**
     * The page's revisions, newest first. Each holds the title and content
     * as they stood BEFORE one save. Empty for a null or unsaved page.
     */
    List<WeblogPageRevision> getRevisions(WeblogPage page) throws WebloggerException;

    /**
     * One revision by id, or null. A global lookup: callers reach it through
     * {@code WeblogOwnership.pageRevision}, which ties it to a page the
     * caller may see.
     */
    WeblogPageRevision getRevision(String id) throws WebloggerException;

    /**
     * The content now committed for this page, read under its row lock so it
     * cannot change before the caller's transaction ends; null when the page
     * no longer exists. For the stale-write check of an update that sent
     * {@code expectedContentHash}.
     */
    String lockPageContent(WeblogPage page) throws WebloggerException;

    /**
     * Remove a page.
     */
    void removePage(WeblogPage page) throws WebloggerException;

    /**
     * Look a page up by its id. Returns null when no page carries the id.
     */
    WeblogPage getPage(String id) throws WebloggerException;

    /**
     * Look a page up by its weblog and slug. Returns null when the weblog has
     * no page with that slug.
     */
    WeblogPage getPageBySlug(Weblog weblog, String slug) throws WebloggerException;

    /**
     * All pages belonging to a weblog, in nav order.
     */
    List<WeblogPage> getPages(Weblog weblog) throws WebloggerException;

    /**
     * The published pages belonging to a weblog, in nav order.
     */
    List<WeblogPage> getPublishedPages(Weblog weblog) throws WebloggerException;

    /**
     * Sets the navigation order of a weblog's pages: the named pages of this
     * weblog get {@code navOrder} 0..n-1 in the given order (ids of other
     * weblogs, unknown ids and repeats are skipped); pages not named follow,
     * keeping their current relative order. Bumps the weblog's last-modified
     * stamp, so rendered pages expire.
     */
    void setNavOrder(Weblog weblog, List<String> pageIds) throws WebloggerException;

    /**
     * Moves the page to the trash: status {@code TRASHED}, stamped now. It
     * stops being served, listed and searched, and stops being the front
     * door; it keeps its slug, its nav order and, for a strand, its parts.
     *
     * @throws WebloggerException if the page is already in the trash
     */
    void trashPage(WeblogPage page) throws WebloggerException;

    /**
     * Brings a trashed page back as a {@code DRAFT}. Never published, and
     * never the front door again without the author choosing it.
     *
     * @throws WebloggerException if the page is not in the trash
     */
    void restorePage(WeblogPage page) throws WebloggerException;

    /**
     * The weblog's trashed pages, most recently trashed first.
     */
    List<WeblogPage> getTrashedPages(Weblog weblog) throws WebloggerException;

    /**
     * Permanently removes ({@link #removePage}) the weblog's pages trashed
     * more than {@code retentionDays} days ago. Any negative value keeps
     * trash forever and removes nothing; 0 removes everything trashed. A
     * trashed page with no stamp is never removed.
     *
     * @return how many pages were removed
     */
    int purgeTrash(Weblog weblog, int retentionDays) throws WebloggerException;

    /**
     * Remove all pages belonging to a weblog.
     */
    void removePages(Weblog weblog) throws WebloggerException;
}
