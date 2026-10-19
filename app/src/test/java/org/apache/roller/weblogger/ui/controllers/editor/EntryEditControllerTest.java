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
package org.apache.roller.weblogger.ui.controllers.editor;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

import org.apache.roller.weblogger.WebloggerException;
import org.apache.roller.weblogger.config.WebloggerRuntimeConfig;
import org.apache.roller.weblogger.pojos.GlobalPermission;
import org.apache.roller.weblogger.pojos.JsonLdType;
import org.apache.roller.weblogger.pojos.WeblogCategory;
import org.apache.roller.weblogger.pojos.WeblogEntry;
import org.apache.roller.weblogger.pojos.WeblogEntryRevision;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.apache.roller.weblogger.pojos.WeblogEntry.PubStatus;
import org.apache.roller.weblogger.pojos.WeblogPermission;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ui.Model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link EntryEditController}.
 *
 * <p>This controller decides, for every save, what publication state an entry
 * lands in and what the rest of the system is told about it. Those decisions
 * carry real consequences: the wrong status makes a draft public or hides a
 * published post, a missed search-index call leaves deleted content findable,
 * and the pinned-to-main flag is a site-wide privilege that only global admins
 * may exercise. The tests below drive one decision each and assert on the side
 * effect, not merely on the view name.
 */
class EntryEditControllerTest extends EditorControllerTestSupport {

    private static final TimeZone NEW_YORK = TimeZone.getTimeZone("America/New_York");

    private EntryEditController controller;
    private EntryBean bean;
    private Model model;
    private WeblogCategory category;

    /** Whether the authenticated user holds WeblogPermission.POST on the weblog. */
    private boolean userMayPost = true;
    /** Whether the authenticated user is a global administrator. */
    private boolean userIsGlobalAdmin;

    @BeforeEach
    void setUp() throws Exception {
        controller = prepare(new EntryEditController());
        bean = new EntryBean();
        model = newModel();

        category = new WeblogCategory();
        category.setId("cat-1");
        category.setName("Travel");
        category.setWeblog(weblog);
        weblog.getWeblogCategories().add(category);
        when(weblogger.getWeblogEntryManager().getWeblogCategory("cat-1")).thenReturn(category);
        when(weblogger.getWeblogEntryManager().getWeblogCategories(weblog))
                .thenReturn(java.util.List.of(category));
        when(weblogger.getPluginManager().getWeblogEntryPlugins(weblog))
                .thenReturn(java.util.Collections.emptyMap());

        // Both the weblog POST check and the global-admin check funnel through
        // UserManager.checkPermission, so the stub has to tell them apart.
        when(weblogger.getUserManager().checkPermission(any(), any())).thenAnswer(invocation -> {
            Object permission = invocation.getArgument(0);
            if (permission instanceof GlobalPermission) {
                return userIsGlobalAdmin;
            }
            if (permission instanceof WeblogPermission weblogPermission) {
                return userMayPost || !weblogPermission.hasAction(WeblogPermission.POST);
            }
            return false;
        });

        // The editor builds a preview link for any entry that has an id, and
        // WeblogEntry generates one in its constructor, so this is on every path.
        when(weblogger.getUrlStrategy().getPreviewURLStrategy(any()))
                .thenReturn(org.mockito.Mockito.mock(
                        org.apache.roller.weblogger.business.URLStrategy.class));

        bean.setCategoryId("cat-1");
        bean.setTitle("A new post");
        bean.setText("Body text");
    }

    // --- entryAdd, GET ---

    @Test
    void openingTheNewEntryFormSeedsTheBeanFromTheWeblogsLocale() {
        weblog.setLocale("fr_FR");

        String view = controller.entryAddExecute(request, model, bean);

        assertEquals(".EntryEdit", view);
        assertEquals("fr_FR", bean.getLocale());
    }

    @Test
    void openingTheNewEntryFormAttributesTheDraftToTheLoggedInUser() {
        controller.entryAddExecute(request, model, bean);

        WeblogEntry entry = (WeblogEntry) model.getAttribute("entry");
        assertNotNull(entry);
        assertEquals(USER_NAME, entry.getCreatorUserName());
        assertEquals(weblog, entry.getWebsite());
    }

    // --- entryAdd, save ---

    @Test
    void savingANewDraftStoresItAsADraftAndRedirectsToTheEditor() throws Exception {
        String view = controller.entryAddSaveDraft(request, model, bean);

        WeblogEntry saved = captureSavedEntry();
        assertEquals(PubStatus.DRAFT, saved.getStatus());
        assertEquals("redirect:/roller-ui/authoring/entryEdit!firstSave.rol?weblog="
                + WEBLOG_HANDLE + "&bean.id=" + saved.getId(), view,
                "After the first save the user must land on the edit form for the entry that "
                        + "was actually persisted, addressed by its own id");
        assertEquals(saved.getId(), bean.getId(),
                "The bean must carry the new id into the redirect");
    }

    @Test
    void savingANewDraftDoesNotAddItToTheSearchIndex() throws Exception {
        controller.entryAddSaveDraft(request, model, bean);

        verify(weblogger.getIndexManager(), never()).addEntryReIndexOperation(any(WeblogEntry.class));
        verify(weblogger.getIndexManager(), never()).removeEntryIndexOperation(any(WeblogEntry.class));
    }

    @Test
    void publishingANewEntryStoresItAsPublishedAndIndexesIt() throws Exception {
        userMayPost = true;

        controller.entryAddPublish(request, model, bean);

        WeblogEntry saved = captureSavedEntry();
        assertEquals(PubStatus.PUBLISHED, saved.getStatus());
        verify(weblogger.getIndexManager()).addEntryReIndexOperation(saved);
    }

    @Test
    void publishingWithNoDateGivenStampsThePubTimeWithTheUpdateTime() throws Exception {
        // Otherwise a published entry would have a null pub time and sort to
        // the bottom of (or fall out of) every date-ordered feed.
        userMayPost = true;
        bean.setPubTimeLocal(null);

        controller.entryAddPublish(request, model, bean);

        WeblogEntry saved = captureSavedEntry();
        assertNotNull(saved.getPubTime(), "A published entry must have a publication time");
        assertEquals(saved.getUpdateTime(), saved.getPubTime());
    }

    @Test
    void publishingWithoutPostPermissionSubmitsForReviewInstead() throws Exception {
        // A contributor with only EDIT_DRAFT must never be able to publish
        // straight to the live blog.
        userMayPost = false;

        controller.entryAddPublish(request, model, bean);

        WeblogEntry saved = captureSavedEntry();
        assertEquals(PubStatus.PENDING, saved.getStatus(),
                "A user without POST permission must land in PENDING, not PUBLISHED");
        verify(weblogger.getIndexManager(), never()).addEntryReIndexOperation(any(WeblogEntry.class));
    }

    @Test
    void publishingWithAFutureDateSchedulesTheEntry() throws Exception {
        userMayPost = true;
        setBeanPubTime(new Date(System.currentTimeMillis() + 7L * 24 * 60 * 60 * 1000));

        controller.entryAddPublish(request, model, bean);

        WeblogEntry saved = captureSavedEntry();
        assertEquals(PubStatus.SCHEDULED, saved.getStatus());
        verify(weblogger.getIndexManager(), never()).addEntryReIndexOperation(any(WeblogEntry.class));
    }

