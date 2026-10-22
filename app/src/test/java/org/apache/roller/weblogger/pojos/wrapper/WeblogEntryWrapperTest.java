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
package org.apache.roller.weblogger.pojos.wrapper;

import org.apache.roller.weblogger.WebloggerException;
import org.apache.roller.weblogger.business.CommentManager;
import org.apache.roller.weblogger.business.EntryRenderer;
import org.apache.roller.weblogger.business.MediaFileManager;
import org.apache.roller.weblogger.business.PreviewURLStrategy;
import org.apache.roller.weblogger.business.URLStrategy;
import org.apache.roller.weblogger.business.Weblogger;
import org.apache.roller.weblogger.pojos.MediaFile;
import org.apache.roller.weblogger.pojos.ReaderComment;
import org.apache.roller.weblogger.pojos.User;
import org.apache.roller.weblogger.pojos.Weblog;
import org.apache.roller.weblogger.pojos.WeblogCategory;
import org.apache.roller.weblogger.pojos.WeblogEntry;
import org.apache.roller.weblogger.pojos.WeblogEntry.PubStatus;
import org.apache.roller.weblogger.util.HTMLSanitizer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Timestamp;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Checks that {@link WeblogEntryWrapper} really is a view of the entry it
 * wraps, and that the transformations it applies on the way out actually
 * happen.
 *
 * <p>A wrapper accessor that returned a constant, or that forgot to sanitise,
 * would look perfectly healthy in a coverage report. So each accessor here is
 * asserted against a distinct value taken from the underlying entry, and the
 * sanitising accessors are exercised with sanitising switched on.
 */
class WeblogEntryWrapperTest {

    private Weblog weblog;
    private WeblogEntry entry;
    private URLStrategy urls;
    private Weblogger weblogger;
    private WeblogEntryWrapper wrapper;

    @BeforeEach
    void setUp() {
        weblog = new Weblog();
        weblog.setHandle("testblog");
        weblog.setLocale("en_US");

        entry = new WeblogEntry();
        entry.setWebsite(weblog);
        entry.setAnchor("hello-world");
        entry.setTitle("Hello World");
        entry.setSummary("A summary");
        entry.setText("Some body text");
        entry.setLink("http://example.com/link");
        entry.setLocale("fr");
        entry.setStatus(PubStatus.PUBLISHED);
        entry.setPubTime(Timestamp.valueOf("2024-03-09 15:30:00"));
        entry.setUpdateTime(Timestamp.valueOf("2024-03-10 09:00:00"));
        entry.setPinnedToMain(Boolean.TRUE);
        entry.setRightToLeft(Boolean.TRUE);
        entry.setSearchDescription("A search description");

        urls = mock(URLStrategy.class);
        weblogger = mock(Weblogger.class);
        wrapper = WeblogEntryWrapper.wrap(entry, urls, weblogger);
    }

    @Test
    void scalarAccessorsReportTheWrappedEntrysOwnValues() {
        // Each field carries a value distinct from every other, so an accessor
        // wired to the wrong field cannot pass by coincidence.
        assertEquals(entry.getId(), wrapper.getId());
        assertEquals("hello-world", wrapper.getAnchor());
        assertEquals("Hello World", wrapper.getTitle());
        assertEquals("A summary", wrapper.getSummary());
        assertEquals("Some body text", wrapper.getText());
        assertEquals("A search description", wrapper.getSearchDescription());
        assertEquals("http://example.com/link", wrapper.getLink());
        assertEquals("fr", wrapper.getLocale());
        assertEquals(PubStatus.PUBLISHED, wrapper.getStatus());
        assertEquals(Timestamp.valueOf("2024-03-09 15:30:00"), wrapper.getPubTime());
        assertEquals(Timestamp.valueOf("2024-03-10 09:00:00"), wrapper.getUpdateTime());
    }

    @Test
    void everyBooleanFlagTracksItsOwnFieldInBothStates() {
        // A boolean accessor read once proves nothing: one hard-wired to a
        // constant, or wired to the neighbouring flag, agrees half the time.
        // Holding the other flags at the opposite value rules that out.
        entry.setPinnedToMain(Boolean.TRUE);
        entry.setRightToLeft(Boolean.FALSE);
        assertEquals(Boolean.TRUE, wrapper.getPinnedToMain(),
                "Pinning a post to the site front page is a per-post decision, so this "
                        + "must not answer for another flag");
        assertEquals(Boolean.FALSE, wrapper.getRightToLeft());

        entry.setPinnedToMain(Boolean.FALSE);
        entry.setRightToLeft(Boolean.TRUE);
        assertEquals(Boolean.FALSE, wrapper.getPinnedToMain());
        assertEquals(Boolean.TRUE, wrapper.getRightToLeft(),
                "A right-to-left post must render right to left; a flag stuck at one "
                        + "answer breaks either every Arabic blog or every English one");
    }

