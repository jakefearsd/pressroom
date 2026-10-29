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

package com.wikantik.pressroom.ui.rendering.model;

import jakarta.servlet.http.HttpServletRequest;
import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.business.MultiWeblogURLStrategy;
import com.wikantik.pressroom.business.PreviewURLStrategy;
import com.wikantik.pressroom.business.PropertiesManager;
import com.wikantik.pressroom.business.Weblogger;
import com.wikantik.pressroom.config.RuntimeConfigAttachment;
import com.wikantik.pressroom.config.WebloggerRuntimeConfig;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.pojos.WeblogEntry;
import com.wikantik.pressroom.ui.rendering.pagers.WeblogEntriesLatestPager;
import com.wikantik.pressroom.ui.rendering.pagers.WeblogEntriesPreviewPager;
import com.wikantik.pressroom.ui.rendering.util.WeblogPageRequest;
import com.wikantik.pressroom.ui.rendering.util.WeblogPreviewRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link PreviewURLModel} and {@link PreviewPageModel}, the
 * variants loaded when an author previews an unsaved theme or a draft entry.
 *
 * <p>Preview mode is where the two ways a page can be rendered diverge, so both
 * models start by refusing anything that is not a preview request — otherwise a
 * live page could be served through the preview path, which shows drafts.
 */
class PreviewModelsTest {

    private static final String SITE = "/roller";
    private static final String ABSOLUTE_SITE = "http://blogs.example.com/roller";

    private RuntimeConfigAttachment runtimeConfig;
    private Weblogger weblogger;
    private Weblog weblog;
    private String previousRelativeContextURL;

    @BeforeEach
    void setUp() {
        // The facade the models are GIVEN through initData.
        weblogger = mock(Weblogger.class);
        // WebloggerRuntimeConfig reads the properties manager attached to it
        // (spec Decision 8 / plan Task 19); a mock is attached, nothing else.
        runtimeConfig = RuntimeConfigAttachment.of(mock(PropertiesManager.class));

        previousRelativeContextURL = WebloggerRuntimeConfig.getRelativeContextURL();
        WebloggerRuntimeConfig.setRelativeContextURL(SITE);
        WebloggerRuntimeConfig.setAbsoluteContextURL(ABSOLUTE_SITE);

        weblog = new Weblog("testblog", "testuser", "Test Blog", "a test blog",
                "blog@example.com", "journal", "en_US", "UTC");
    }

    @AfterEach
    void tearDown() {
        WebloggerRuntimeConfig.setRelativeContextURL(previousRelativeContextURL);
        WebloggerRuntimeConfig.setAbsoluteContextURL(null);
        runtimeConfig.close();
    }

    /**
     * WeblogPreviewRequest has no no-arg constructor, so it is built the way the
     * preview servlet builds it — from a request on the preview servlet path.
     */
    private WeblogPreviewRequest previewRequest() throws Exception {
        HttpServletRequest servletRequest = mock(HttpServletRequest.class);
        when(servletRequest.getServletPath()).thenReturn("/pressroom-ui/authoring/preview");
        when(servletRequest.getPathInfo()).thenReturn("/testblog");
        when(servletRequest.getParameterMap()).thenReturn(Map.of());

        WeblogPreviewRequest request = new WeblogPreviewRequest(weblogger, servletRequest);
        request.setWeblog(weblog);
        return request;
    }

    private Map<String, Object> initData(Object request, Object urlStrategy) {
        Map<String, Object> initData = new HashMap<>();
        initData.put("parsedRequest", request);
        initData.put("weblogger", weblogger);
        initData.put("urlStrategy", urlStrategy);
        return initData;
    }

    // -------------------------------------------------------- PreviewURLModel

    @Test
    void previewUrlModelRejectsAnythingButAPreviewRequest() {
        WeblogPageRequest liveRequest = new WeblogPageRequest();
        liveRequest.setWeblog(weblog);

        PreviewURLModel model = new PreviewURLModel();
        WebloggerException thrown = assertThrows(WebloggerException.class,
                () -> model.init(initData(liveRequest, new MultiWeblogURLStrategy())),
                "Serving a live request through the preview model would expose "
                        + "unpublished content.");
        assertTrue(thrown.getMessage().contains("WeblogPreviewRequest"),
                "The failure should say which request type is required; was: "
                        + thrown.getMessage());
    }