    @Test
    void publishingWithAPastDateBackdatesRatherThanSchedules() throws Exception {
        userMayPost = true;
        setBeanPubTime(new Date(System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1000));

        controller.entryAddPublish(request, model, bean);

        assertEquals(PubStatus.PUBLISHED, captureSavedEntry().getStatus(),
                "Back-dating an entry must publish it immediately, not schedule it in the past");
    }

    @Test
    void aDateInsideTheSchedulingThresholdPublishesImmediately() throws Exception {
        // The controller only treats a time as "scheduled" when it is more than
        // MIN_IN_MS ahead. bean.pubTimeLocal is minute-granularity -- a
        // datetime-local input has no seconds control -- so "the current
        // minute" is exactly the class of values this guards against being
        // mistaken for the future: truncating a "now" timestamp down to the
        // minute can only ever move it into the past or leave it unchanged,
        // never push it forward, so it must never schedule.
        userMayPost = true;
        setBeanPubTime(new Date());

        controller.entryAddPublish(request, model, bean);

        assertEquals(PubStatus.PUBLISHED, captureSavedEntry().getStatus());
    }

    // --- entryEdit ---

    @Test
    void openingAnEntryThatDoesNotExistBouncesToTheMenu() throws Exception {
        bean.setId("gone");
        when(weblogger.getWeblogEntryManager().getWeblogEntry("gone")).thenReturn(null);

        assertEquals("redirect:/roller-ui/menu.rol",
                controller.entryEditExecute(request, model, bean, newRedirectAttributes()),
                "A missing entry must not render the editor against a null entry");
    }

    /**
     * With a dozen editor tabs open -- the normal way this screen gets used --
     * every one of them read "Edit entry", so finding the right tab meant
     * clicking through them. The entry's own title has to reach the model for
     * the layout's &lt;title&gt; to carry it.
     *
     * <p>It reaches it as {@code tabTitle}, NOT by overwriting {@code
     * pageTitle}: the layouts render {@code pageTitle} a second time as the
     * visible {@code <h2 class="roller-page-title">}, so appending there put
     * the whole "Stored title -- Edit entry" string into the page's own
     * heading. The two are separate attributes precisely so the tab and the
     * heading can differ.
     */
    @Test
    void theEditorNamesTheEntryItIsEditing() throws Exception {
        existingEntry(PubStatus.DRAFT);

        controller.entryEditExecute(request, model, bean, newRedirectAttributes());

        assertTrue(String.valueOf(model.getAttribute("tabTitle")).startsWith("Stored title"),
                "tabTitle must lead with the entry's own title, was: "
                        + model.getAttribute("tabTitle"));
    }

    /** Criterion 25 (settings half): the editor shows the entry's comment counts when comments are on. */
    @Test
    void theEditorCarriesTheCommentCountsWhenTheWeblogHasCommentsOn() throws Exception {
        weblog.setCommentsEnabled(Boolean.TRUE);
        WeblogEntry entry = existingEntry(PubStatus.PUBLISHED);
        when(weblogger.commentManager().countForEntry(entry,
                org.apache.roller.weblogger.pojos.ReaderComment.Status.PUBLISHED)).thenReturn(3L);
        when(weblogger.commentManager().countForEntry(entry,
                org.apache.roller.weblogger.pojos.ReaderComment.Status.PENDING)).thenReturn(2L);

        controller.entryEditExecute(request, model, bean, newRedirectAttributes());

        assertEquals(3L, model.getAttribute("commentsPublished"));
        assertEquals(2L, model.getAttribute("commentsPending"));
    }

    /**
     * M10: the counts link to the Comments page, which needs the weblog POST
     * permission. A limited (edit_draft) member is refused that page, so the
     * editor neither counts nor links for them.
     */
    @Test
    void aMemberWhoCannotModerateGetsNoCommentCounts() throws Exception {
        weblog.setCommentsEnabled(Boolean.TRUE);
        userMayPost = false;
        existingEntry(PubStatus.DRAFT);

        controller.entryEditExecute(request, model, bean, newRedirectAttributes());

        assertNull(model.getAttribute("commentsPublished"));
        assertNull(model.getAttribute("commentsPending"));
        org.mockito.Mockito.verifyNoInteractions(weblogger.commentManager());
    }

    @Test
    void theEditorCarriesNoCommentCountsWhenCommentsAreOff() throws Exception {
        weblog.setCommentsEnabled(Boolean.FALSE);
        existingEntry(PubStatus.PUBLISHED);

        controller.entryEditExecute(request, model, bean, newRedirectAttributes());

        assertNull(model.getAttribute("commentsPublished"));
        org.mockito.Mockito.verifyNoInteractions(weblogger.commentManager());
    }

    @Test
    void theNewEntryFormCarriesNoCommentCounts() {
        weblog.setCommentsEnabled(Boolean.TRUE);

        controller.entryAddExecute(request, model, bean);

        assertNull(model.getAttribute("commentsPublished"));
    }

    /**
     * The other half of the split above: naming the tab must leave the visible
     * heading alone. A regression here is not subtle -- it prints the entry
     * title and the em dash straight into the page's own h2.
     */
    @Test
    void namingTheTabLeavesTheVisibleHeadingAlone() throws Exception {
        existingEntry(PubStatus.DRAFT);

        controller.entryEditExecute(request, model, bean, newRedirectAttributes());

        assertEquals("weblogEdit.title.editEntry", model.getAttribute("pageTitle"),
                "the h2 renders pageTitle, so it must stay the plain message key");
    }

    /** A brand-new entry has no title yet, so there is nothing to prepend and
     *  the generic heading must survive untouched -- with no tabTitle set, the
     *  layout falls back to pageTitle. */
    @Test
    void theAddScreenKeepsItsGenericTitle() {
        controller.entryAddExecute(request, model, bean);

        assertEquals("weblogEdit.title.newEntry", model.getAttribute("pageTitle"));
        assertNull(model.getAttribute("tabTitle"),
                "an unsaved entry has no title to name its tab after");
    }

    @Test
    void openingAnEntryWithNoIdBouncesToTheMenu() {
        bean.setId(null);

        assertEquals("redirect:/roller-ui/menu.rol",
                controller.entryEditExecute(request, model, bean, newRedirectAttributes()));
    }

    @Test
    void openingAnEntryWhoseLookupFailsBouncesToTheMenu() throws Exception {
        bean.setId("entry-1");
        when(weblogger.getWeblogEntryManager().getWeblogEntry("entry-1"))
                .thenThrow(new WebloggerException("database down"));

        assertEquals("redirect:/roller-ui/menu.rol",
                controller.entryEditExecute(request, model, bean, newRedirectAttributes()),
                "A lookup failure must be handled, not propagated as a 500");
    }

    @Test
    void openingAnEntryFromAnotherWeblogBouncesToTheMenu() throws Exception {
        // getWeblogEntry is a global by-id lookup, so an unchecked id would
        // hand a foreign weblog's draft content to any editor who knows it.
        WeblogEntry foreign = existingEntry(PubStatus.DRAFT);
        org.apache.roller.weblogger.pojos.Weblog other =
                new org.apache.roller.weblogger.pojos.Weblog();
        other.setId("weblog-2");
        other.setHandle("otherblog");
        foreign.setWebsite(other);

        String view = controller.entryEditExecute(request, model, bean, newRedirectAttributes());

        assertEquals("redirect:/roller-ui/menu.rol", view,
                "an entryId from another weblog must bounce, exactly like an unknown one");
        assertNull(model.getAttribute("entry"));
        assertFalse("Stored title".equals(bean.getTitle()),
                "the foreign entry's content must not reach this weblog's form");
    }