    @Test
    void theTextAccessorsRunThroughTheSanitiser() {
        // Sanitising follows the weblogAdminsUntrusted setting; it is pinned on
        // here so the test states what it depends on rather than inheriting it
        // from configuration that a deployment may change.
        Boolean previous = HTMLSanitizer.xssEnabled;
        try {
            HTMLSanitizer.xssEnabled = Boolean.TRUE;
            entry.setTitle("Title <script>alert(1)</script>");
            entry.setSummary("Summary <script>alert(1)</script>");
            entry.setText("Text <script>alert(1)</script>");
            entry.setSearchDescription("Desc <script>alert(1)</script>");

            assertFalse(wrapper.getTitle().contains("<script>"),
                    "With untrusted weblog admins configured, the wrapper must sanitise "
                            + "the title before a theme renders it");
            assertFalse(wrapper.getSummary().contains("<script>"));
            assertFalse(wrapper.getText().contains("<script>"));
            assertFalse(wrapper.getSearchDescription().contains("<script>"));

            assertTrue(entry.getTitle().contains("<script>"),
                    "Precondition: the underlying entry still holds the raw value, so the "
                            + "cleaning demonstrably happens in the wrapper");
        } finally {
            HTMLSanitizer.xssEnabled = previous;
        }
    }

    @Test
    void wrappingNullGivesNull() {
        assertNull(WeblogEntryWrapper.wrap(null, urls, weblogger),
                "Templates test entries for presence before dereferencing them");
    }

    @Test
    void associatedObjectsComeBackWrappedNotRaw() {
        WeblogCategory category = new WeblogCategory();
        category.setName("General");
        entry.setCategory(category);

        assertEquals("General", wrapper.getCategory().getName(),
                "The category must be reachable through the wrapper");
        assertEquals(List.of("General"),
                wrapper.getCategories().stream().map(WeblogCategoryWrapper::getName).toList(),
                "getCategories() is what feed templates iterate");
        assertEquals("testblog", wrapper.getWebsite().getHandle(),
                "The owning weblog must be reachable, wrapped");
    }

    @Test
    void tagsAreHandedOutSortedByNameAndWrapped() throws Exception {
        entry.addTag("zebra");
        entry.addTag("apple");
        entry.addTag("mango");

        List<String> names = wrapper.getTags().stream()
                .map(WeblogEntryTagWrapper::getName).toList();

        assertEquals(List.of("apple", "mango", "zebra"), names,
                "The pojo stores tags in a HashSet, whose iteration order is arbitrary. "
                        + "The wrapper sorts them so a theme rendering a tag list gets the "
                        + "same order on every request.");
        assertEquals("apple mango zebra", wrapper.getTagsAsString());
    }

    @Test
    void entryAttributesAreHandedOutWrapped() throws Exception {
        entry.putEntryAttribute("mood", "cheerful");

        List<WeblogEntryAttributeWrapper> attributes = wrapper.getEntryAttributes();
        assertEquals(1, attributes.size());
        assertEquals("mood", attributes.get(0).getName());
        assertEquals("cheerful", attributes.get(0).getValue());
        assertEquals("cheerful", wrapper.findEntryAttribute("mood"));
    }

    @Test
    void derivedTextAccessorsDelegateToTheEntry() {
        entry.setTitle(null);
        entry.setText("Body of the post");

        assertEquals("Body of the post", wrapper.getDisplayTitle(),
                "The wrapper must reuse the entry's fallback-title logic rather than "
                        + "reimplementing it");
        assertEquals("Body of the post", wrapper.getRss09xDescription());
        assertEquals("Body...", wrapper.getRss09xDescription(7),
                "The length argument has to reach the entry; dropping it would put the "
                        + "whole post into an RSS 0.9x description");
    }

    @Test
    void dateFormattingDelegatesToTheEntry() {
        assertEquals("2024-03-09", wrapper.formatPubTime("yyyy-MM-dd"));
        assertEquals("2024-03-10", wrapper.formatUpdateTime("yyyy-MM-dd"));
    }