    @Test
    void previewUrlModelRejectsMissingInitData() {
        PreviewURLModel model = new PreviewURLModel();
        assertThrows(WebloggerException.class, () -> model.init(new HashMap<>()),
                "init() must reject init data with no request.");
    }

    @Test
    void previewResourceUrlsGoThroughThePreviewResourceServlet() throws Exception {
        // While previewing an unsaved theme the ordinary resource servlet has no
        // way of knowing which theme to serve from, so resources have to be
        // routed through the preview servlet with the theme named.
        PreviewURLModel model = new PreviewURLModel();
        model.init(initData(previewRequest(), new PreviewURLStrategy("someTheme")));

        assertEquals(ABSOLUTE_SITE + "/pressroom-ui/authoring/previewresource/testblog/"
                        + "style.css?theme=someTheme",
                model.resource("style.css"),
                "Preview resource URLs must carry the theme being previewed.");
    }

    @Test
    void previewUrlModelRefusesInitDataWithoutAUrlStrategy() throws Exception {
        // Used to fall back to the static locator's strategy; the preview servlet
        // always supplies its own, and a missing one is a caller bug.
        Map<String, Object> initData = new HashMap<>();
        initData.put("parsedRequest", previewRequest());
        initData.put("weblogger", weblogger);
        PreviewURLModel model = new PreviewURLModel();

        WebloggerException thrown = assertThrows(WebloggerException.class,
                () -> model.init(initData),
                "init() must refuse init data with no 'urlStrategy'.");
        assertTrue(thrown.getMessage().contains("urlStrategy"),
                "The failure should name what was missing; was: " + thrown.getMessage());
    }

    @Test
    void previewUrlModelStillProvidesEveryOrdinaryUrl() throws Exception {
        // PreviewURLModel overrides only resource(); everything else is
        // inherited and depends on URLModel.init() having run. Skipping that
        // call would leave every other link on a previewed page null.
        PreviewURLModel model = new PreviewURLModel();
        model.init(initData(previewRequest(), new MultiWeblogURLStrategy()));

        assertEquals(ABSOLUTE_SITE + "/testblog/", model.getHome(),
                "The inherited URL builders must work in preview mode too.");
        assertEquals(SITE, model.getSite(),
                "The inherited site root must work in preview mode too.");
    }

    // ------------------------------------------------------- PreviewPageModel

    @Test
    void previewPageModelRejectsAnythingButAPreviewRequest() {
        WeblogPageRequest liveRequest = new WeblogPageRequest();
        liveRequest.setWeblog(weblog);

        PreviewPageModel model = new PreviewPageModel();
        WebloggerException thrown = assertThrows(WebloggerException.class,
                () -> model.init(initData(liveRequest, new MultiWeblogURLStrategy())),
                "The preview page model shows drafts, so it must only ever be given "
                        + "a preview request.");
        assertTrue(thrown.getMessage().contains("WeblogPreviewRequest"),
                "The failure should say which request type is required; was: "
                        + thrown.getMessage());
    }

    @Test
    void previewPageModelRejectsMissingInitData() {
        PreviewPageModel model = new PreviewPageModel();
        assertThrows(WebloggerException.class, () -> model.init(new HashMap<>()),
                "init() must reject init data with no request.");
    }

    private PreviewPageModel previewPageModel(WeblogPreviewRequest request)
            throws WebloggerException {
        PreviewPageModel model = new PreviewPageModel();
        model.init(initData(request, new MultiWeblogURLStrategy()));
        return model;
    }

    @Test
    void previewingASingleDraftCountsAsAPermalink() throws Exception {
        // The theme renders the comment form and single-entry layout behind
        // $model.permalink, and an author previewing one draft wants to see it.
        WeblogPreviewRequest request = previewRequest();
        request.setPreviewEntry("my-draft");

        assertTrue(previewPageModel(request).isPermalink(),
                "A previewed draft must render as a permalink page.");
    }

    @Test
    void previewingTheWholeBlogIsNotAPermalink() throws Exception {
        assertFalse(previewPageModel(previewRequest()).isPermalink(),
                "With no entry named, the preview is of the front page.");
    }