    @Test
    void openingATrashedEntryRedirectsToTheTrashScreenRatherThanTheEditor() throws Exception {
        // A bookmarked editor URL can name an entry that has since been
        // trashed. It must not render an editable form -- Save would
        // resurrect the entry to DRAFT (or straight to PUBLISHED) by a side
        // door that bypasses restore entirely, with trashedAt still
        // populated on the row.
        existingEntry(PubStatus.TRASHED);
        RedirectAttributes redirect = newRedirectAttributes();

        String view = controller.entryEditExecute(request, model, bean, redirect);

        assertEquals("redirect:/roller-ui/authoring/trash.rol?weblog=" + WEBLOG_HANDLE, view);
        assertEquals(List.of("entryEdit.entryIsTrashed"), flashErrors(redirect));
    }

    @Test
    void theFirstSaveLandingPageAlsoRefusesAnEntryFromAnotherWeblog() throws Exception {
        WeblogEntry foreign = existingEntry(PubStatus.DRAFT);
        org.apache.roller.weblogger.pojos.Weblog other =
                new org.apache.roller.weblogger.pojos.Weblog();
        other.setId("weblog-2");
        other.setHandle("otherblog");
        foreign.setWebsite(other);

        assertEquals("redirect:/roller-ui/menu.rol",
                controller.entryEditFirstSave(request, model, bean, newRedirectAttributes()));
    }

    @Test
    void theFirstSaveLandingPageAlsoRedirectsATrashedEntryToTheTrashScreen() throws Exception {
        // entryEdit!firstSave.rol is just as bookmarkable/reloadable as
        // entryEdit.rol, and resolves the same way via lookupEntry (no status
        // filter) -- so it is exposed to the exact same hazard
        // openingATrashedEntryRedirectsToTheTrashScreenRatherThanTheEditor
        // above covers for entryEdit.rol: reload this link after the entry
        // has since been trashed, and lookupEntry alone would hand back the
        // trashed entry as if it were live.
        existingEntry(PubStatus.TRASHED);
        RedirectAttributes redirect = newRedirectAttributes();

        String view = controller.entryEditFirstSave(request, model, bean, redirect);

        assertEquals("redirect:/roller-ui/authoring/trash.rol?weblog=" + WEBLOG_HANDLE, view);
        assertEquals(List.of("entryEdit.entryIsTrashed"), flashErrors(redirect));
    }

    @Test
    void savingAnEntryFromAnotherWeblogIsRefused() throws Exception {
        WeblogEntry foreign = existingEntry(PubStatus.DRAFT);
        org.apache.roller.weblogger.pojos.Weblog other =
                new org.apache.roller.weblogger.pojos.Weblog();
        other.setId("weblog-2");
        other.setHandle("otherblog");
        foreign.setWebsite(other);

        assertEquals("redirect:/roller-ui/menu.rol",
                controller.entryEditSaveDraft(request, model, bean));
        verify(weblogger.getWeblogEntryManager(), never()).saveWeblogEntry(any());
    }

    @Test
    void aSeoImageFromAnotherWeblogGetsNoPreview() throws Exception {
        // featuredImageId/ogImageId are form fields; the preview is built from
        // a global getMediaFile lookup.
        WeblogEntry existing = existingEntry(PubStatus.PUBLISHED);
        existing.setFeaturedImageId("mf-foreign");
        org.apache.roller.weblogger.pojos.Weblog other =
                new org.apache.roller.weblogger.pojos.Weblog();
        other.setId("weblog-2");
        other.setHandle("otherblog");
        org.apache.roller.weblogger.pojos.MediaFile foreign =
                new org.apache.roller.weblogger.pojos.MediaFile();
        foreign.setId("mf-foreign");
        foreign.setWeblog(other);
        when(weblogger.getMediaFileManager().getMediaFile("mf-foreign")).thenReturn(foreign);

        controller.entryEditExecute(request, model, bean, newRedirectAttributes());

        assertNull(model.getAttribute("featuredImageThumbnailUrl"),
                "a foreign media file must not produce a preview URL");
    }

    @Test
    void openingAnExistingEntryLoadsItIntoTheForm() throws Exception {
        WeblogEntry existing = existingEntry(PubStatus.PUBLISHED);
        existing.setTitle("Stored title");

        String view = controller.entryEditExecute(request, model, bean, newRedirectAttributes());

        assertEquals(".EntryEdit", view);
        assertEquals("Stored title", bean.getTitle());
        assertEquals(PubStatus.PUBLISHED.name(), bean.getStatus());
        assertEquals(existing, model.getAttribute("entry"));
    }

    @Test
    void theSeoCardsStructuredDataFieldsSurviveTheSaveAndReopen() throws Exception {
        // The whole path the SEO card's new rows depend on: the submitted
        // strings (type name, datetime-local values) reach the entity through
        // copyTo, and reopening the editor puts them back into the same inputs.
        bean.setJsonLdType(JsonLdType.EVENT.name());
        bean.setGeoLatitude(48.8584);
        bean.setGeoLongitude(2.2945);
        bean.setEventStartLocal("2026-08-02T19:30");
        bean.setEventEndLocal("2026-08-02T22:00");
        bean.setEventLocation("Champ de Mars");

        controller.entryAddSaveDraft(request, model, bean);

        WeblogEntry saved = captureSavedEntry();
        assertEquals(JsonLdType.EVENT, saved.getJsonLdType());
        assertEquals(48.8584, saved.getGeoLatitude());
        assertEquals(2.2945, saved.getGeoLongitude());
        ZoneId zone = ZoneId.of("America/New_York"); // the fixture weblog's zone
        assertEquals(Timestamp.from(LocalDateTime.of(2026, 8, 2, 19, 30).atZone(zone).toInstant()),
                saved.getEventStart());
        assertEquals(Timestamp.from(LocalDateTime.of(2026, 8, 2, 22, 0).atZone(zone).toInstant()),
                saved.getEventEnd());
        assertEquals("Champ de Mars", saved.getEventLocation());

        EntryBean reopened = new EntryBean();
        reopened.copyFrom(saved, Locale.US);
        assertEquals(JsonLdType.EVENT.name(), reopened.getJsonLdType(),
                "the dropdown must come back on the entry's own type");
        assertEquals("2026-08-02T19:30", reopened.getEventStartLocal(),
                "the datetime-local input must be re-fillable with what it submitted");
        assertEquals("Champ de Mars", reopened.getEventLocation());
    }

    @Test
    void firstSaveShowsTheStatusMessageForTheEntryThatWasJustCreated() throws Exception {
        existingEntry(PubStatus.DRAFT);

        controller.entryEditFirstSave(request, model, bean, newRedirectAttributes());

        assertTrue(messages(model).contains("weblogEdit.draftSaved"),
                "The landing page after a first save must confirm what happened: " + messages(model));
    }

    @Test
    void addStatusMessageIsADocumentedNoOpForATrashedEntry() {
        // Both real callers (entryEditExecute, entryEditFirstSave) already
        // redirect a trashed entry to the trash screen before this method is
        // ever reached, so this drives the defensive TRASHED case directly
        // rather than through either of them.
        WeblogEntry trashed = new WeblogEntry();
        trashed.setStatus(PubStatus.TRASHED);

        controller.addStatusMessage(PubStatus.TRASHED, model, trashed, request);

        assertTrue(messages(model).isEmpty(),
                "no status toast makes sense for a trashed entry: " + messages(model));
    }

