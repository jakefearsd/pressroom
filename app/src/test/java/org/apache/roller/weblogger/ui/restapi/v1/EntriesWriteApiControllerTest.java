package org.apache.roller.weblogger.ui.restapi.v1;

import java.util.List;
import org.apache.roller.weblogger.business.WeblogEntryManager;
import org.apache.roller.weblogger.business.Weblogger;
import org.apache.roller.weblogger.business.URLStrategy;
import org.apache.roller.weblogger.business.search.IndexManager;
import org.apache.roller.weblogger.pojos.User;
import org.apache.roller.weblogger.pojos.Weblog;
import org.apache.roller.weblogger.pojos.WeblogCategory;
import org.apache.roller.weblogger.pojos.WeblogEntry;
import org.apache.roller.weblogger.pojos.WeblogPermission;
import org.apache.roller.weblogger.ui.restapi.ApiExceptionHandler;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.apache.roller.weblogger.ui.restapi.ErrorFieldMatchers.errorFieldIs;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Controller-level tests for {@link EntriesWriteApi}: routing, status codes,
 * the ownership/authentication checks, and JSON shape -- none of which
 * {@code EntriesApiWriteTest} (the {@code EntryDtos.applyWrite} unit test)
 * can see. actionWeblog/authenticatedUser are injected as request
 * attributes rather than resolved by a real interceptor, matching
 * {@code EntriesApiReadTest}/{@code CategoriesApiTest}'s standalone style;
 * these run against a Mockito-mocked Weblogger, not the DB fixtures.
 */
class EntriesWriteApiControllerTest {

