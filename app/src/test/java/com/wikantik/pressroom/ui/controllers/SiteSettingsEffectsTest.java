package com.wikantik.pressroom.ui.controllers;

import java.util.Date;
import java.util.List;
import java.util.Map;

import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.business.MockWeblogger;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.util.cache.CacheManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.MockedStatic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link SiteSettingsEffects#afterSave} on plain values (spec
 * 2026-11-20-jsp-rules-and-view-models-design, Task 1): which changes move
 * every weblog's {@code lastModified} and clear the site-wide cache, and which
 * rebuild the discovery snapshot.
 */
class SiteSettingsEffectsTest {

    private static final Date LONG_AGO = new Date(1_000L);

    private MockWeblogger weblogger;
    private Weblog blog;
    private Weblog site;

    @BeforeEach
    void setUp() throws Exception {
        weblogger = MockWeblogger.attached();
        blog = new Weblog();
        blog.setHandle("travel");
        blog.setLastModified(LONG_AGO);
        site = new Weblog();
        site.setSiteSpace(true);
        site.setLastModified(LONG_AGO);
        when(weblogger.weblogManager().getWeblogs(any(), any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(List.of(blog));
        when(weblogger.weblogManager().getSiteSpace()).thenReturn(site);
    }

    @AfterEach
    void tearDown() {
        weblogger.detach();
    }

    private void afterSave(Map<String, String> values, Map<String, String> before) throws WebloggerException {
        SiteSettingsEffects.afterSave(weblogger.weblogger(), values, before);
    }

    @Test
    void aChangeToEachRenderedPageInputMovesEveryWeblogAndTheSiteSpaceAndClearsTheCache() throws Exception {
        for (String name : SiteSettingsEffects.RENDERED_PAGE_INPUTS) {
            blog.setLastModified(LONG_AGO);
            site.setLastModified(LONG_AGO);
            try (MockedStatic<CacheManager> cache = mockStatic(CacheManager.class)) {
                afterSave(Map.of(name, "new"), Map.of(name, "old"));

                cache.verify(() -> CacheManager.invalidate(blog));
                cache.verify(() -> CacheManager.invalidate(site));
            }
            assertTrue(blog.getLastModified().after(LONG_AGO), name);
            assertTrue(site.getLastModified().after(LONG_AGO), name);
        }
    }

    @Test
    void theWeblogsAreSavedAndFlushedBeforeTheCacheIsCleared() throws Exception {
        try (MockedStatic<CacheManager> cache = mockStatic(CacheManager.class)) {
            afterSave(Map.of("site.name", "New"), Map.of("site.name", "Old"));

            InOrder order = inOrder(weblogger.weblogManager(), weblogger.weblogger());
            order.verify(weblogger.weblogManager()).saveWeblog(blog);
            order.verify(weblogger.weblogManager()).saveWeblog(site);
            order.verify(weblogger.weblogger()).flush();
            cache.verify(() -> CacheManager.invalidate(blog));
        }
    }

    @Test
    void withNoSiteSpaceOnlyTheWeblogsAreTouched() throws Exception {
        when(weblogger.weblogManager().getSiteSpace()).thenReturn(null);

        afterSave(Map.of("site.name", "New"), Map.of("site.name", "Old"));

        verify(weblogger.weblogManager()).saveWeblog(blog);
        assertEquals(LONG_AGO, site.getLastModified());
    }

    @Test
    void anUnchangedRenderedPageInputOrAnyOtherChangeTouchesNothing() throws Exception {
        afterSave(Map.of("site.name", "Same", "site.adminemail", "b@x.test"),
                Map.of("site.name", "Same", "site.adminemail", "a@x.test"));

        assertEquals(LONG_AGO, blog.getLastModified());
        verify(weblogger.weblogManager(), never()).saveWeblog(any());
        verify(weblogger.discoveryManager(), never()).refresh();
    }

    @Test
    void aChangedDiscoverySettingRefreshesTheSnapshotAndAnUnchangedOneDoesNot() throws Exception {
        afterSave(Map.of("site.discovery.windowDays", "30"), Map.of("site.discovery.windowDays", "30"));
        verify(weblogger.discoveryManager(), never()).refresh();

        afterSave(Map.of("site.discovery.windowDays", "31"), Map.of("site.discovery.windowDays", "30"));
        verify(weblogger.discoveryManager()).refresh();
        verify(weblogger.weblogManager(), never()).saveWeblog(any());
    }

    @Test
    void aFailureTouchingTheWeblogsThrowsBeforeTheRefresh() throws Exception {
        doThrow(new WebloggerException("down")).when(weblogger.weblogManager()).saveWeblog(blog);

        assertThrows(WebloggerException.class, () -> afterSave(
                Map.of("site.name", "New", "site.discovery.windowDays", "31"),
                Map.of("site.name", "Old", "site.discovery.windowDays", "30")));

        verify(weblogger.discoveryManager(), never()).refresh();
    }
}