    @Test
    void revertingAPublishedEntryToDraftFlagsTheAggregatesForRefresh() throws Exception {
        // Un-publishing changes the site's entry counts, so the aggregate
        // tables have to be recomputed. Missing this leaves stale counts on the
        // front page indefinitely.
        existingEntry(PubStatus.PUBLISHED);

        controller.entryEditSaveDraft(request, model, bean);

        WeblogEntry saved = captureSavedEntry();
        assertEquals(PubStatus.DRAFT, saved.getStatus());
        assertTrue(saved.getRefreshAggregates(),
                "Taking an entry off the site must trigger an aggregate refresh");
    }

    @Test
    void savingAnAlreadyDraftEntryAsDraftDoesNotTouchTheAggregates() throws Exception {
        existingEntry(PubStatus.DRAFT);

        controller.entryEditSaveDraft(request, model, bean);

        assertFalse(captureSavedEntry().getRefreshAggregates(),
                "A draft-to-draft save changes no published counts, so it must not force a refresh");
    }

    @Test
    void unpublishingAnEntryRemovesItFromTheSearchIndex() throws Exception {
        // If this is skipped, content the author has pulled offline keeps
        // turning up in site search.
        existingEntry(PubStatus.PUBLISHED);

        controller.entryEditSaveDraft(request, model, bean);

        verify(weblogger.getIndexManager()).removeEntryIndexOperation(any(WeblogEntry.class));
        verify(weblogger.getIndexManager(), never()).addEntryReIndexOperation(any(WeblogEntry.class));
    }

    @Test
    void publishingAPreviouslyUnpublishedEntryFlagsTheAggregatesForRefresh() throws Exception {
        existingEntry(PubStatus.DRAFT);
        userMayPost = true;

        controller.entryEditPublish(request, model, bean);

        WeblogEntry saved = captureSavedEntry();
        assertEquals(PubStatus.PUBLISHED, saved.getStatus());
        assertTrue(saved.getRefreshAggregates(),
                "Putting an entry on the site must trigger an aggregate refresh");
        verify(weblogger.getIndexManager()).addEntryReIndexOperation(saved);
    }

    @Test
    void schedulingAnAlreadyPublishedEntryFlagsTheAggregatesForRefresh() throws Exception {
        // Pushing a live post into the future takes it off the site now, so the
        // published counts have to be recomputed just as un-publishing does.
        existingEntry(PubStatus.PUBLISHED);
        userMayPost = true;
        setBeanPubTime(new Date(System.currentTimeMillis() + 7L * 24 * 60 * 60 * 1000));

        controller.entryEditPublish(request, model, bean);

        WeblogEntry saved = captureSavedEntry();
        assertEquals(PubStatus.SCHEDULED, saved.getStatus());
        assertTrue(saved.getRefreshAggregates(),
                "Scheduling a currently-published entry removes it from the site now");
    }

    @Test
    void schedulingAnUnpublishedEntryDoesNotTouchTheAggregates() throws Exception {
        existingEntry(PubStatus.DRAFT);
        userMayPost = true;
        setBeanPubTime(new Date(System.currentTimeMillis() + 7L * 24 * 60 * 60 * 1000));

        controller.entryEditPublish(request, model, bean);

        assertFalse(captureSavedEntry().getRefreshAggregates(),
                "A draft was never counted, so scheduling it changes no totals");
    }

    @Test
    void aSuccessfulSaveIsCommitted() throws Exception {
        // Everything above verifies the manager was called; without a flush
        // none of it reaches the database.
        controller.entryAddSaveDraft(request, model, bean);

        assertEquals(1, weblogger.flushCount(), "A successful save must be committed");
    }

    @Test
    void aFailedSaveIsNotCommitted() throws Exception {
        doThrow(new WebloggerException("constraint violation"))
                .when(weblogger.getWeblogEntryManager()).saveWeblogEntry(any());

        controller.entryAddSaveDraft(request, model, bean);

        assertEquals(0, weblogger.flushCount());
    }

    @Test
    void aPublishedEntrysPubTimeIsParsedInTheWeblogsTimezone() throws Exception {
        // bean.pubTimeLocal is a datetime-local value with no offset; it must
        // be read as wall-clock time in the WEBLOG's zone, not the server's --
        // exactly what the old dateString/hours/minutes/seconds combination did.
        userMayPost = true;
        weblog.setTimeZone("America/New_York");
        bean.setPubTimeLocal("2020-01-01T09:00");

        controller.entryAddPublish(request, model, bean);

        WeblogEntry saved = captureSavedEntry();
        Timestamp expected = Timestamp.from(
                LocalDateTime.of(2020, 1, 1, 9, 0).atZone(ZoneId.of("America/New_York")).toInstant());
        assertEquals(expected, saved.getPubTime());
    }

    @Test
    void aBlankPubTimeLocalFallsBackToPublishingNow() throws Exception {
        userMayPost = true;
        bean.setPubTimeLocal("");

        controller.entryAddPublish(request, model, bean);

        WeblogEntry saved = captureSavedEntry();
        assertEquals(PubStatus.PUBLISHED, saved.getStatus());
        assertEquals(saved.getUpdateTime(), saved.getPubTime(),
                "A blank pubTimeLocal must fall back to publish-now, exactly like the old blank "
                        + "dateString did");
    }

    @Test
    void aMalformedPubTimeLocalIsRejectedAndNothingIsSaved() throws Exception {
        // The old dateString parser swallowed a parse failure and quietly
        // published "now" instead. A hand-crafted POST with an unparseable
        // pubTimeLocal must surface a validation error and save nothing,
        // exactly like the canonicalUrl validation just above it.
        bean.setPubTimeLocal("not-a-datetime");

        String view = controller.entryAddSaveDraft(request, model, bean);

        assertEquals(".EntryEdit", view, "a rejected pubTimeLocal must redisplay the form");
        assertTrue(errors(model).contains("entryEdit.pubTimeInvalid"),
                "Expected a pubTimeInvalid error, got: " + errors(model));
        verify(weblogger.getWeblogEntryManager(), never()).saveWeblogEntry(any());
    }

    /**
     * Task M1 (task B8's field errors, applied to the entry editor). The
     * banner says a save was refused; only the marker says which of the
     * editor's thirty-odd controls did it -- and the pubtime field is in a
     * rail an author may well have scrolled past, next to an SEO drawer that
     * is collapsed by default. Package B converted every other admin
     * controller and deliberately skipped this one because Package A owned
     * the file it renders.
     */
    @Test
    void aMalformedPubTimeLocalMarksTheFieldItNames() throws Exception {
        bean.setPubTimeLocal("not-a-datetime");

        controller.entryAddSaveDraft(request, model, bean);

        assertEquals(java.util.List.of("entry_bean_pubTimeLocal"), invalidFields(model),
                "the refusal must name the control's own id, which EntryEdit.jsp renders "
                        + "on the datetime-local input");
    }

    @Test
    void eventTimesAreWallClockInTheWeblogsZone() throws Exception {
        weblog.setTimeZone("Europe/Berlin");
        bean.setEventStartLocal("2026-08-02T19:30");
        bean.setEventEndLocal("2026-08-02T22:00");

        controller.entryAddSaveDraft(request, model, bean);

        WeblogEntry saved = captureSavedEntry();
        assertEquals(Timestamp.from(java.time.Instant.parse("2026-08-02T17:30:00Z")), saved.getEventStart());
        assertEquals(Timestamp.from(java.time.Instant.parse("2026-08-02T20:00:00Z")), saved.getEventEnd());
    }

