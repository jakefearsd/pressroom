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
package com.wikantik.pressroom.business;

import java.util.Optional;

import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.pojos.WeblogPage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** The privacy setting's value: {@code slug} (a site page) or {@code handle/slug} (a blog's page). */
class PageReferenceTest {

    private Weblogger weblogger;
    private WeblogManager weblogs;
    private WeblogPageManager pages;
    private URLStrategy urls;

    @BeforeEach
    void setUp() {
        weblogger = mock(Weblogger.class);
        weblogs = mock(WeblogManager.class);
        pages = mock(WeblogPageManager.class);
        urls = mock(URLStrategy.class);
        when(weblogger.getWeblogManager()).thenReturn(weblogs);
        when(weblogger.getWeblogPageManager()).thenReturn(pages);
        when(weblogger.getUrlStrategy()).thenReturn(urls);
    }

    @Test
    void aBareSlugIsASitePage() {
        PageReference ref = PageReference.parse("privacy").orElseThrow();
        assertNull(ref.handle());
        assertEquals("privacy", ref.slug());
    }

    @Test
    void handleSlashSlugIsABlogsPage() {
        PageReference ref = PageReference.parse(" wikantik/privacy ").orElseThrow();
        assertEquals("wikantik", ref.handle());
        assertEquals("privacy", ref.slug());
    }

    @Test
    void malformedValuesAreNotReferences() {
        for (String bad : new String[] {null, "", "   ", "a/b/c", "/x", "x/", "/", "a/ "}) {
            assertTrue(PageReference.parse(bad).isEmpty(), "'" + bad + "'");
        }
    }

    private Weblog weblog(String handle) throws WebloggerException {
        Weblog blog = new Weblog();
        blog.setHandle(handle);
        when(weblogs.getWeblogByHandle(handle)).thenReturn(blog);
        return blog;
    }

    private void page(Weblog blog, String slug, WeblogPage.PubStatus status) throws WebloggerException {
        WeblogPage page = new WeblogPage();
        page.setSlug(slug);
        page.setStatus(status);
        when(pages.getPageBySlug(blog, slug)).thenReturn(page);
    }

    @Test
    void aSiteReferenceResolvesThroughTheSiteSpace() throws Exception {
        Weblog site = new Weblog();
        site.setHandle(Weblog.SITE_SPACE_HANDLE);
        when(weblogs.getSiteSpace()).thenReturn(site);
        page(site, "privacy", WeblogPage.PubStatus.PUBLISHED);

        assertTrue(PageReference.parse("privacy").orElseThrow().resolvePublished(weblogger).isPresent());
    }

    @Test
    void aBlogReferenceResolvesThroughThatBlog() throws Exception {
        page(weblog("wikantik"), "privacy", WeblogPage.PubStatus.PUBLISHED);
        assertTrue(PageReference.parse("wikantik/privacy").orElseThrow().resolvePublished(weblogger).isPresent());
    }

    @Test
    void onlyAPublishedPageResolves() throws Exception {
        page(weblog("wikantik"), "draft", WeblogPage.PubStatus.DRAFT);
        assertEquals(Optional.empty(),
                PageReference.parse("wikantik/draft").orElseThrow().resolvePublished(weblogger));
        assertEquals(Optional.empty(),
                PageReference.parse("wikantik/missing").orElseThrow().resolvePublished(weblogger));
        assertEquals(Optional.empty(),
                PageReference.parse("ghost/privacy").orElseThrow().resolvePublished(weblogger));
        assertEquals(Optional.empty(),
                PageReference.parse("privacy").orElseThrow().resolvePublished(weblogger),
                "no site space yet: nothing to resolve");
    }

    @Test
    void theSiteSpaceIsNeverNamedAsABlog() throws Exception {
        Weblog site = weblog(Weblog.SITE_SPACE_HANDLE);
        page(site, "privacy", WeblogPage.PubStatus.PUBLISHED);
        assertEquals(Optional.empty(), PageReference.parse("site-space/privacy").orElseThrow()
                .resolvePublished(weblogger), "a site page is written bare, never site-space/slug");
    }

    @Test
    void absoluteUrlComesFromTheUrlStrategy() throws Exception {
        Weblog blog = weblog("wikantik");
        when(urls.getWeblogStaticPageURL(blog, "privacy", true)).thenReturn("https://x.test/wikantik/privacy");
        assertEquals(Optional.of("https://x.test/wikantik/privacy"),
                PageReference.parse("wikantik/privacy").orElseThrow().absoluteUrl(weblogger));

        Weblog site = new Weblog();
        site.setHandle(Weblog.SITE_SPACE_HANDLE);
        when(weblogs.getSiteSpace()).thenReturn(site);
        when(urls.getWeblogStaticPageURL(site, "privacy", true)).thenReturn("https://x.test/privacy");
        assertEquals(Optional.of("https://x.test/privacy"),
                PageReference.parse("privacy").orElseThrow().absoluteUrl(weblogger));
    }

    @Test
    void absoluteUrlIsEmptyWhenTheBlogIsGone() throws Exception {
        assertEquals(Optional.empty(), PageReference.parse("ghost/privacy").orElseThrow().absoluteUrl(weblogger));
    }
}