    @Test
    void theDraftBeingPreviewedIsExposedAsTheEntry() throws Exception {
        WeblogEntry draft = new WeblogEntry();
        draft.setTitle("Unpublished Draft");
        draft.setWebsite(weblog);
        WeblogPreviewRequest request = previewRequest();
        request.setPreviewEntry("my-draft");
        request.setWeblogEntry(draft);

        assertEquals("Unpublished Draft",
                previewPageModel(request).getWeblogEntry().getTitle(),
                "$model.weblogEntry must be the draft, which is the whole point of "
                        + "previewing.");
    }

    @Test
    void noEntryIsExposedWhenNoneIsBeingPreviewed() throws Exception {
        assertNull(previewPageModel(previewRequest()).getWeblogEntry(),
                "A front-page preview has no single entry.");
    }

    @Test
    void previewingADraftUsesThePagerThatCanSeeUnpublishedEntries() throws Exception {
        // The ordinary permalink pager filters to PUBLISHED, which would show an
        // author an empty page when previewing their own draft.
        WeblogPreviewRequest request = previewRequest();
        request.setPreviewEntry("my-draft");

        assertInstanceOf(WeblogEntriesPreviewPager.class,
                previewPageModel(request).getWeblogEntriesPager("nil"),
                "A draft preview needs the pager that ignores publication status.");
    }

    @Test
    void previewingTheWholeBlogUsesTheOrdinaryLatestPager() throws Exception {
        assertInstanceOf(WeblogEntriesLatestPager.class,
                previewPageModel(previewRequest()).getWeblogEntriesPager("nil"),
                "A front-page preview shows the latest entries as visitors would.");
    }

    @Test
    void previewingAPublishedPermalinkAlsoUsesThePreviewPager() throws Exception {
        // Previewing a theme while sitting on a permalink: there is no draft
        // entry, but the URL still names one, and it must still be shown alone.
        WeblogPreviewRequest request = previewRequest();
        request.setWeblogAnchor("already-published");

        assertTrue(previewPageModel(request).isPermalink(),
                "An anchor in the URL makes the preview a permalink even with no "
                        + "draft entry named.");
        assertInstanceOf(WeblogEntriesPreviewPager.class,
                previewPageModel(request).getWeblogEntriesPager("nil"),
                "The anchor must select the single-entry pager.");

        WeblogEntry published = new WeblogEntry();
        published.setTitle("Already Published");
        published.setWebsite(weblog);
        request.setWeblogEntry(published);
        assertEquals("Already Published",
                previewPageModel(request).getWeblogEntry().getTitle(),
                "An anchor alone must be enough to expose the entry, with no draft "
                        + "named.");
    }

    @Test
    void previewPageModelRefusesInitDataWithoutAUrlStrategy() throws Exception {
        // Used to fall back to the static locator's strategy; the preview servlet
        // always supplies its own (a PreviewURLStrategy), and a missing one is
        // a caller bug rather than something to paper over.
        Map<String, Object> initData = new HashMap<>();
        initData.put("parsedRequest", previewRequest());
        initData.put("weblogger", weblogger);
        PreviewPageModel model = new PreviewPageModel();

        WebloggerException thrown = assertThrows(WebloggerException.class,
                () -> model.init(initData),
                "init() must refuse init data with no 'urlStrategy'.");
        assertTrue(thrown.getMessage().contains("urlStrategy"),
                "The failure should name what was missing; was: " + thrown.getMessage());
    }

    @Test
    void theSuppliedUrlStrategyIsTheOneTheModelBuildsLinksWith() throws Exception {
        // Characterisation: the strategy in initData is used verbatim. With the
        // fallback gone there is nothing else it could be, but this is the
        // contract the preview servlet relies on when it installs a
        // PreviewURLStrategy.
        PreviewPageModel model = previewPageModel(previewRequest());

        assertEquals(SITE + "/testblog/",
                model.getWeblogEntriesPager("nil").getHomeLink(),
                "The model must build its links with the strategy it was given.");
    }

    @Test
    void previewPageModelStillProvidesTheInheritedPageData() throws Exception {
        // PreviewPageModel overrides only three methods; everything else is
        // PageModel's and depends on super.init() having run.
        PreviewPageModel model = previewPageModel(previewRequest());

        assertEquals("testblog", model.getWeblog().getHandle(),
                "The inherited $model.weblog must work in preview mode too.");
        assertNull(model.getLocale(),
                "The inherited $model.locale must be readable rather than throwing "
                        + "on an uninitialised request.");
    }
}