    @Test
    void anEventEndBeforeItsStartIsAFieldErrorAndNothingIsSaved() throws Exception {
        bean.setEventStartLocal("2026-08-02T19:30");
        bean.setEventEndLocal("2026-08-02T18:00");

        controller.entryAddSaveDraft(request, model, bean);

        assertEquals(java.util.List.of("seo_eventEnd"), invalidFields(model));
        assertTrue(errors(model).contains("entryEdit.eventEndBeforeStart"), errors(model).toString());
        verify(weblogger.getWeblogEntryManager(), never()).saveWeblogEntry(any());
    }

    @Test
    void anUnparseableEventStartIsAFieldErrorAndNothingIsSaved() throws Exception {
        bean.setEventStartLocal("soon");

        controller.entryAddSaveDraft(request, model, bean);

        assertEquals(java.util.List.of("seo_eventStart"), invalidFields(model));
        assertTrue(errors(model).contains("entryEdit.eventTimeInvalid"), errors(model).toString());
        verify(weblogger.getWeblogEntryManager(), never()).saveWeblogEntry(any());
    }

    @Test
    void theEditorOffersTheWeblogsCategories() throws Exception {
        controller.entryAddExecute(request, model, bean);

        assertEquals(java.util.List.of(category), model.getAttribute("categories"));
    }

    @Test
    void aFailureListingCategoriesLeavesAnEmptyDropdownRatherThanBreakingTheEditor() throws Exception {
        when(weblogger.getWeblogEntryManager().getWeblogCategories(weblog))
                .thenThrow(new WebloggerException("database down"));

        controller.entryAddExecute(request, model, bean);

        assertTrue(((java.util.List<?>) model.getAttribute("categories")).isEmpty(),
                "A null here would break the JSP outright");
    }

    @Test
    void republishingAnAlreadyPublishedEntryDoesNotTouchTheAggregates() throws Exception {
        existingEntry(PubStatus.PUBLISHED);
        userMayPost = true;

        controller.entryEditPublish(request, model, bean);

        assertFalse(captureSavedEntry().getRefreshAggregates(),
                "Editing a live entry does not change the published count");
    }

    @Test
    void editingStaysOnTheEditorRatherThanRedirecting() throws Exception {
        existingEntry(PubStatus.DRAFT);

        assertEquals(".EntryEdit", controller.entryEditSaveDraft(request, model, bean),
                "Only the very first save redirects; subsequent saves stay on the form");
    }

    // --- status messages ---

    @Test
    void eachPublicationStateGetsItsOwnConfirmationMessage() throws Exception {
        assertEquals("weblogEdit.draftSaved", statusMessageAfterEditSave(PubStatus.DRAFT, false, null));
        assertEquals("weblogEdit.publishedEntry", statusMessageAfterEditSave(PubStatus.DRAFT, true, null));
        assertEquals("weblogEdit.submittedForReview",
                statusMessageAfterEditSave(PubStatus.DRAFT, true, Boolean.FALSE));
    }

    @Test
    void publishingConfirmsWithTheEntrysOwnPermalink() throws Exception {
        // The publish banner is where an author goes to check the post, so it
        // carries a link. The href must be built here, server-side, from the
        // entry: messages.jsp renders a message unescaped, so a reader-supplied
        // argument would be markup injection.
        registerMessage("weblogEdit.publishedEntry", "published <a href=\"{0}\">View it</a>");
        existingEntry(PubStatus.DRAFT);
        userMayPost = true;
        // The permalink is built from the injected strategy (the entity no
        // longer builds one), so stub the strategy and expect exactly its answer.
        when(weblogger.urlStrategy().getWeblogEntryURL(any(), org.mockito.ArgumentMatchers.isNull(),
                any(), org.mockito.ArgumentMatchers.eq(true)))
                .thenReturn("http://example.com/testblog/entry/the-post");

        controller.entryEditPublish(request, model, bean);

        assertTrue(messages(model).contains(
                        "published <a href=\"http://example.com/testblog/entry/the-post\">View it</a>"),
                "Expected the publish message to carry the entry's permalink, got: "
                        + messages(model));
    }

    @Test
    void schedulingAnEntryConfirmsWithTheDateItWillGoLive() throws Exception {
        // The scheduled message is the only one that carries an argument; the
        // author needs to be told *when*, not just that it was scheduled.
        registerMessage("weblogEdit.scheduledEntry", "scheduled for {0}");
        existingEntry(PubStatus.DRAFT);
        userMayPost = true;
        setBeanPubTime(new Date(System.currentTimeMillis() + 7L * 24 * 60 * 60 * 1000));

        controller.entryEditPublish(request, model, bean);

        assertTrue(messages(model).stream().anyMatch(m -> m.startsWith("scheduled for ")),
                "Expected a scheduled-for message carrying the date, got: " + messages(model));
    }

    // --- pinned to main ---

    @Test
    void onlyGlobalAdminsCanPinAnEntryToTheFrontPage() throws Exception {
        // pinnedToMain promotes an entry onto the site-wide front page, across
        // every blog. A weblog admin must not be able to grant it to themselves
        // just by posting the checkbox.
        userIsGlobalAdmin = false;
        bean.setPinnedToMain(true);

        controller.entryAddSaveDraft(request, model, bean);

        assertFalse(Boolean.TRUE.equals(captureSavedEntry().getPinnedToMain()),
                "A non-admin must not be able to pin an entry to the site front page");
    }

    @Test
    void globalAdminsCanPinAnEntryToTheFrontPage() throws Exception {
        userIsGlobalAdmin = true;
        bean.setPinnedToMain(true);

        controller.entryAddSaveDraft(request, model, bean);

        assertTrue(captureSavedEntry().getPinnedToMain());
    }

    // --- error handling ---

    @Test
    void aFailedSaveReportsAnErrorAndStaysOnTheForm() throws Exception {
        doThrow(new WebloggerException("constraint violation"))
                .when(weblogger.getWeblogEntryManager()).saveWeblogEntry(any());

        String view = controller.entryAddSaveDraft(request, model, bean);

        assertEquals(".EntryEdit", view, "A failed save must redisplay the form, not redirect away");
        assertTrue(errors(model).contains("generic.error.check.logs"),
                "Expected an error to be reported, got: " + errors(model));
        verify(weblogger.getIndexManager(), never()).addEntryReIndexOperation(any(WeblogEntry.class));
    }

    @Test
    void aFailedAddClearsTheStatusSoTheFormDoesNotClaimTheEntryWasSaved() throws Exception {
        doThrow(new WebloggerException("constraint violation"))
                .when(weblogger.getWeblogEntryManager()).saveWeblogEntry(any());

        controller.entryAddSaveDraft(request, model, bean);

        assertNull(bean.getStatus(),
                "After a failed add the form must not still show a publication status");
    }

    @Test
    void anEntryFiledUnderAnotherWeblogsCategoryIsRefused() throws Exception {
        // copyTo raises this; the controller has to turn it into an error
        // rather than a stack trace, and must not persist anything.
        WeblogCategory foreign = new WeblogCategory();
        foreign.setId("cat-foreign");
        foreign.setName("Theirs");
        org.apache.roller.weblogger.pojos.Weblog other =
                new org.apache.roller.weblogger.pojos.Weblog();
        other.setId("weblog-2");
        foreign.setWeblog(other);
        when(weblogger.getWeblogEntryManager().getWeblogCategory("cat-foreign")).thenReturn(foreign);
        bean.setCategoryId("cat-foreign");

        controller.entryAddSaveDraft(request, model, bean);

        assertTrue(errors(model).contains("generic.error.check.logs"),
                "A cross-weblog category must be refused: " + errors(model));
        verify(weblogger.getWeblogEntryManager(), never()).saveWeblogEntry(any());
    }