    private MockMvc mockMvc(EntriesWriteApi controller) {
        return MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    private Weblogger mockedWeblogger() {
        Weblogger weblogger = mock(Weblogger.class);
        WeblogEntryManager entryManager = mock(WeblogEntryManager.class);
        when(weblogger.getWeblogEntryManager()).thenReturn(entryManager);
        when(weblogger.getUrlStrategy()).thenReturn(mock(URLStrategy.class));
        // EntryDeletion.trashEntryWithIndex/deleteEntryForeverWithIndex both
        // reach the index manager before touching the entry manager.
        when(weblogger.getIndexManager()).thenReturn(mock(IndexManager.class));
        // preview renders through the tier's renderer; a real one over this
        // facade's mocks gives real markdown output
        when(weblogger.getEntryRenderer()).thenReturn(
                new org.apache.roller.weblogger.business.EntryRenderer(
                        org.apache.roller.weblogger.business.shortcodes.ShortcodeExpander.builtIn(
                                weblogger, mock(org.apache.roller.weblogger.business.MediaFileManager.class)),
                        mock(org.apache.roller.weblogger.business.plugins.PluginManager.class)));
        return weblogger;
    }

    private EntriesWriteApi controllerFor(Weblogger weblogger) {
        EntriesWriteApi controller = new EntriesWriteApi();
        controller.weblogger = weblogger;
        return controller;
    }

    private static Weblog aWeblog(String handle) {
        Weblog weblog = new Weblog();
        weblog.setId(handle + "-id");
        weblog.setHandle(handle);
        weblog.setTimeZone("UTC");
        return weblog;
    }

    private static User aUser(String userName) {
        User user = new User();
        user.setUserName(userName);
        return user;
    }

    /**
     * The happy path end to end: title escaped exactly once, creator taken
     * from the authenticated user (never from the request body -- there is
     * no such field on EntryWrite), category resolved by name, 201 with a
     * Location header naming the new entry, and the saved entry echoed
     * back.
     */
    @Test
    void postCreatesAnEntryEscapesTheTitleAndReturnsLocation() throws Exception {
        Weblogger weblogger = mockedWeblogger();
        WeblogEntryManager entryManager = weblogger.getWeblogEntryManager();
        Weblog weblog = aWeblog("myblog");
        WeblogCategory travel = new WeblogCategory(weblog, "Travel", null, null);
        when(entryManager.getWeblogCategoryByName(weblog, "Travel")).thenReturn(travel);
        doAnswer(invocation -> {
            WeblogEntry saved = invocation.getArgument(0);
            saved.setId("entry-99");
            saved.setAnchor("cats-dogs");
            return null;
        }).when(entryManager).saveWeblogEntry(any());

        var result = mockMvc(controllerFor(weblogger))
                .perform(post("/v1/weblogs/myblog/entries")
                        .requestAttr("actionWeblog", weblog)
                        .requestAttr("authenticatedUser", aUser("maiia"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Cats & Dogs\",\"text\":\"body\",\"category\":\"Travel\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        String location = result.getResponse().getHeader("Location");
        assertNotNull(location);
        assertTrue(location.endsWith("/entries/entry-99"), "Location was: " + location);
        String body = result.getResponse().getContentAsString();

        ArgumentCaptor<WeblogEntry> captor = ArgumentCaptor.forClass(WeblogEntry.class);
        verify(weblogger.getWeblogEntryManager()).saveWeblogEntry(captor.capture());
        assertEquals("Cats &amp; Dogs", captor.getValue().getTitle());
        assertEquals("maiia", captor.getValue().getCreatorUserName());
        assertEquals(travel, captor.getValue().getCategory());
        assertSame(weblog, captor.getValue().getWebsite());
        // saveWeblogEntry only begins a JPA transaction; without flush() the
        // created entry would be rolled back by PersistenceSessionFilter's
        // end-of-request release despite the 201 (whole-branch review, Must
        // Fix 7 -- every write call site in this API was missing this
        // assertion, not just the one where the bug shipped, TokensApi).
        verify(weblogger).flush();

        tools.jackson.databind.JsonNode json = new tools.jackson.databind.ObjectMapper().readTree(body);
        assertEquals("Cats &amp; Dogs", json.get("title").asString());
    }

    /**
     * A post about one place, as a publishing agent sends it: the location
     * reaches the saved entry and comes back in the view under the same
     * names, so an agent can read an entry and write it back unchanged.
     */
    @Test
    void postWithALocationSavesItAndEchoesIt() throws Exception {
        Weblogger weblogger = mockedWeblogger();
        Weblog weblog = aWeblog("myblog");

        String body = mockMvc(controllerFor(weblogger))
                .perform(post("/v1/weblogs/myblog/entries")
                        .requestAttr("actionWeblog", weblog)
                        .requestAttr("authenticatedUser", aUser("maiia"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Torrey Pines\",\"text\":\"body\","
                                + "\"jsonLdType\":\"TOURIST_ATTRACTION\","
                                + "\"geoLatitude\":32.9228,\"geoLongitude\":-117.2534}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        ArgumentCaptor<WeblogEntry> captor = ArgumentCaptor.forClass(WeblogEntry.class);
        verify(weblogger.getWeblogEntryManager()).saveWeblogEntry(captor.capture());
        assertEquals(org.apache.roller.weblogger.pojos.JsonLdType.TOURIST_ATTRACTION,
                captor.getValue().getJsonLdType());
        assertEquals(32.9228, captor.getValue().getGeoLatitude());
        assertEquals(-117.2534, captor.getValue().getGeoLongitude());

        tools.jackson.databind.JsonNode json = new tools.jackson.databind.ObjectMapper().readTree(body);
        assertEquals("TOURIST_ATTRACTION", json.get("jsonLdType").asString());
        assertEquals(32.9228, json.get("geoLatitude").asDouble());
        assertEquals(-117.2534, json.get("geoLongitude").asDouble());
    }

    @Test
    void postWithALatitudeOffTheGlobeIsBadRequestAndSavesNothing() throws Exception {
        Weblogger weblogger = mockedWeblogger();
        Weblog weblog = aWeblog("myblog");

        mockMvc(controllerFor(weblogger))
                .perform(post("/v1/weblogs/myblog/entries")
                        .requestAttr("actionWeblog", weblog)
                        .requestAttr("authenticatedUser", aUser("maiia"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"t\",\"text\":\"body\",\"geoLatitude\":132.9}"))
                .andExpect(status().isBadRequest());

        verify(weblogger.getWeblogEntryManager(), never()).saveWeblogEntry(any());
    }

    /** No manager fallback needed: an absent category leaves entry.category null, and
     * JPAWeblogEntryManagerImpl.saveWeblogEntry (not exercised by this mocked test) is
     * what defaults it to the weblog's first category in production. */
    @Test
    void postWithNoCategoryLeavesEntryCategoryUnset() throws Exception {
        Weblogger weblogger = mockedWeblogger();
        Weblog weblog = aWeblog("myblog");

        mockMvc(controllerFor(weblogger))
                .perform(post("/v1/weblogs/myblog/entries")
                        .requestAttr("actionWeblog", weblog)
                        .requestAttr("authenticatedUser", aUser("maiia"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"No category here\",\"text\":\"body\"}"))
                .andExpect(status().isCreated());

        ArgumentCaptor<WeblogEntry> captor = ArgumentCaptor.forClass(WeblogEntry.class);
        verify(weblogger.getWeblogEntryManager()).saveWeblogEntry(captor.capture());
        assertNull(captor.getValue().getCategory());
    }

    /** An unknown category name is a 400, never a 500 from a null category reaching save. */
    @Test
    void postWithAnUnknownCategoryIsBadRequest() throws Exception {
        Weblogger weblogger = mockedWeblogger();
        Weblog weblog = aWeblog("myblog");
        when(weblogger.getWeblogEntryManager().getWeblogCategoryByName(weblog, "Nope"))
                .thenReturn(null);

        mockMvc(controllerFor(weblogger))
                .perform(post("/v1/weblogs/myblog/entries")
                        .requestAttr("actionWeblog", weblog)
                        .requestAttr("authenticatedUser", aUser("maiia"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"x\",\"text\":\"y\",\"category\":\"Nope\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));

        verify(weblogger.getWeblogEntryManager(), never()).saveWeblogEntry(any());
        verify(weblogger, never()).flush();
    }

    /** A mistyped pubTime is a 400 end to end through the controller, not an opaque 500. */
    @Test
    void postWithAMistypedPubTimeIsBadRequest() throws Exception {
        Weblogger weblogger = mockedWeblogger();
        Weblog weblog = aWeblog("myblog");

        mockMvc(controllerFor(weblogger))
                .perform(post("/v1/weblogs/myblog/entries")
                        .requestAttr("actionWeblog", weblog)
                        .requestAttr("authenticatedUser", aUser("maiia"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"x\",\"text\":\"y\",\"pubTime\":\"yesterday-ish\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));

        verify(weblogger.getWeblogEntryManager(), never()).saveWeblogEntry(any());
    }

    /** No authenticatedUser attribute -- the interceptor never having run -- is 401, not an NPE. */
    @Test
    void postWithoutAnAuthenticatedUserIsUnauthorized() throws Exception {
        Weblogger weblogger = mockedWeblogger();
        Weblog weblog = aWeblog("myblog");

        mockMvc(controllerFor(weblogger))
                .perform(post("/v1/weblogs/myblog/entries")
                        .requestAttr("actionWeblog", weblog)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"x\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));

        verify(weblogger.getWeblogEntryManager(), never()).saveWeblogEntry(any());
    }

    /** Malformed JSON is 400, matching CategoriesApi/EntriesApi's existing coverage of the same gap. */
    @Test
    void postWithMalformedJsonIsBadRequestNotAnOpaque500() throws Exception {
        Weblogger weblogger = mockedWeblogger();
        Weblog weblog = aWeblog("myblog");

        mockMvc(controllerFor(weblogger))
                .perform(post("/v1/weblogs/myblog/entries")
                        .requestAttr("actionWeblog", weblog)
                        .requestAttr("authenticatedUser", aUser("maiia"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));

        verify(weblogger.getWeblogEntryManager(), never()).saveWeblogEntry(any());
    }

    /**
     * PATCH found through WeblogOwnership.entry: an absent field (title,
     * here) is left untouched end to end, not just at the DTO layer.
     */
    @Test
    void patchUpdatesOnlyTheFieldsThePatchBodyCarries() throws Exception {
        Weblogger weblogger = mockedWeblogger();
        Weblog weblog = aWeblog("myblog");
        WeblogEntry entry = new WeblogEntry();
        entry.setId("entry-1");
        entry.setWebsite(weblog);
        entry.setTitle("kept");
        entry.setText("old body");
        when(weblogger.getWeblogEntryManager().getWeblogEntry("entry-1")).thenReturn(entry);

        String body = mockMvc(controllerFor(weblogger))
                .perform(patch("/v1/weblogs/myblog/entries/{id}", "entry-1")
                        .requestAttr("actionWeblog", weblog)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"new body\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertEquals("kept", entry.getTitle());
        assertEquals("new body", entry.getText());
        verify(weblogger.getWeblogEntryManager()).saveWeblogEntry(entry);
        verify(weblogger).flush();
        tools.jackson.databind.JsonNode json = new tools.jackson.databind.ObjectMapper().readTree(body);
        assertEquals("kept", json.get("title").asString());
    }

    /** Criterion 27 (settings half): commentsClosed is writable, shown, and null leaves it alone. */
    @Test
    void patchSetsCommentsClosedAndNullLeavesItAlone() throws Exception {
        Weblogger weblogger = mockedWeblogger();
        Weblog weblog = aWeblog("myblog");
        WeblogEntry entry = new WeblogEntry();
        entry.setId("entry-1");
        entry.setWebsite(weblog);
        entry.setTitle("t");
        entry.setText("b");
        when(weblogger.getWeblogEntryManager().getWeblogEntry("entry-1")).thenReturn(entry);

        String body = mockMvc(controllerFor(weblogger))
                .perform(patch("/v1/weblogs/myblog/entries/{id}", "entry-1")
                        .requestAttr("actionWeblog", weblog)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commentsClosed\":true}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertEquals(Boolean.TRUE, entry.getCommentsClosed());
        assertTrue(new tools.jackson.databind.ObjectMapper().readTree(body).get("commentsClosed").asBoolean());

        mockMvc(controllerFor(weblogger))
                .perform(patch("/v1/weblogs/myblog/entries/{id}", "entry-1")
                        .requestAttr("actionWeblog", weblog)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"again\"}"))
                .andExpect(status().isOk());
        assertEquals(Boolean.TRUE, entry.getCommentsClosed(), "null leaves the flag unchanged");
    }

    /**
     * Criterion 11's last step, the import-then-featuredImageId flow: the id
     * POST /media/imports answers with (a MediaFile id, a 36-character UUID)
     * is accepted as an entry's featuredImageId, stored, and read back.
     * Characterisation of the shipped write path: expected to pass at once.
     * The id must now name an image of this weblog (spec 2026-10-13, section
     * 4), so the test stubs one.
     */
    @Test
    void patchAcceptsAnImportedMediaIdAsTheFeaturedImage() throws Exception {
        Weblogger weblogger = mockedWeblogger();
        Weblog weblog = aWeblog("myblog");
        WeblogEntry entry = new WeblogEntry();
        entry.setId("entry-1");
        entry.setWebsite(weblog);
        entry.setTitle("Harbour");
        when(weblogger.getWeblogEntryManager().getWeblogEntry("entry-1")).thenReturn(entry);
        String mediaId = new org.apache.roller.weblogger.pojos.MediaFile().getId();
        org.apache.roller.weblogger.business.MediaFileManager media =
                mock(org.apache.roller.weblogger.business.MediaFileManager.class);
        when(weblogger.getMediaFileManager()).thenReturn(media);
        org.apache.roller.weblogger.pojos.MediaFile image = new org.apache.roller.weblogger.pojos.MediaFile();
        image.setId(mediaId);
        image.setDirectory(new org.apache.roller.weblogger.pojos.MediaFileDirectory(weblog, "default", null));
        image.setContentType("image/jpeg");
        when(media.getMediaFile(mediaId)).thenReturn(image);

        String body = mockMvc(controllerFor(weblogger))
                .perform(patch("/v1/weblogs/myblog/entries/{id}", "entry-1")
                        .requestAttr("actionWeblog", weblog)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"featuredImageId\":\"" + mediaId + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertEquals(mediaId, entry.getFeaturedImageId());
        verify(weblogger.getWeblogEntryManager()).saveWeblogEntry(entry);
        verify(weblogger).flush();
        assertEquals(mediaId,
                new tools.jackson.databind.ObjectMapper().readTree(body).get("featuredImageId").asString());
    }

    /**
     * The IDOR case: an entry belonging to another weblog is
     * indistinguishable from an unknown id -- 404 either way, never the
     * foreign entry's data or a rewrite of it.
     */
    @Test
    void patchIsNotFoundWhenTheEntryBelongsToAnotherWeblog() throws Exception {
        Weblogger weblogger = mockedWeblogger();
        Weblog thisWeblog = aWeblog("myblog");
        Weblog anotherWeblog = aWeblog("someoneelse");
        WeblogEntry foreign = new WeblogEntry();
        foreign.setId("entry-1");
        foreign.setWebsite(anotherWeblog);
        when(weblogger.getWeblogEntryManager().getWeblogEntry("entry-1")).thenReturn(foreign);

        mockMvc(controllerFor(weblogger))
                .perform(patch("/v1/weblogs/myblog/entries/{id}", "entry-1")
                        .requestAttr("actionWeblog", thisWeblog)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"new body\"}"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));

        verify(weblogger.getWeblogEntryManager(), never()).saveWeblogEntry(any());
    }

    /**
     * roller_weblogentrytag.name is varchar(255) (V002__baseline_schema.sql)
     * -- checked per tag, AFTER splitting on the same characters {@code
     * WeblogEntry.setTagsAsString} splits on (see {@code applyTags}'s
     * javadoc), so one oversized tag in a batch is refused before any of
     * them reach it. This fixture has no split characters in it, so
     * splitting is a no-op and the whole 256-char string is refused as one
     * tag either way -- see the sibling test below for the case that only
     * the post-split check gets right.
     */
    @Test
    void patchWithATagLongerThanTheColumnIsBadRequest() throws Exception {
        Weblogger weblogger = mockedWeblogger();
        Weblog weblog = aWeblog("myblog");
        WeblogEntry entry = new WeblogEntry();
        entry.setId("entry-1");
        entry.setWebsite(weblog);
        when(weblogger.getWeblogEntryManager().getWeblogEntry("entry-1")).thenReturn(entry);
        String tooLong = "a".repeat(256);

        mockMvc(controllerFor(weblogger))
                .perform(patch("/v1/weblogs/myblog/entries/{id}", "entry-1")
                        .requestAttr("actionWeblog", weblog)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tags\":[\"travel\",\"" + tooLong + "\"]}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));

        verify(weblogger.getWeblogEntryManager(), never()).saveWeblogEntry(any());
        verify(weblogger, never()).flush();
    }

    /**
     * Parked-item fix: a single list element may itself carry several
     * space/comma-separated tags ({@code applyTags} joins the whole list
     * with a space before {@code setTagsAsString} splits it again on
     * {@code Utilities.TAG_SPLIT_CHARS}), and their COMBINED length can
     * exceed the column even though every tag actually stored -- one row
     * per split token -- is short. Checking length before the split
     * over-rejects this case; checking after it (what this test pins)
     * measures what {@code setTagsAsString} really stores.
     */
    @Test
    void patchWithASingleTagsElementCarryingManyShortTagsWhoseCombinedLengthExceedsTheColumnIsAccepted()
            throws Exception {
        Weblogger weblogger = mockedWeblogger();
        Weblog weblog = aWeblog("myblog");
        WeblogEntry entry = new WeblogEntry();
        entry.setId("entry-1");
        entry.setWebsite(weblog);
        when(weblogger.getWeblogEntryManager().getWeblogEntry("entry-1")).thenReturn(entry);
        String manyShortTags = String.join(",", java.util.Collections.nCopies(40, "abcdefghij"));
        assertTrue(manyShortTags.length() > 255,
                "fixture must overflow the column taken as one string, though no split tag does");

        mockMvc(controllerFor(weblogger))
                .perform(patch("/v1/weblogs/myblog/entries/{id}", "entry-1")
                        .requestAttr("actionWeblog", weblog)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tags\":[\"" + manyShortTags + "\"]}"))
                .andExpect(status().isOk());

        verify(weblogger.getWeblogEntryManager()).saveWeblogEntry(entry);
    }

    /** Tags go through WeblogEntry's own tag-setting path, space-joined. */
    @Test
    void patchWithTagsAppliesThemThroughSetTagsAsString() throws Exception {
        Weblogger weblogger = mockedWeblogger();
        Weblog weblog = aWeblog("myblog");
        WeblogEntry entry = new WeblogEntry();
        entry.setId("entry-1");
        entry.setWebsite(weblog);
        when(weblogger.getWeblogEntryManager().getWeblogEntry("entry-1")).thenReturn(entry);

        mockMvc(controllerFor(weblogger))
                .perform(patch("/v1/weblogs/myblog/entries/{id}", "entry-1")
                        .requestAttr("actionWeblog", weblog)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tags\":[\"travel\",\"food\"]}"))
                .andExpect(status().isOk());

        assertEquals("food travel", entry.getTagsAsString());
    }

    /**
     * CRITICAL fix round 1: publishing with no pubTime is the single most
     * likely automation call ("publish this now") and must not 500.
     * {@code applyWrite} only sets pubTime when the client supplied one, and
     * {@code JPAWeblogEntryManagerImpl.saveWeblogEntry} evaluates {@code
     * entry.getPubTime().after(...)} unconditionally whenever status is
     * PUBLISHED -- an NPE the JSP editor's {@code EntryEditController.doSave}
     * already guards against immediately before every save. This controller
     * ported that same guard.
     */
    @Test
    void postWithPublishedStatusAndNoPubTimeDefaultsToNowRatherThanFailing() throws Exception {
        Weblogger weblogger = mockedWeblogger();
        Weblog weblog = aWeblog("myblog");

        String body = mockMvc(controllerFor(weblogger))
                .perform(post("/v1/weblogs/myblog/entries")
                        .requestAttr("actionWeblog", weblog)
                        .requestAttr("authenticatedUser", aUser("maiia"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"x\",\"text\":\"y\",\"status\":\"PUBLISHED\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        ArgumentCaptor<WeblogEntry> captor = ArgumentCaptor.forClass(WeblogEntry.class);
        verify(weblogger.getWeblogEntryManager()).saveWeblogEntry(captor.capture());
        assertNotNull(captor.getValue().getPubTime());

        tools.jackson.databind.JsonNode json = new tools.jackson.databind.ObjectMapper().readTree(body);
        assertNotNull(json.get("pubTime"));
    }

    /** Same guard, PATCH side: publishing a draft with no stored pubTime must not 500 either. */
    @Test
    void patchWithPublishedStatusAndNoPubTimeDefaultsToNowRatherThanFailing() throws Exception {
        Weblogger weblogger = mockedWeblogger();
        Weblog weblog = aWeblog("myblog");
        WeblogEntry entry = new WeblogEntry();
        entry.setId("entry-1");
        entry.setWebsite(weblog);
        entry.setTitle("existing");
        entry.setText("existing body");
        when(weblogger.getWeblogEntryManager().getWeblogEntry("entry-1")).thenReturn(entry);

        String body = mockMvc(controllerFor(weblogger))
                .perform(patch("/v1/weblogs/myblog/entries/{id}", "entry-1")
                        .requestAttr("actionWeblog", weblog)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"PUBLISHED\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertNotNull(entry.getPubTime());
        verify(weblogger.getWeblogEntryManager()).saveWeblogEntry(entry);
        tools.jackson.databind.JsonNode json = new tools.jackson.databind.ObjectMapper().readTree(body);
        assertNotNull(json.get("pubTime"));
    }

    /**
     * A post the API publishes reaches site search the way an editor-published
     * one does (EntryEditController queues it after the flush). The first
     * agent-published post on blog.wikantik.com was live but matched no
     * search, because create and update saved without touching the index.
     */
    @Test
    void postWithPublishedStatusQueuesTheEntryForTheSearchIndex() throws Exception {
        Weblogger weblogger = mockedWeblogger();
        Weblog weblog = aWeblog("myblog");

        mockMvc(controllerFor(weblogger))
                .perform(post("/v1/weblogs/myblog/entries")
                        .requestAttr("actionWeblog", weblog)
                        .requestAttr("authenticatedUser", aUser("maiia"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"x\",\"text\":\"y\",\"status\":\"PUBLISHED\"}"))
                .andExpect(status().isCreated());

        ArgumentCaptor<WeblogEntry> captor = ArgumentCaptor.forClass(WeblogEntry.class);
        verify(weblogger.getWeblogEntryManager()).saveWeblogEntry(captor.capture());
        verify(weblogger.getIndexManager()).addEntryReIndexOperation(captor.getValue());
    }

    /** A draft is not searchable, so creating one leaves the index alone. */
    @Test
    void postOfADraftLeavesTheSearchIndexAlone() throws Exception {
        Weblogger weblogger = mockedWeblogger();
        Weblog weblog = aWeblog("myblog");

        mockMvc(controllerFor(weblogger))
                .perform(post("/v1/weblogs/myblog/entries")
                        .requestAttr("actionWeblog", weblog)
                        .requestAttr("authenticatedUser", aUser("maiia"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"x\",\"text\":\"y\",\"status\":\"DRAFT\"}"))
                .andExpect(status().isCreated());

        verify(weblogger.getIndexManager(), never()).addEntryReIndexOperation(any());
    }

    /** Publishing (or editing a published entry) through PATCH reindexes it. */
    @Test
    void patchLeavingTheEntryPublishedQueuesItForTheSearchIndex() throws Exception {
        Weblogger weblogger = mockedWeblogger();
        Weblog weblog = aWeblog("myblog");
        WeblogEntry entry = new WeblogEntry();
        entry.setId("entry-1");
        entry.setWebsite(weblog);
        entry.setTitle("existing");
        entry.setText("existing body");
        when(weblogger.getWeblogEntryManager().getWeblogEntry("entry-1")).thenReturn(entry);

        mockMvc(controllerFor(weblogger))
                .perform(patch("/v1/weblogs/myblog/entries/{id}", "entry-1")
                        .requestAttr("actionWeblog", weblog)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"PUBLISHED\"}"))
                .andExpect(status().isOk());

        verify(weblogger.getIndexManager()).addEntryReIndexOperation(entry);
    }

    /**
     * Taking a published entry back to draft drops it from the index, as the
     * editor's save does: otherwise search keeps offering a permalink that 404s.
     */
    @Test
    void patchUnpublishingAnEntryRemovesItFromTheSearchIndex() throws Exception {
        Weblogger weblogger = mockedWeblogger();
        Weblog weblog = aWeblog("myblog");
        WeblogEntry entry = new WeblogEntry();
        entry.setId("entry-1");
        entry.setWebsite(weblog);
        entry.setTitle("existing");
        entry.setText("existing body");
        entry.setStatus(WeblogEntry.PubStatus.PUBLISHED);
        entry.setPubTime(new java.sql.Timestamp(System.currentTimeMillis()));
        when(weblogger.getWeblogEntryManager().getWeblogEntry("entry-1")).thenReturn(entry);

        mockMvc(controllerFor(weblogger))
                .perform(patch("/v1/weblogs/myblog/entries/{id}", "entry-1")
                        .requestAttr("actionWeblog", weblog)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"DRAFT\"}"))
                .andExpect(status().isOk());

        verify(weblogger.getIndexManager()).removeEntryIndexOperation(entry);
        verify(weblogger.getIndexManager(), never()).addEntryReIndexOperation(any());
    }

    /**
     * Whole-branch review, Must Fix 3: SCHEDULED with no pubTime creates an
     * entry that can never publish. {@code applyPublishNowDefault} only
     * defaults a pubTime for PUBLISHED; ScheduledEntriesTask promotes a
     * SCHEDULED entry only once {@code pubtime <= now}, and NULL never
     * satisfies that, so the entry would be stuck SCHEDULED forever and
     * invisible to every listing that does not explicitly ask for that
     * status. Must be a 400, not a silently-broken create.
     */
    @Test
    void postWithScheduledStatusAndNoPubTimeIsBadRequest() throws Exception {
        Weblogger weblogger = mockedWeblogger();
        Weblog weblog = aWeblog("myblog");

        mockMvc(controllerFor(weblogger))
                .perform(post("/v1/weblogs/myblog/entries")
                        .requestAttr("actionWeblog", weblog)
                        .requestAttr("authenticatedUser", aUser("maiia"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"x\",\"text\":\"y\",\"status\":\"SCHEDULED\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(errorFieldIs("pubTime"));

        verify(weblogger.getWeblogEntryManager(), never()).saveWeblogEntry(any());
    }

    /** Same guard, PATCH side. */
    @Test
    void patchWithScheduledStatusAndNoPubTimeIsBadRequest() throws Exception {
        Weblogger weblogger = mockedWeblogger();
        Weblog weblog = aWeblog("myblog");
        WeblogEntry entry = new WeblogEntry();
        entry.setId("entry-1");
        entry.setWebsite(weblog);
        entry.setTitle("existing");
        entry.setText("existing body");
        when(weblogger.getWeblogEntryManager().getWeblogEntry("entry-1")).thenReturn(entry);

        mockMvc(controllerFor(weblogger))
                .perform(patch("/v1/weblogs/myblog/entries/{id}", "entry-1")
                        .requestAttr("actionWeblog", weblog)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"SCHEDULED\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(errorFieldIs("pubTime"));

        verify(weblogger.getWeblogEntryManager(), never()).saveWeblogEntry(any());
    }

    /** SCHEDULED with an explicit pubTime must succeed -- the guard rejects only the NULL case. */
    @Test
    void postWithScheduledStatusAndAnExplicitPubTimeSucceeds() throws Exception {
        Weblogger weblogger = mockedWeblogger();
        Weblog weblog = aWeblog("myblog");

        mockMvc(controllerFor(weblogger))
                .perform(post("/v1/weblogs/myblog/entries")
                        .requestAttr("actionWeblog", weblog)
                        .requestAttr("authenticatedUser", aUser("maiia"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"x\",\"text\":\"y\",\"status\":\"SCHEDULED\","
                                + "\"pubTime\":\"2099-01-01T09:30\"}"))
                .andExpect(status().isCreated());

        verify(weblogger.getWeblogEntryManager()).saveWeblogEntry(any());
    }

    /**
     * A PATCH that leaves an already-SCHEDULED entry's stored pubTime
     * untouched must not be refused -- the guard reads the entry's final
     * pubTime after applyWrite, not just whatever this one request supplied.
     */
    @Test
    void patchOnAnAlreadyScheduledEntryThatDoesNotTouchPubTimeIsNotRejected() throws Exception {
        Weblogger weblogger = mockedWeblogger();
        Weblog weblog = aWeblog("myblog");
        WeblogEntry entry = new WeblogEntry();
        entry.setId("entry-1");
        entry.setWebsite(weblog);
        entry.setTitle("existing");
        entry.setText("existing body");
        entry.setStatus(WeblogEntry.PubStatus.SCHEDULED);
        entry.setPubTime(new java.sql.Timestamp(System.currentTimeMillis() + 86_400_000L));
        when(weblogger.getWeblogEntryManager().getWeblogEntry("entry-1")).thenReturn(entry);

        mockMvc(controllerFor(weblogger))
                .perform(patch("/v1/weblogs/myblog/entries/{id}", "entry-1")
                        .requestAttr("actionWeblog", weblog)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"summary\":\"just a summary update\"}"))
                .andExpect(status().isOk());

        verify(weblogger.getWeblogEntryManager()).saveWeblogEntry(entry);
    }

    /**
     * IMPORTANT fix round 1: title and text are NOT NULL columns
     * (V002__baseline_schema.sql) and WeblogEntry defaults both to null, so
     * an omitted title used to reach weblogger.flush() and die on the
     * constraint -- an opaque 500 from the generic handler. Validated the
     * same way CategoriesApi.create validates {@code name}: before the
     * manager is ever called.
     */
    @Test
    void postWithoutATitleIsBadRequest() throws Exception {
        Weblogger weblogger = mockedWeblogger();
        Weblog weblog = aWeblog("myblog");

        mockMvc(controllerFor(weblogger))
                .perform(post("/v1/weblogs/myblog/entries")
                        .requestAttr("actionWeblog", weblog)
                        .requestAttr("authenticatedUser", aUser("maiia"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"y\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));

        verify(weblogger.getWeblogEntryManager(), never()).saveWeblogEntry(any());
    }

    /** Same guard, the other NOT NULL column. */
    @Test
    void postWithoutTextIsBadRequest() throws Exception {
        Weblogger weblogger = mockedWeblogger();
        Weblog weblog = aWeblog("myblog");

        mockMvc(controllerFor(weblogger))
                .perform(post("/v1/weblogs/myblog/entries")
                        .requestAttr("actionWeblog", weblog)
                        .requestAttr("authenticatedUser", aUser("maiia"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"x\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));

        verify(weblogger.getWeblogEntryManager(), never()).saveWeblogEntry(any());
    }

    /**
     * The validation added to create() must NOT reach update(): an existing
     * entry already satisfies the NOT NULL constraint, and applyWrite's
     * "null means absent" contract must keep leaving prior values alone
     * rather than suddenly demanding a title on every PATCH.
     */
    @Test
    void patchWithoutATitleLeavesTheStoredTitleUntouchedAndIsNotRejected() throws Exception {
        Weblogger weblogger = mockedWeblogger();
        Weblog weblog = aWeblog("myblog");
        WeblogEntry entry = new WeblogEntry();
        entry.setId("entry-1");
        entry.setWebsite(weblog);
        entry.setTitle("kept title");
        entry.setText("kept body");
        when(weblogger.getWeblogEntryManager().getWeblogEntry("entry-1")).thenReturn(entry);

        mockMvc(controllerFor(weblogger))
                .perform(patch("/v1/weblogs/myblog/entries/{id}", "entry-1")
                        .requestAttr("actionWeblog", weblog)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"summary\":\"just a summary update\"}"))
                .andExpect(status().isOk());

        assertEquals("kept title", entry.getTitle());
        assertEquals("kept body", entry.getText());
        verify(weblogger.getWeblogEntryManager()).saveWeblogEntry(entry);
    }

    @Test
    void declaresPostAsTheRequiredWeblogPermission() {
        assertEquals(List.of(WeblogPermission.POST),
                new EntriesWriteApi().requiredWeblogPermissionActions());
        assertTrue(new EntriesWriteApi().isUserRequired());
        assertTrue(new EntriesWriteApi().isWeblogRequired());
    }

    // ---- trash / restore / delete-forever / preview (Task 10) ----------

    /**
     * The happy path: DELETE removes the entry from the search index before
     * trashing it -- the ordering {@code EntryDeletion.trashEntryWithIndex}
     * exists to guarantee -- and never calls the permanent-delete manager
     * method.
     */
    @Test
    void deleteTrashesALiveEntryAndRemovesItFromTheIndexFirst() throws Exception {
        Weblogger weblogger = mockedWeblogger();
        Weblog weblog = aWeblog("myblog");
        WeblogEntry entry = new WeblogEntry();
        entry.setId("entry-1");
        entry.setWebsite(weblog);
        entry.setStatus(WeblogEntry.PubStatus.PUBLISHED);
        when(weblogger.getWeblogEntryManager().getWeblogEntry("entry-1")).thenReturn(entry);

        mockMvc(controllerFor(weblogger))
                .perform(delete("/v1/weblogs/myblog/entries/{id}", "entry-1")
                        .requestAttr("actionWeblog", weblog))
                .andExpect(status().isNoContent());

        verify(weblogger.getIndexManager()).removeEntryIndexOperation(entry);
        verify(weblogger.getWeblogEntryManager()).trashWeblogEntry(entry);
        verify(weblogger.getWeblogEntryManager(), never()).removeWeblogEntry(any());
        verify(weblogger).flush();
    }

    /** IDOR case: a foreign entry's id is 404, same as PATCH. */
    @Test
    void deleteIsNotFoundWhenTheEntryBelongsToAnotherWeblog() throws Exception {
        Weblogger weblogger = mockedWeblogger();
        Weblog thisWeblog = aWeblog("myblog");
        Weblog anotherWeblog = aWeblog("someoneelse");
        WeblogEntry foreign = new WeblogEntry();
        foreign.setId("entry-1");
        foreign.setWebsite(anotherWeblog);
        when(weblogger.getWeblogEntryManager().getWeblogEntry("entry-1")).thenReturn(foreign);

        mockMvc(controllerFor(weblogger))
                .perform(delete("/v1/weblogs/myblog/entries/{id}", "entry-1")
                        .requestAttr("actionWeblog", thisWeblog))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));

        verify(weblogger.getWeblogEntryManager(), never()).trashWeblogEntry(any());
    }

    /**
     * Trashing an already-trashed entry is a 409, not a silent no-op re-save
     * -- a caller asking to trash something already trashed has a stale view
     * of the entry.
     */
    @Test
    void deleteOnAnAlreadyTrashedEntryIsConflict() throws Exception {
        Weblogger weblogger = mockedWeblogger();
        Weblog weblog = aWeblog("myblog");
        WeblogEntry entry = new WeblogEntry();
        entry.setId("entry-1");
        entry.setWebsite(weblog);
        entry.setStatus(WeblogEntry.PubStatus.TRASHED);
        when(weblogger.getWeblogEntryManager().getWeblogEntry("entry-1")).thenReturn(entry);

        mockMvc(controllerFor(weblogger))
                .perform(delete("/v1/weblogs/myblog/entries/{id}", "entry-1")
                        .requestAttr("actionWeblog", weblog))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));

        verify(weblogger.getWeblogEntryManager(), never()).trashWeblogEntry(any());
        verify(weblogger.getIndexManager(), never()).removeEntryIndexOperation(any());
        verify(weblogger, never()).flush();
    }

    /**
     * Restore lands on DRAFT -- proved here by asserting the entry's
     * in-memory status after the call, since {@code restoreWeblogEntry}
     * itself is mocked and would otherwise leave TRASHED in place with no
     * failure at all.
     */
    @Test
    void restoreCallsManagerRestoreAndReturnsTheEntry() throws Exception {
        Weblogger weblogger = mockedWeblogger();
        WeblogEntryManager entryManager = weblogger.getWeblogEntryManager();
        Weblog weblog = aWeblog("myblog");
        WeblogEntry entry = new WeblogEntry();
        entry.setId("entry-1");
        entry.setWebsite(weblog);
        entry.setTitle("was trashed");
        entry.setStatus(WeblogEntry.PubStatus.TRASHED);
        when(entryManager.getWeblogEntry("entry-1")).thenReturn(entry);
        doAnswer(invocation -> {
            WeblogEntry e = invocation.getArgument(0);
            e.setStatus(WeblogEntry.PubStatus.DRAFT);
            return null;
        }).when(entryManager).restoreWeblogEntry(entry);

        String body = mockMvc(controllerFor(weblogger))
                .perform(post("/v1/weblogs/myblog/entries/{id}/restore", "entry-1")
                        .requestAttr("actionWeblog", weblog))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        verify(weblogger.getWeblogEntryManager()).restoreWeblogEntry(entry);
        assertEquals(WeblogEntry.PubStatus.DRAFT, entry.getStatus());
        verify(weblogger).flush();
        tools.jackson.databind.JsonNode json = new tools.jackson.databind.ObjectMapper().readTree(body);
        assertEquals("DRAFT", json.get("status").asString());
    }

    /**
     * The side-door-resurrection guard: restoring a live PUBLISHED entry
     * must never reach {@code restoreWeblogEntry}, which has no status
     * check of its own and would silently demote it to DRAFT.
     */
    @Test
    void restoringAnEntryThatIsNotTrashedIsConflict() throws Exception {
        Weblogger weblogger = mockedWeblogger();
        Weblog weblog = aWeblog("myblog");
        WeblogEntry entry = new WeblogEntry();
        entry.setId("entry-1");
        entry.setWebsite(weblog);
        entry.setStatus(WeblogEntry.PubStatus.PUBLISHED);
        when(weblogger.getWeblogEntryManager().getWeblogEntry("entry-1")).thenReturn(entry);

        mockMvc(controllerFor(weblogger))
                .perform(post("/v1/weblogs/myblog/entries/{id}/restore", "entry-1")
                        .requestAttr("actionWeblog", weblog))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));

        verify(weblogger.getWeblogEntryManager(), never()).restoreWeblogEntry(any());
        assertEquals(WeblogEntry.PubStatus.PUBLISHED, entry.getStatus());
        verify(weblogger, never()).flush();
    }

    /** Delete-forever on an already-trashed entry: index removal, then the real delete. */
    @Test
    void deleteForeverOnATrashedEntryRemovesItFromTheIndexAndCallsRemoveWeblogEntry() throws Exception {
        Weblogger weblogger = mockedWeblogger();
        Weblog weblog = aWeblog("myblog");
        WeblogEntry entry = new WeblogEntry();
        entry.setId("entry-1");
        entry.setWebsite(weblog);
        entry.setStatus(WeblogEntry.PubStatus.TRASHED);
        when(weblogger.getWeblogEntryManager().getWeblogEntry("entry-1")).thenReturn(entry);

        mockMvc(controllerFor(weblogger))
                .perform(post("/v1/weblogs/myblog/entries/{id}/delete-forever", "entry-1")
                        .requestAttr("actionWeblog", weblog))
                .andExpect(status().isNoContent());

        verify(weblogger.getIndexManager()).removeEntryIndexOperation(entry);
        verify(weblogger.getWeblogEntryManager()).removeWeblogEntry(entry);
        verify(weblogger.getWeblogEntryManager(), never()).trashWeblogEntry(any());
        verify(weblogger).flush();
    }

    /**
     * Delete-forever is not a shortcut around trashing: a live entry's id is
     * a 409, not an undocumented hard-delete endpoint.
     */
    @Test
    void deleteForeverOnALiveEntryIsConflictAndNeverCallsRemoveWeblogEntry() throws Exception {
        Weblogger weblogger = mockedWeblogger();
        Weblog weblog = aWeblog("myblog");
        WeblogEntry entry = new WeblogEntry();
        entry.setId("entry-1");
        entry.setWebsite(weblog);
        entry.setStatus(WeblogEntry.PubStatus.DRAFT);
        when(weblogger.getWeblogEntryManager().getWeblogEntry("entry-1")).thenReturn(entry);

        mockMvc(controllerFor(weblogger))
                .perform(post("/v1/weblogs/myblog/entries/{id}/delete-forever", "entry-1")
                        .requestAttr("actionWeblog", weblog))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));

        verify(weblogger.getWeblogEntryManager(), never()).removeWeblogEntry(any());
        verify(weblogger.getIndexManager(), never()).removeEntryIndexOperation(any());
        verify(weblogger, never()).flush();
    }

    /** An unknown id for restore is 404, matching every other by-id endpoint. */
    @Test
    void restoreIsNotFoundForAnUnknownId() throws Exception {
        Weblogger weblogger = mockedWeblogger();
        Weblog weblog = aWeblog("myblog");
        when(weblogger.getWeblogEntryManager().getWeblogEntry("nope")).thenReturn(null);

        mockMvc(controllerFor(weblogger))
                .perform(post("/v1/weblogs/myblog/entries/{id}/restore", "nope")
                        .requestAttr("actionWeblog", weblog))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));
    }

    /**
     * Preview against an existing entry runs the real shortcode/Markdown/
     * sanitizer pipeline (no plugins registered in this standalone test, so
     * that half of the pipeline is a no-op) and never persists anything.
     */
    @Test
    void previewExistingEntryRendersSubmittedTextAndNeverSaves() throws Exception {
        Weblogger weblogger = mockedWeblogger();
        Weblog weblog = aWeblog("myblog");
        WeblogEntry entry = new WeblogEntry();
        entry.setId("entry-1");
        entry.setWebsite(weblog);
        entry.setText("old text, never rendered here");
        when(weblogger.getWeblogEntryManager().getWeblogEntry("entry-1")).thenReturn(entry);

        String body = mockMvc(controllerFor(weblogger))
                .perform(post("/v1/weblogs/myblog/entries/{id}/preview", "entry-1")
                        .requestAttr("actionWeblog", weblog)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"**bold draft**\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        tools.jackson.databind.JsonNode json = new tools.jackson.databind.ObjectMapper().readTree(body);
        assertTrue(json.get("html").asString().contains("<strong>bold draft</strong>"),
                "was: " + json.get("html").asString());
        verify(weblogger.getWeblogEntryManager(), never()).saveWeblogEntry(any());
        // renderPreview's own javadoc names flush() as the hazard: nothing on
        // this path may ever call it, or a preview would silently persist
        // over the stored entry (whole-branch review, Must Fix 7).
        verify(weblogger, never()).flush();
    }

    /**
     * Preview for a brand-new entry (no id yet) previews against a scratch
     * entry owned by the action weblog rather than 404ing or 500ing for
     * lack of one.
     */
    @Test
    void previewNewEntryRendersAgainstAScratchEntryOwnedByTheActionWeblog() throws Exception {
        Weblogger weblogger = mockedWeblogger();
        Weblog weblog = aWeblog("myblog");

        String body = mockMvc(controllerFor(weblogger))
                .perform(post("/v1/weblogs/myblog/entries/preview")
                        .requestAttr("actionWeblog", weblog)
                        .requestAttr("authenticatedUser", aUser("maiia"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"hello *world*\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        tools.jackson.databind.JsonNode json = new tools.jackson.databind.ObjectMapper().readTree(body);
        assertTrue(json.get("html").asString().contains("<em>world</em>"),
                "was: " + json.get("html").asString());
    }

    /**
     * Guard from the wave's recurring "ordinary client input must not 500"
     * lesson: an empty request body renders an empty draft, not a 500 or an
     * NPE from a null text field.
     */
    @Test
    void previewWithAnEmptyBodyRendersAnEmptyDraftRatherThanFailing() throws Exception {
        Weblogger weblogger = mockedWeblogger();
        Weblog weblog = aWeblog("myblog");

        String body = mockMvc(controllerFor(weblogger))
                .perform(post("/v1/weblogs/myblog/entries/preview")
                        .requestAttr("actionWeblog", weblog)
                        .requestAttr("authenticatedUser", aUser("maiia"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        tools.jackson.databind.JsonNode json = new tools.jackson.databind.ObjectMapper().readTree(body);
        assertNotNull(json.get("html"));
    }

    /** Malformed JSON on preview is 400, not the generic 500 handler. */
    @Test
    void previewWithMalformedJsonIsBadRequestNotAnOpaque500() throws Exception {
        Weblogger weblogger = mockedWeblogger();
        Weblog weblog = aWeblog("myblog");

        mockMvc(controllerFor(weblogger))
                .perform(post("/v1/weblogs/myblog/entries/preview")
                        .requestAttr("actionWeblog", weblog)
                        .requestAttr("authenticatedUser", aUser("maiia"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));
    }

    // ---- fix round 1: PATCH/preview must not resurrect a trashed entry,
    // and delete-forever/preview need their own IDOR coverage -----------

    /**
     * IMPORTANT 1: {@code requireEntry} carries no status filter, so PATCH
     * on a TRASHED entry used to reach {@code saveWeblogEntry} directly --
     * a side door around {@code restore} that could publish a trashed entry
     * in one call, with {@code trashedAt} still populated on the row. This
     * is exactly the hazard {@code BaseController.lookupNonTrashedEntry}'s
     * javadoc names for the JSP side; the API needs the same closed door.
     */
    @Test
    void patchOnATrashedEntryIsRefusedRatherThanResurrectingIt() throws Exception {
        Weblogger weblogger = mockedWeblogger();
        Weblog weblog = aWeblog("myblog");
        WeblogEntry entry = new WeblogEntry();
        entry.setId("entry-1");
        entry.setWebsite(weblog);
        entry.setStatus(WeblogEntry.PubStatus.TRASHED);
        when(weblogger.getWeblogEntryManager().getWeblogEntry("entry-1")).thenReturn(entry);

        mockMvc(controllerFor(weblogger))
                .perform(patch("/v1/weblogs/myblog/entries/{id}", "entry-1")
                        .requestAttr("actionWeblog", weblog)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"PUBLISHED\"}"))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));

        verify(weblogger.getWeblogEntryManager(), never()).saveWeblogEntry(any());
        assertEquals(WeblogEntry.PubStatus.TRASHED, entry.getStatus());
    }

    /**
     * Same door, the preview side: previewing a trashed entry's unsaved text
     * is refused rather than silently rendering as though the entry were
     * live -- previewExisting uses the same live-entry guard as PATCH.
     */
    @Test
    void previewExistingOnATrashedEntryIsConflict() throws Exception {
        Weblogger weblogger = mockedWeblogger();
        Weblog weblog = aWeblog("myblog");
        WeblogEntry entry = new WeblogEntry();
        entry.setId("entry-1");
        entry.setWebsite(weblog);
        entry.setStatus(WeblogEntry.PubStatus.TRASHED);
        when(weblogger.getWeblogEntryManager().getWeblogEntry("entry-1")).thenReturn(entry);

        mockMvc(controllerFor(weblogger))
                .perform(post("/v1/weblogs/myblog/entries/{id}/preview", "entry-1")
                        .requestAttr("actionWeblog", weblog)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"x\"}"))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));
    }

    /**
     * IMPORTANT 2: delete-forever permanently destroys data and had no
     * ownership test at all -- its correctness rested on inspection of
     * {@code requireEntry} alone. A foreign entry's id must be 404, exactly
     * like every other by-id endpoint.
     */
    @Test
    void deleteForeverIsNotFoundWhenTheEntryBelongsToAnotherWeblog() throws Exception {
        Weblogger weblogger = mockedWeblogger();
        Weblog thisWeblog = aWeblog("myblog");
        Weblog anotherWeblog = aWeblog("someoneelse");
        WeblogEntry foreign = new WeblogEntry();
        foreign.setId("entry-1");
        foreign.setWebsite(anotherWeblog);
        foreign.setStatus(WeblogEntry.PubStatus.TRASHED);
        when(weblogger.getWeblogEntryManager().getWeblogEntry("entry-1")).thenReturn(foreign);

        mockMvc(controllerFor(weblogger))
                .perform(post("/v1/weblogs/myblog/entries/{id}/delete-forever", "entry-1")
                        .requestAttr("actionWeblog", thisWeblog))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));

        verify(weblogger.getWeblogEntryManager(), never()).removeWeblogEntry(any());
        verify(weblogger.getIndexManager(), never()).removeEntryIndexOperation(any());
    }

    /** Same IDOR coverage for preview, which had none either. */
    @Test
    void previewExistingIsNotFoundWhenTheEntryBelongsToAnotherWeblog() throws Exception {
        Weblogger weblogger = mockedWeblogger();
        Weblog thisWeblog = aWeblog("myblog");
        Weblog anotherWeblog = aWeblog("someoneelse");
        WeblogEntry foreign = new WeblogEntry();
        foreign.setId("entry-1");
        foreign.setWebsite(anotherWeblog);
        when(weblogger.getWeblogEntryManager().getWeblogEntry("entry-1")).thenReturn(foreign);

        mockMvc(controllerFor(weblogger))
                .perform(post("/v1/weblogs/myblog/entries/{id}/preview", "entry-1")
                        .requestAttr("actionWeblog", thisWeblog)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"x\"}"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));
    }

    // ------------------------------------------------------------ event times

    private static Weblog aBerlinWeblog() {
        Weblog weblog = aWeblog("myblog");
        weblog.setTimeZone("Europe/Berlin");
        return weblog;
    }

    private static final String EVENT_FORMAT_DETAIL =
            "eventStart must be a wall-clock time in the weblog's zone, for example 2026-08-02T19:30.";

    @Test
    void anEventIsWrittenInTheWeblogsZoneAndReadBackAsInstants() throws Exception {
        Weblogger weblogger = mockedWeblogger();
        Weblog weblog = aBerlinWeblog();

        String body = mockMvc(controllerFor(weblogger))
                .perform(post("/v1/weblogs/myblog/entries")
                        .requestAttr("actionWeblog", weblog)
                        .requestAttr("authenticatedUser", aUser("maiia"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"t\",\"text\":\"x\",\"jsonLdType\":\"EVENT\","
                                + "\"eventStart\":\"2026-08-02T19:30\",\"eventEnd\":\"2026-08-02T22:00\","
                                + "\"eventLocation\":\"Waldb\u00fchne\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        ArgumentCaptor<WeblogEntry> captor = ArgumentCaptor.forClass(WeblogEntry.class);
        verify(weblogger.getWeblogEntryManager()).saveWeblogEntry(captor.capture());
        assertEquals(java.time.Instant.parse("2026-08-02T17:30:00Z"),
                captor.getValue().getEventStart().toInstant());
        assertEquals(java.time.Instant.parse("2026-08-02T20:00:00Z"),
                captor.getValue().getEventEnd().toInstant());
        assertEquals("Waldb\u00fchne", captor.getValue().getEventLocation());

        tools.jackson.databind.JsonNode json = new tools.jackson.databind.ObjectMapper().readTree(body);
        assertEquals("2026-08-02T17:30:00Z", json.get("eventStart").asString());
        assertEquals("2026-08-02T20:00:00Z", json.get("eventEnd").asString());
        assertEquals("Waldb\u00fchne", json.get("eventLocation").asString());
    }

    @Test
    void anEventTimeWithAnOffsetIsA400ShowingTheFormat() throws Exception {
        Weblogger weblogger = mockedWeblogger();

        String body = mockMvc(controllerFor(weblogger))
                .perform(post("/v1/weblogs/myblog/entries")
                        .requestAttr("actionWeblog", aBerlinWeblog())
                        .requestAttr("authenticatedUser", aUser("maiia"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"t\",\"text\":\"x\",\"eventStart\":\"2026-08-02T19:30+02:00\"}"))
                .andExpect(status().isBadRequest())
                .andReturn().getResponse().getContentAsString();

        assertTrue(body.contains(EVENT_FORMAT_DETAIL), body);
        verify(weblogger.getWeblogEntryManager(), never()).saveWeblogEntry(any());
    }

    @Test
    void anEventTimeWithSecondsIsAccepted() throws Exception {
        Weblogger weblogger = mockedWeblogger();

        mockMvc(controllerFor(weblogger))
                .perform(post("/v1/weblogs/myblog/entries")
                        .requestAttr("actionWeblog", aBerlinWeblog())
                        .requestAttr("authenticatedUser", aUser("maiia"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"t\",\"text\":\"x\",\"eventStart\":\"2026-08-02T19:30:00\"}"))
                .andExpect(status().isCreated());
    }

    @Test
    void anEndBeforeTheStartIsA400EvenWhenOnlyOneIsSent() throws Exception {
        Weblogger weblogger = mockedWeblogger();
        Weblog weblog = aBerlinWeblog();
        WeblogEntry entry = new WeblogEntry();
        entry.setId("entry-1");
        entry.setWebsite(weblog);
        entry.setEventStart(java.sql.Timestamp.from(java.time.Instant.parse("2026-08-02T17:30:00Z")));
        when(weblogger.getWeblogEntryManager().getWeblogEntry("entry-1")).thenReturn(entry);

        String body = mockMvc(controllerFor(weblogger))
                .perform(patch("/v1/weblogs/myblog/entries/{id}", "entry-1")
                        .requestAttr("actionWeblog", weblog)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"eventEnd\":\"2026-08-02T18:00\"}"))
                .andExpect(status().isBadRequest())
                .andReturn().getResponse().getContentAsString();

        assertTrue(body.contains("eventEnd must not be before eventStart."), body);
        verify(weblogger.getWeblogEntryManager(), never()).saveWeblogEntry(any());
    }

    @Test
    void aBlankEventTimeIsRefusedNotTreatedAsClearing() throws Exception {
        Weblogger weblogger = mockedWeblogger();

        String body = mockMvc(controllerFor(weblogger))
                .perform(post("/v1/weblogs/myblog/entries")
                        .requestAttr("actionWeblog", aBerlinWeblog())
                        .requestAttr("authenticatedUser", aUser("maiia"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"t\",\"text\":\"x\",\"eventStart\":\" \"}"))
                .andExpect(status().isBadRequest())
                .andReturn().getResponse().getContentAsString();

        assertTrue(body.contains(EVENT_FORMAT_DETAIL), body);
    }

    @Test
    void anEventLocationOver255IsA400() throws Exception {
        Weblogger weblogger = mockedWeblogger();

        mockMvc(controllerFor(weblogger))
                .perform(post("/v1/weblogs/myblog/entries")
                        .requestAttr("actionWeblog", aBerlinWeblog())
                        .requestAttr("authenticatedUser", aUser("maiia"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"t\",\"text\":\"x\",\"eventLocation\":\""
                                + "x".repeat(256) + "\"}"))
                .andExpect(status().isBadRequest());

        verify(weblogger.getWeblogEntryManager(), never()).saveWeblogEntry(any());
    }

    private static WeblogEntry aFullyDecoratedEntry(Weblog weblog) {
        WeblogEntry entry = new WeblogEntry();
        entry.setId("entry-1");
        entry.setWebsite(weblog);
        entry.setTitle("kept");
        entry.setText("body");
        entry.setSummary("s");
        entry.setMetaTitle("mt");
        entry.setSearchDescription("sd");
        entry.setCanonicalUrl("https://example.com/x");
        entry.setFeaturedImageId("img-1");
        entry.setOgImageId("img-2");
        entry.setJsonLdType(org.apache.roller.weblogger.pojos.JsonLdType.EVENT);
        entry.setGeoLatitude(32.9);
        entry.setGeoLongitude(-117.2);
        entry.setEventStart(java.sql.Timestamp.from(java.time.Instant.parse("2026-08-02T19:30:00Z")));
        entry.setEventEnd(java.sql.Timestamp.from(java.time.Instant.parse("2026-08-02T21:00:00Z")));
        entry.setEventLocation("The Hall");
        return entry;
    }

    private org.springframework.test.web.servlet.ResultActions patchEntry(
            Weblogger weblogger, Weblog weblog, String json) throws Exception {
        return mockMvc(controllerFor(weblogger))
                .perform(patch("/v1/weblogs/myblog/entries/{id}", "entry-1")
                        .requestAttr("actionWeblog", weblog)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json));
    }

    @Test
    void clearRemovesEachClearableEntryField() throws Exception {
        Weblogger weblogger = mockedWeblogger();
        Weblog weblog = aWeblog("myblog");
        WeblogEntry entry = aFullyDecoratedEntry(weblog);
        when(weblogger.getWeblogEntryManager().getWeblogEntry("entry-1")).thenReturn(entry);
        String names = org.apache.roller.weblogger.ui.restapi.dto.EntryDtos.ENTRY_CLEARABLE.stream()
                .map(n -> "\"" + n + "\"").collect(java.util.stream.Collectors.joining(","));

        String body = patchEntry(weblogger, weblog, "{\"clear\":[" + names + "]}")
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertNull(entry.getSummary());
        assertNull(entry.getMetaTitle());
        assertNull(entry.getSearchDescription());
        assertNull(entry.getCanonicalUrl());
        assertNull(entry.getFeaturedImageId());
        assertNull(entry.getOgImageId());
        assertEquals(org.apache.roller.weblogger.pojos.JsonLdType.BLOG_POSTING, entry.getJsonLdType());
        assertNull(entry.getGeoLatitude());
        assertNull(entry.getGeoLongitude());
        assertNull(entry.getEventStart());
        assertNull(entry.getEventEnd());
        assertNull(entry.getEventLocation());
        assertEquals("kept", entry.getTitle());
        verify(weblogger.getWeblogEntryManager()).saveWeblogEntry(entry);
        tools.jackson.databind.JsonNode json = new tools.jackson.databind.ObjectMapper().readTree(body);
        for (String name : org.apache.roller.weblogger.ui.restapi.dto.EntryDtos.ENTRY_CLEARABLE) {
            if ("jsonLdType".equals(name)) {
                assertEquals("BLOG_POSTING", json.get(name).asString());
            } else {
                assertTrue(json.get(name) == null || json.get(name).isNull(), name + " should be gone");
            }
        }
    }

    @Test
    void clearOnCreateIsA400() throws Exception {
        Weblogger weblogger = mockedWeblogger();
        Weblog weblog = aWeblog("myblog");

        String body = mockMvc(controllerFor(weblogger))
                .perform(post("/v1/weblogs/myblog/entries")
                        .requestAttr("actionWeblog", weblog)
                        .requestAttr("authenticatedUser", aUser("maiia"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"t\",\"text\":\"x\",\"clear\":[\"summary\"]}"))
                .andExpect(status().isBadRequest())
                .andReturn().getResponse().getContentAsString();

        assertTrue(body.contains("clear applies only to updates."), body);

        verify(weblogger.getWeblogEntryManager(), never()).saveWeblogEntry(any());
    }

    @Test
    void clearRefusalsChangeNothing() throws Exception {
        Weblogger weblogger = mockedWeblogger();
        Weblog weblog = aWeblog("myblog");
        WeblogEntry entry = aFullyDecoratedEntry(weblog);
        when(weblogger.getWeblogEntryManager().getWeblogEntry("entry-1")).thenReturn(entry);

        patchEntry(weblogger, weblog, "{\"clear\":[\"title\"]}").andExpect(status().isBadRequest());
        String pairBody = patchEntry(weblogger, weblog, "{\"clear\":[\"geoLatitude\"]}")
                .andExpect(status().isBadRequest()).andReturn().getResponse().getContentAsString();
        assertTrue(pairBody.contains("geoLatitude and geoLongitude are cleared together."), pairBody);
        patchEntry(weblogger, weblog, "{\"summary\":\"x\",\"clear\":[\"summary\"]}")
                .andExpect(status().isBadRequest());

        verify(weblogger.getWeblogEntryManager(), never()).saveWeblogEntry(any());
        assertEquals("s", entry.getSummary());
        assertEquals(32.9, entry.getGeoLatitude());
    }

    @Test
    void clearingTheStartLeavesAnEndAlone() throws Exception {
        Weblogger weblogger = mockedWeblogger();
        Weblog weblog = aWeblog("myblog");
        WeblogEntry entry = aFullyDecoratedEntry(weblog);
        java.sql.Timestamp end = entry.getEventEnd();
        when(weblogger.getWeblogEntryManager().getWeblogEntry("entry-1")).thenReturn(entry);

        patchEntry(weblogger, weblog, "{\"clear\":[\"eventStart\"]}").andExpect(status().isOk());

        assertNull(entry.getEventStart());
        assertEquals(end, entry.getEventEnd());
        verify(weblogger.getWeblogEntryManager()).saveWeblogEntry(entry);
    }
}