    // Exercises a deprecated theme-facing alias on purpose.
    @SuppressWarnings("deprecation")
    @Test
    void permalinksComeFromTheUrlStrategyAndTheDeprecatedFormsFromTheHandle() throws Exception {
        when(urls.getWeblogEntryURL(weblog, null, "hello-world", true))
                .thenReturn("http://example.com/roller/testblog/entry/hello-world");

        // No static locator: the strategy the wrapper was GIVEN is the only
        // source of its permalink (spec Decision 6 -- a preview installs a
        // preview-aware one, and the wrapper used to bypass it).
        assertEquals("http://example.com/roller/testblog/entry/hello-world",
                wrapper.getPermalink(),
                "The canonical permalink is whatever the injected URL strategy says, absolute "
                        + "so it survives being copied into a feed");

        assertEquals("/testblog/entry/hello-world", wrapper.getPermaLink(),
                "The deprecated relative form is built from the handle and anchor");
    }

    // Exercises a deprecated theme-facing alias on purpose.
    @SuppressWarnings("deprecation")
    @Test
    void anchorsAreUrlEncodedInTheDeprecatedPermalink() {
        entry.setAnchor("hello world&again");

        assertEquals("/testblog/entry/hello+world%26again", wrapper.getPermaLink(),
                "An anchor containing a space or an ampersand must be encoded, or the "
                        + "link it produces is broken HTML");
    }

    @Test
    void renderedContentAccessorsGoThroughTheInjectedFacadesRenderer() {
        // The wrapper renders through the facade it was constructed with --
        // a real EntryRenderer here, over that same facade -- never through
        // anything static.
        org.apache.roller.weblogger.business.plugins.PluginManager plugins =
                mock(org.apache.roller.weblogger.business.plugins.PluginManager.class);
        when(plugins.getWeblogEntryPlugins(weblog)).thenReturn(java.util.Map.of());
        when(weblogger.getEntryRenderer()).thenReturn(new org.apache.roller.weblogger.business.EntryRenderer(
                org.apache.roller.weblogger.business.shortcodes.ShortcodeExpander.builtIn(
                        weblogger, mock(org.apache.roller.weblogger.business.MediaFileManager.class)),
                plugins));
        entry.setText("The whole post");
        entry.setSummary("Just a taste");

        // Every entry is markdown, so even a bare line of prose comes back
        // as a rendered paragraph -- there is no pass-through path left.
        assertEquals("<p>The whole post</p>\n", wrapper.getTransformedText());
        assertEquals("<p>Just a taste</p>\n", wrapper.getTransformedSummary(),
                "The summary accessor must not quietly return the body");
        assertEquals("<p>The whole post</p>\n", wrapper.getDisplayContent(),
                "With no read-more link the permalink form is used");
        assertTrue(wrapper.displayContent("http://example.com/entry")
                        .startsWith("<p>Just a taste</p>"),
                "and with one, the teaser form");
    }

    @Test
    void theCreatorIsReadThroughTheEntry() throws Exception {
        // The author is resolved by name through the facade the wrapper was
        // given; WeblogEntry.getCreator() is gone (plan Task 16).
        org.apache.roller.weblogger.business.UserManager users =
                mock(org.apache.roller.weblogger.business.UserManager.class);
        when(weblogger.getUserManager()).thenReturn(users);

        org.apache.roller.weblogger.pojos.User alice =
                new org.apache.roller.weblogger.pojos.User();
        alice.setUserName("alice");
        alice.setScreenName("Alice A");
        when(users.getUserByUserName("alice")).thenReturn(alice);

        entry.setCreatorUserName("alice");

        assertEquals("Alice A", wrapper.getCreator().getScreenName(),
                "The author must be reachable, wrapped, for the byline");
    }

    @Test
    void seoScalarAccessorsReportTheWrappedEntrysOwnValues() {
        entry.setFeaturedImageId("mf-featured-1");
        entry.setMetaTitle("Custom <b>SEO</b> Title");
        entry.setOgImageId("mf-og-1");
        entry.setCanonicalUrl("http://example.com/canonical");
        entry.setNoindex(Boolean.TRUE);

        assertEquals("mf-featured-1", wrapper.getFeaturedImageId());
        assertEquals("mf-og-1", wrapper.getOgImageId());
        assertEquals("http://example.com/canonical", wrapper.getCanonicalUrl());
        assertEquals(Boolean.TRUE, wrapper.getNoindex());
    }