    /**
     * Characterisation: the controller owns the category lookup now (the form
     * bean is pure), and keeps the semantics the bean had -- a lookup that
     * throws is reported as an error, not a stack trace, and nothing is saved.
     * Expected to pass on arrival; it exists so moving the lookup out of
     * {@code EntryBean.copyTo} could not change it.
     */
    @Test
    void aCategoryLookupFailureIsReportedRatherThanThrown() throws Exception {
        when(weblogger.getWeblogEntryManager().getWeblogCategory("cat-1"))
                .thenThrow(new WebloggerException("database down"));
        bean.setCategoryId("cat-1");

        controller.entryAddSaveDraft(request, model, bean);

        assertTrue(errors(model).contains("generic.error.check.logs"),
                "A failed category lookup must be reported: " + errors(model));
        verify(weblogger.getWeblogEntryManager(), never()).saveWeblogEntry(any());
    }

    // --- SEO panel ---

    @Test
    void savingCarriesTheSeoFieldsOntoTheEntry() throws Exception {
        // The SEO card's inputs bind straight onto the bean; a field dropped in
        // copyTo would silently discard what the author typed.
        bean.setMetaTitle("A meta title");
        bean.setSearchDescription("A meta description");
        bean.setFeaturedImageId("mf-featured");
        bean.setOgImageId("mf-og");
        bean.setCanonicalUrl("https://example.com/the-original");
        bean.setNoindex(true);

        controller.entryAddSaveDraft(request, model, bean);

        WeblogEntry saved = captureSavedEntry();
        assertEquals("A meta title", saved.getMetaTitle());
        assertEquals("A meta description", saved.getSearchDescription());
        assertEquals("mf-featured", saved.getFeaturedImageId());
        assertEquals("mf-og", saved.getOgImageId());
        assertEquals("https://example.com/the-original", saved.getCanonicalUrl());
        assertEquals(Boolean.TRUE, saved.getNoindex());
    }

    @Test
    void aNonHttpCanonicalUrlIsRejectedAndNothingIsSaved() throws Exception {
        // A stored javascript:/data:/file: URL would be emitted straight into
        // <link rel="canonical">, og:url and JSON-LD mainEntityOfPage -- this
        // is the front door that keeps one from ever being saved in the
        // first place (PageModel#getCanonicalUrl is the back door, for rows
        // that predate this check).
        bean.setCanonicalUrl("data:text/html,x");

        String view = controller.entryAddSaveDraft(request, model, bean);

        assertEquals(".EntryEdit", view, "a rejected canonical URL must redisplay the form");
        assertTrue(errors(model).contains("entryEdit.canonicalUrlInvalid"),
                "Expected a canonicalUrlInvalid error, got: " + errors(model));
        verify(weblogger.getWeblogEntryManager(), never()).saveWeblogEntry(any());
    }

    @Test
    void anHttpsCanonicalUrlIsAccepted() throws Exception {
        bean.setCanonicalUrl("https://example.com/x");

        controller.entryAddSaveDraft(request, model, bean);

        assertTrue(errors(model).isEmpty(), "Expected no error, got: " + errors(model));
        assertEquals("https://example.com/x", captureSavedEntry().getCanonicalUrl());
    }

    @Test
    void aBlankCanonicalUrlIsAccepted() throws Exception {
        bean.setCanonicalUrl("");

        controller.entryAddSaveDraft(request, model, bean);

        assertTrue(errors(model).isEmpty(), "Expected no error, got: " + errors(model));
        verify(weblogger.getWeblogEntryManager()).saveWeblogEntry(any());
    }

    @Test
    void openingAnExistingEntryLoadsTheSeoFieldsIntoTheForm() throws Exception {
        WeblogEntry existing = existingEntry(PubStatus.PUBLISHED);
        existing.setMetaTitle("Stored meta title");
        existing.setSearchDescription("Stored description");
        existing.setFeaturedImageId("mf-featured");
        existing.setOgImageId("mf-og");
        existing.setCanonicalUrl("https://example.com/stored");
        existing.setNoindex(Boolean.TRUE);

        controller.entryEditExecute(request, model, bean, newRedirectAttributes());

        assertEquals("Stored meta title", bean.getMetaTitle());
        assertEquals("Stored description", bean.getSearchDescription());
        assertEquals("mf-featured", bean.getFeaturedImageId());
        assertEquals("mf-og", bean.getOgImageId());
        assertEquals("https://example.com/stored", bean.getCanonicalUrl());
        assertTrue(bean.getNoindex());
    }

    @Test
    void theEditorShowsThumbnailsForTheStoredSeoImages() throws Exception {
        WeblogEntry existing = existingEntry(PubStatus.PUBLISHED);
        existing.setFeaturedImageId("mf-featured");
        existing.setOgImageId("mf-og");
        givenMediaFile("mf-featured");
        givenMediaFile("mf-og");

        controller.entryEditExecute(request, model, bean, newRedirectAttributes());

        assertEquals("http://media/mf-featured?t=true",
                model.getAttribute("featuredImageThumbnailUrl"));
        assertEquals("http://media/mf-og?t=true", model.getAttribute("ogImageThumbnailUrl"));
    }

    @Test
    void aDanglingImageIdRendersNoPreviewRatherThanBreakingTheEditor() throws Exception {
        // The picked image can be deleted from the media library afterwards;
        // the editor must still open, just without a preview.
        WeblogEntry existing = existingEntry(PubStatus.PUBLISHED);
        existing.setFeaturedImageId("mf-deleted");
        existing.setOgImageId("mf-broken");
        when(weblogger.getMediaFileManager().getMediaFile("mf-deleted")).thenReturn(null);
        when(weblogger.getMediaFileManager().getMediaFile("mf-broken"))
                .thenThrow(new WebloggerException("database down"));

        String view = controller.entryEditExecute(request, model, bean, newRedirectAttributes());

        assertEquals(".EntryEdit", view);
        assertNull(model.getAttribute("featuredImageThumbnailUrl"));
        assertNull(model.getAttribute("ogImageThumbnailUrl"));
    }

    @Test
    void anEntryWithoutSeoImagesAsksTheMediaTierForNothing() throws Exception {
        existingEntry(PubStatus.PUBLISHED);

        controller.entryEditExecute(request, model, bean, newRedirectAttributes());

        verify(weblogger.getMediaFileManager(), never()).getMediaFile(any());
        assertNull(model.getAttribute("featuredImageThumbnailUrl"));
        assertNull(model.getAttribute("ogImageThumbnailUrl"));
    }

    // --- helpers ---

    /** Stub a media file whose thumbnail resolves to {@code http://media/<id>?t=true}. */
    private void givenMediaFile(String id) throws WebloggerException {
        org.apache.roller.weblogger.pojos.MediaFile mediaFile =
                new org.apache.roller.weblogger.pojos.MediaFile();
        mediaFile.setId(id);
        mediaFile.setWeblog(weblog);
        when(weblogger.getMediaFileManager().getMediaFile(id)).thenReturn(mediaFile);
        when(weblogger.getUrlStrategy().getMediaFileThumbnailURL(weblog, id, true))
                .thenReturn("http://media/" + id + "?t=true");
    }

    private WeblogEntry existingEntry(PubStatus status) throws WebloggerException {
        WeblogEntry entry = new WeblogEntry();
        entry.setId("entry-1");
        entry.setTitle("Stored title");
        entry.setWebsite(weblog);
        entry.setCategory(category);
        entry.setStatus(status);
        entry.setCreatorUserName(USER_NAME);
        when(weblogger.getWeblogEntryManager().getWeblogEntry("entry-1")).thenReturn(entry);
        bean.setId("entry-1");
        return entry;
    }

    private WeblogEntry captureSavedEntry() throws WebloggerException {
        ArgumentCaptor<WeblogEntry> captor = ArgumentCaptor.forClass(WeblogEntry.class);
        verify(weblogger.getWeblogEntryManager(), atLeastOnce()).saveWeblogEntry(captor.capture());
        return captor.getValue();
    }

    private static final DateTimeFormatter PUB_TIME_LOCAL =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm", Locale.ROOT);

    private void setBeanPubTime(Date when) {
        LocalDateTime local = LocalDateTime.ofInstant(when.toInstant(), ZoneId.of("America/New_York"));
        bean.setPubTimeLocal(local.format(PUB_TIME_LOCAL));
    }

    /**
     * Drive an edit-save and return the single status message it produced.
     *
     * @param publish     whether to take the publish action rather than save-draft
     * @param mayPost     null to leave POST permission as-is, FALSE to withhold it
     */
    private String statusMessageAfterEditSave(PubStatus startingStatus, boolean publish,
                                              Boolean mayPost) throws Exception {
        setUp();
        existingEntry(startingStatus);
        if (mayPost != null) {
            userMayPost = mayPost;
        }
        if (publish) {
            controller.entryEditPublish(request, model, bean);
        } else {
            controller.entryEditSaveDraft(request, model, bean);
        }
        assertEquals(1, messages(model).size(),
                "Expected exactly one status message, got: " + messages(model));
        return messages(model).get(0);
    }

    // ------------------------------------------------------- live preview

    @Test
    void previewRunsUnsavedTextThroughTheRealPipeline() {
        // The whole point of rendering the preview server-side: it must agree
        // with the published page, which means the same markdown + shortcode +
        // sanitize chain rather than a markdown library in the browser.
        var response = controller.entryEditPreview(request, null,
                "## Trip notes\n\nA **long** drive.");

        assertEquals(200, response.getStatusCode().value());
        String html = response.getBody();
        assertTrue(html.contains("<h2 id=\"trip-notes\">Trip notes</h2>"), html);
        assertTrue(html.contains("<strong>long</strong>"), html);
    }

    @Test
    void previewSanitizesWhatItRenders() {
        // commonmark passes raw HTML through by design, and the preview pane
        // lives inside the admin page -- so the sanitizer has to run here too.
        var response = controller.entryEditPreview(request, null,
                "before\n\n<script>alert(1)</script>\n");

        assertFalse(response.getBody().contains("<script"), response.getBody());
    }

    @Test
    void previewOfAForeignEntryIsNotFound() {
        // Same ownership rule as every other entry action: the text comes from
        // the request, but the entry must belong to the action weblog.
        var response = controller.entryEditPreview(request, "no-such-entry-id", "anything");

        assertEquals(404, response.getStatusCode().value());
    }

    // --------------------------------------------------- the preview shell

    /**
     * The editor frames the weblog's own theme rather than dumping the
     * rendered fragment into a div, so the page needs the shell's URL. It is
     * built here rather than by the preview URL strategy because the strategy
     * answers with the weblog's <em>root</em>; the shell is the same document
     * asked for with one query parameter, and building it from the parts is
     * one string rather than a strategy call whose contract does not cover it.
     */
    @Test
    void theEditorIsToldWhereThePreviewShellLives() {
        // The context URL is a process-global static, so pin it rather than
        // asserting against whatever an earlier test class left behind.
        String previous = WebloggerRuntimeConfig.getRelativeContextURL();
        WebloggerRuntimeConfig.setRelativeContextURL("");
        try {
            controller.entryAddExecute(request, model, bean);

            assertEquals("/roller-ui/authoring/preview/" + WEBLOG_HANDLE + "/?shell=true",
                    model.getAttribute("previewShellURL"));
        } finally {
            WebloggerRuntimeConfig.setRelativeContextURL(previous);
        }
    }

    /**
     * Under a servlet context prefix the shell is not at the site root, and an
     * iframe pointed at an absolute-root path would load the login page (or
     * nothing) instead. This is the same trap {@code ContactShortcode} hit --
     * see CLAUDE.md's Audience section -- so the URL carries the context path
     * the same way that one does.
     */
    @Test
    void thePreviewShellUrlCarriesTheServletContextPath() {
        String previous = WebloggerRuntimeConfig.getRelativeContextURL();
        WebloggerRuntimeConfig.setRelativeContextURL("/roller");
        try {
            controller.entryAddExecute(request, model, bean);

            assertEquals("/roller/roller-ui/authoring/preview/" + WEBLOG_HANDLE + "/?shell=true",
                    model.getAttribute("previewShellURL"));
        } finally {
            WebloggerRuntimeConfig.setRelativeContextURL(previous);
        }
    }

    // ------------------------------------------------------------- revisions

    /**
     * Restoring rewrites the entry's content, so the revision id is exactly as
     * dangerous as an entry id. It arrives as client input and must be proven
     * to belong to the entry the caller may edit -- otherwise another weblog's
     * unpublished draft could be pushed into this one.
     */
    @Test
    void aRevisionBelongingToAnotherEntryIsNotRestored() throws Exception {
        WeblogEntry entry = existingEntry(PubStatus.PUBLISHED);
        entry.setText("Current text");

        WeblogEntry otherEntry = new WeblogEntry();
        otherEntry.setId("entry-2");
        WeblogEntryRevision foreign = new WeblogEntryRevision();
        foreign.setId("revision-1");
        foreign.setWeblogEntry(otherEntry);
        foreign.setText("Someone else's draft");
        when(weblogger.getWeblogEntryManager().getRevision("revision-1")).thenReturn(foreign);

        RedirectAttributes redirect = newRedirectAttributes();
        controller.entryEditRestoreRevision(request, "entry-1", "revision-1", redirect);

        assertEquals("Current text", entry.getText(), "another entry's revision was restored");
        verify(weblogger.getWeblogEntryManager(), never()).saveWeblogEntry(any());
        assertEquals(java.util.List.of("weblogEntry.notFound"), flashErrors(redirect));
    }

    @Test
    void restoringPutsTheRevisionsContentBackThroughTheNormalSave() throws Exception {
        WeblogEntry entry = existingEntry(PubStatus.PUBLISHED);
        entry.setText("Current text");
        entry.setTitle("Current title");

        WeblogEntryRevision revision = new WeblogEntryRevision();
        revision.setId("revision-1");
        revision.setWeblogEntry(entry);
        revision.setTitle("Older title");
        revision.setText("Older text");
        revision.setSummary("Older summary");
        when(weblogger.getWeblogEntryManager().getRevision("revision-1")).thenReturn(revision);

        RedirectAttributes redirect = newRedirectAttributes();
        String view = controller.entryEditRestoreRevision(request, "entry-1", "revision-1", redirect);

        assertEquals("Older title", entry.getTitle());
        assertEquals("Older text", entry.getText());
        assertEquals("Older summary", entry.getSummary());
        // Through saveWeblogEntry, which is what makes the replaced version a
        // revision of its own and the restore itself undoable.
        verify(weblogger.getWeblogEntryManager()).saveWeblogEntry(entry);
        assertEquals("redirect:/roller-ui/authoring/entryEdit.rol?weblog=" + WEBLOG_HANDLE
                + "&bean.id=entry-1", view);
    }