    @Test
    void travelScalarAccessorsReportTheWrappedEntrysOwnValues() {
        entry.setJsonLdType(org.apache.roller.weblogger.pojos.JsonLdType.EVENT);
        entry.setGeoLatitude(48.8584);
        entry.setGeoLongitude(2.2945);
        entry.setEventStart(Timestamp.valueOf("2026-06-01 18:00:00"));
        entry.setEventEnd(Timestamp.valueOf("2026-06-01 23:00:00"));

        assertEquals(org.apache.roller.weblogger.pojos.JsonLdType.EVENT, wrapper.getJsonLdType());
        assertEquals(48.8584, wrapper.getGeoLatitude());
        assertEquals(2.2945, wrapper.getGeoLongitude());
        assertEquals(Timestamp.valueOf("2026-06-01 18:00:00"), wrapper.getEventStart());
        assertEquals(Timestamp.valueOf("2026-06-01 23:00:00"), wrapper.getEventEnd());
    }

    @Test
    void travelAccessorsAreNullSafeForEntriesThatPredateThem() {
        assertNull(wrapper.getJsonLdType(),
                "Null means the BLOG_POSTING default; the wrapper must not invent a value");
        assertNull(wrapper.getGeoLatitude());
        assertNull(wrapper.getGeoLongitude());
        assertNull(wrapper.getEventStart());
        assertNull(wrapper.getEventEnd());
        assertNull(wrapper.getEventLocation());
    }

    @Test
    void eventLocationRunsThroughTheSanitiser() {
        Boolean previous = HTMLSanitizer.xssEnabled;
        try {
            HTMLSanitizer.xssEnabled = Boolean.TRUE;
            entry.setEventLocation("Venue <script>alert(1)</script>");

            assertFalse(wrapper.getEventLocation().contains("<script>"),
                    "The event venue is author-entered free text, so it must be sanitised "
                            + "like metaTitle and every other free-text accessor");
        } finally {
            HTMLSanitizer.xssEnabled = previous;
        }
    }

    @Test
    void metaTitleRunsThroughTheSanitiser() {
        Boolean previous = HTMLSanitizer.xssEnabled;
        try {
            HTMLSanitizer.xssEnabled = Boolean.TRUE;
            entry.setMetaTitle("Title <script>alert(1)</script>");

            assertFalse(wrapper.getMetaTitle().contains("<script>"),
                    "The SEO <title> override reaches <head> unescaped in T4's macro, so it "
                            + "must be sanitised here like every other free-text accessor");
        } finally {
            HTMLSanitizer.xssEnabled = previous;
        }
    }

    @Test
    void featuredImageAndOgImageResolveThroughTheMediaFileManagerWrapped() throws Exception {
        entry.setFeaturedImageId("mf-featured-1");
        entry.setOgImageId("mf-og-1");

        MediaFile featured = new MediaFile();
        featured.setName("featured.jpg");
        MediaFile og = new MediaFile();
        og.setName("og.jpg");

        MediaFileManager mediaFiles = mock(MediaFileManager.class);
        when(mediaFiles.getMediaFile("mf-featured-1")).thenReturn(featured);
        when(mediaFiles.getMediaFile("mf-og-1")).thenReturn(og);
        when(weblogger.getMediaFileManager()).thenReturn(mediaFiles);

        assertEquals("featured.jpg", wrapper.getFeaturedImage().getName(),
                "getFeaturedImage() must resolve the id via the INJECTED tier's media file "
                        + "manager, wrapped");
        assertEquals("og.jpg", wrapper.getOgImage().getName());
    }

    @Test
    void featuredImageAndOgImageAreNullSafeWhenNoIdIsSet() {
        assertNull(wrapper.getFeaturedImageId());
        assertNull(wrapper.getFeaturedImage(),
                "No featured image id means no image -- callers must not have to null-check "
                        + "getFeaturedImageId() before calling getFeaturedImage()");
        assertNull(wrapper.getOgImage());
    }

    @Test
    void featuredImageResolvesToNullRatherThanThrowingWhenTheIdIsStale() throws Exception {
        // The media file may have been deleted independently of the entry
        // (see the javadoc on WeblogEntry#getFeaturedImageId()); the wrapper
        // must degrade to "no image", not propagate the lookup failure.
        entry.setFeaturedImageId("mf-deleted");

        MediaFileManager mediaFiles = mock(MediaFileManager.class);
        when(mediaFiles.getMediaFile("mf-deleted"))
                .thenThrow(new org.apache.roller.weblogger.WebloggerException("not found"));
        when(weblogger.getMediaFileManager()).thenReturn(mediaFiles);

        assertNull(wrapper.getFeaturedImage());
    }

    @Test
    void thePojoEscapeHatchReturnsTheVeryObjectThatWasWrapped() {
        assertSame(entry, wrapper.getPojo(),
                "The rendering internals unwrap through getPojo(); handing back a copy "
                        + "would silently drop any state they then set on it");
    }