    @Test
    void aDiffForAnUnknownRevisionIsNotFound() throws Exception {
        existingEntry(PubStatus.PUBLISHED);
        when(weblogger.getWeblogEntryManager().getRevision("no-such-revision")).thenReturn(null);

        var response = controller.entryEditRevisionDiff(request, "entry-1", "no-such-revision");

        assertEquals(404, response.getStatusCode().value());
    }

    /**
     * A revision holds the author's raw Markdown, which may contain any HTML at
     * all. The diff shows it as source, so it must arrive escaped -- otherwise
     * the modal would execute whatever an entry once contained.
     */
    @Test
    void theDiffEscapesTheContentItDisplays() throws Exception {
        WeblogEntry entry = existingEntry(PubStatus.PUBLISHED);
        entry.setText("after");

        WeblogEntryRevision revision = new WeblogEntryRevision();
        revision.setId("revision-1");
        revision.setWeblogEntry(entry);
        revision.setText("<script>alert(1)</script>");
        when(weblogger.getWeblogEntryManager().getRevision("revision-1")).thenReturn(revision);

        var response = controller.entryEditRevisionDiff(request, "entry-1", "revision-1");

        assertEquals(200, response.getStatusCode().value());
        String body = response.getBody();
        assertTrue(body != null && !body.contains("<script>"),
                "the diff rendered raw markup from a revision: " + body);
        assertTrue(body.contains("&lt;script&gt;"), "the removed line must appear, escaped");
    }

    // ---------------------------------------- revisions: diff content and failures

    private WeblogEntryRevision revisionOf(WeblogEntry entry, String id) throws Exception {
        WeblogEntryRevision revision = new WeblogEntryRevision();
        revision.setId(id);
        revision.setWeblogEntry(entry);
        when(weblogger.getWeblogEntryManager().getRevision(id)).thenReturn(revision);
        return revision;
    }

    @Test
    void theDiffMarksAddedRemovedAndUnchangedLinesAndOmitsUnchangedSections() throws Exception {
        WeblogEntry entry = existingEntry(PubStatus.PUBLISHED);
        entry.setTitle("Same title");
        entry.setSummary(null);
        entry.setText("kept\nnew line");
        WeblogEntryRevision revision = revisionOf(entry, "r1");
        revision.setTitle("Same title");
        revision.setSummary(null);
        revision.setText("kept\nold line");

        String body = controller.entryEditRevisionDiff(request, "entry-1", "r1").getBody();

        assertTrue(body.contains("<span class=\"diff-same\">  kept</span>"), body);
        assertTrue(body.contains("<span class=\"diff-removed\">- old line</span>"), body);
        assertTrue(body.contains("<span class=\"diff-added\">+ new line</span>"), body);
        assertEquals(1, body.split("<h5>", -1).length - 1, "only the changed section is shown: " + body);
    }

    @Test
    void aDiffWithNoRevisionIdOrNoEntryIsNotFound() throws Exception {
        existingEntry(PubStatus.PUBLISHED);

        assertEquals(404, controller.entryEditRevisionDiff(request, "entry-1", null).getStatusCode().value());
        assertEquals(404, controller.entryEditRevisionDiff(request, "no-such-entry", "r1").getStatusCode().value());
    }

    @Test
    void aRevisionLookupThatFailsIsNotFoundRatherThanAnError() throws Exception {
        existingEntry(PubStatus.PUBLISHED);
        when(weblogger.getWeblogEntryManager().getRevision("r1")).thenThrow(new WebloggerException("store down"));

        assertEquals(404, controller.entryEditRevisionDiff(request, "entry-1", "r1").getStatusCode().value());
    }

    @Test
    void aRevisionWithNoEntryRecordedIsNotRestoredOrShown() throws Exception {
        WeblogEntry entry = existingEntry(PubStatus.PUBLISHED);
        entry.setText("Current text");
        WeblogEntryRevision orphan = revisionOf(entry, "r1");
        orphan.setWeblogEntry(null);
        orphan.setText("orphan");

        assertEquals(404, controller.entryEditRevisionDiff(request, "entry-1", "r1").getStatusCode().value());
        RedirectAttributes redirect = newRedirectAttributes();
        controller.entryEditRestoreRevision(request, "entry-1", "r1", redirect);
        assertEquals("Current text", entry.getText());
        assertEquals(java.util.List.of("weblogEntry.notFound"), flashErrors(redirect));
    }

    @Test
    void restoringAnUnpublishedEntryDoesNotPutItInTheSearchIndex() throws Exception {
        WeblogEntry entry = existingEntry(PubStatus.DRAFT);
        WeblogEntryRevision revision = revisionOf(entry, "r1");
        revision.setText("Older text");

        controller.entryEditRestoreRevision(request, "entry-1", "r1", newRedirectAttributes());

        assertEquals("Older text", entry.getText());
        verify(weblogger.getIndexManager(), never()).addEntryReIndexOperation(any(WeblogEntry.class));
    }

    @Test
    void restoringAPublishedEntryReindexesIt() throws Exception {
        WeblogEntry entry = existingEntry(PubStatus.PUBLISHED);
        revisionOf(entry, "r1").setText("Older text");

        RedirectAttributes redirect = newRedirectAttributes();
        controller.entryEditRestoreRevision(request, "entry-1", "r1", redirect);

        verify(weblogger.getIndexManager()).addEntryReIndexOperation(entry);
        assertEquals(java.util.List.of("weblogEdit.revisionRestored"), flashMessages(redirect));
    }

    @Test
    void aRestoreThatFailsToSaveReportsAnErrorAndNoSuccess() throws Exception {
        WeblogEntry entry = existingEntry(PubStatus.PUBLISHED);
        revisionOf(entry, "r1").setText("Older text");
        org.mockito.Mockito.doThrow(new WebloggerException("store down"))
                .when(weblogger.getWeblogEntryManager()).saveWeblogEntry(entry);

        RedirectAttributes redirect = newRedirectAttributes();
        String view = controller.entryEditRestoreRevision(request, "entry-1", "r1", redirect);

        assertEquals(java.util.List.of("generic.error.check.logs"), flashErrors(redirect));
        assertTrue(flashMessages(redirect).isEmpty(), "a failed restore must not claim success");
        assertTrue(view.startsWith("redirect:/roller-ui/authoring/entryEdit.rol"), view);
    }

    @Test
    void aBlankIdPreviewsAgainstAScratchEntryOfTheActionWeblogAndPersistsNothing() throws Exception {
        var response = controller.entryEditPreview(request, "  ", "plain **text**");

        assertEquals(200, response.getStatusCode().value());
        assertTrue(response.getBody().contains("<strong>text</strong>"), response.getBody());
        verify(weblogger.getWeblogEntryManager(), never()).saveWeblogEntry(any());
    }

    @Test
    void aPreviewWithNoTextAtAllRendersAnEmptyBody() {
        var response = controller.entryEditPreview(request, null, null);

        assertEquals(200, response.getStatusCode().value());
        assertTrue(response.getBody().isBlank(), response.getBody());
    }
}