    @Test
    void theRelativePermalinkComesFromTheUrlStrategyWithTheContextPath() {
        when(urls.getWeblogEntryURL(weblog, null, "hello-world", false))
                .thenReturn("/roller/testblog/entry/hello-world");
        when(urls.getWeblogEntryURL(weblog, null, "hello-world", true))
                .thenReturn("https://example.com/roller/testblog/entry/hello-world");

        assertEquals("/roller/testblog/entry/hello-world", wrapper.getRelativePermalink());
    }

    private static ReaderComment published(String id, String screenName) {
        User author = new User();
        author.setUserName("u-" + id);
        author.setScreenName(screenName);
        ReaderComment c = new ReaderComment();
        c.setId(id);
        c.setAuthor(author);
        c.setStatus(ReaderComment.Status.PUBLISHED);
        c.setBody("body " + id);
        return c;
    }

    @Test
    void commentsAreThePublishedListWrappedInOrderFromOneLookup() throws Exception {
        CommentManager comments = mock(CommentManager.class);
        when(weblogger.getCommentManager()).thenReturn(comments);
        when(comments.getPublishedForEntry(entry))
                .thenReturn(List.of(published("c1", "Ana"), published("c2", "Bo")));

        List<ReaderCommentWrapper> shown = wrapper.getComments();
        assertEquals(List.of("c1", "c2"), shown.stream().map(ReaderCommentWrapper::getId).toList());
        assertEquals("Bo", shown.get(1).getDisplayName());
        assertEquals(2L, wrapper.getCommentCount(), "the heading counts what the list shows");
        assertEquals(2, wrapper.getComments().size());

        // One page render asks for the list and its count: one query, not one per call.
        verify(comments, times(1)).getPublishedForEntry(entry);
    }

    @Test
    void noCommentsIsAnEmptyListAndAZeroCount() throws Exception {
        CommentManager comments = mock(CommentManager.class);
        when(weblogger.getCommentManager()).thenReturn(comments);
        when(comments.getPublishedForEntry(entry)).thenReturn(List.of());

        assertTrue(wrapper.getComments().isEmpty());
        assertEquals(0L, wrapper.getCommentCount());
    }

    @Test
    void aFailedLookupShowsNoCommentsRatherThanBreakingThePage() throws Exception {
        CommentManager comments = mock(CommentManager.class);
        when(weblogger.getCommentManager()).thenReturn(comments);
        when(comments.getPublishedForEntry(entry)).thenThrow(new WebloggerException("db down"));

        assertTrue(wrapper.getComments().isEmpty());
        assertEquals(0L, wrapper.getCommentCount());
    }

    /**
     * A reader's render goes through the entry-HTML cache; a preview --
     * wrapped with the preview url strategy -- never reads or writes it, so
     * a draft or a previewed theme can neither be served a live render nor
     * leave one behind.
     */
    @Test
    void aLiveRenderUsesTheEntryCacheAndAPreviewNeverDoes() {
        EntryRenderer live = mock(EntryRenderer.class);
        when(weblogger.getEntryRenderer()).thenReturn(live);

        wrapper.getTransformedText();
        wrapper.getTransformedSummary();
        wrapper.getDisplayContent();
        wrapper.displayContent("https://example.com/more");

        verify(live).cachedTransformedText(entry);
        verify(live).cachedTransformedSummary(entry);
        verify(live).cachedDisplayContent(entry, null);
        verify(live).cachedDisplayContent(entry, "https://example.com/more");
        verify(live, never()).transformedText(any());
        verify(live, never()).transformedSummary(any());
        verify(live, never()).displayContent(any(), any());

        EntryRenderer preview = mock(EntryRenderer.class);
        when(weblogger.getEntryRenderer()).thenReturn(preview);
        WeblogEntryWrapper previewed = WeblogEntryWrapper.wrap(entry, new PreviewURLStrategy("journal"), weblogger);

        previewed.getTransformedText();
        previewed.getTransformedSummary();
        previewed.getDisplayContent();
        previewed.displayContent("https://example.com/more");

        verify(preview).transformedText(entry);
        verify(preview).transformedSummary(entry);
        verify(preview).displayContent(entry, null);
        verify(preview).displayContent(entry, "https://example.com/more");
        verify(preview, never()).cachedTransformedText(any());
        verify(preview, never()).cachedTransformedSummary(any());
        verify(preview, never()).cachedDisplayContent(any(), any());
    }
}
