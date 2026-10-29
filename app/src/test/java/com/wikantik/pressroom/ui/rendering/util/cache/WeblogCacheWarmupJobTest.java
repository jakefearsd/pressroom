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
package com.wikantik.pressroom.ui.rendering.util.cache;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.business.URLStrategy;
import com.wikantik.pressroom.business.Weblogger;
import com.wikantik.pressroom.ui.rendering.model.ModelLoader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * Tests how the (experimental) cache warm-up job reads its inputs.
 *
 * The job renders feeds ahead of anyone asking for them, so it is driven
 * entirely by a Map handed to it by whatever scheduled it. Everything in that
 * Map is optional and untyped, and a job that throws on the way in takes the
 * scheduler thread with it. The rendering itself needs the whole rendering
 * stack and is not exercised here.
 */
public class WeblogCacheWarmupJobTest {

    private Weblogger weblogger;
    private URLStrategy urlStrategy;
    private WeblogCacheWarmupJob job;

    @BeforeEach
    public void createTheJob() {
        weblogger = mock(Weblogger.class);
        urlStrategy = mock(URLStrategy.class);
        when(weblogger.getUrlStrategy()).thenReturn(urlStrategy);
        job = new WeblogCacheWarmupJob(weblogger);
    }

    @Test
    public void aJobWithNoInputDoesNothing() {
        // input() may simply never be called
        job.execute();

        assertEquals(Map.of(), job.output(), "This job reports nothing back to its scheduler");
    }

    @Test
    public void aJobWithNoWeblogsDoesNothing() {
        Map<String, Object> inputs = new HashMap<>();
        inputs.put("feed-entries-atom", "true");
        job.input(inputs);

        // no "weblogs" key at all: nothing to warm up, and nothing to throw over
        job.execute();

        assertEquals(Map.of(), job.output(), "This job reports nothing back to its scheduler");
    }

    @Test
    public void weblogsWithoutAFormatAreNotRendered() {
        Map<String, Object> inputs = new HashMap<>();
        inputs.put("weblogs", List.of("myblog"));
        job.input(inputs);

        // feed-entries-atom was not asked for, so nothing must be rendered
        // for the weblog that was named. (RSS feeds are gone -- W2 -- so
        // "feed-entries-rss" is no longer a recognized input at all.)
        job.execute();

        assertNull(WeblogFeedCache.getInstance().get("cache.weblogfeed:myblog/entries/atom", 0L),
                "Naming a weblog is not on its own an instruction to render anything");
    }

    @Test
    public void oneWeblogFailingToRenderDoesNotAbortTheWarmup() {
        // The job is scheduled, so it runs unattended against whatever weblogs
        // it is handed. A weblog that cannot be rendered -- deleted since the
        // schedule was written, or broken template, or (as here) no rendering
        // stack behind it at all -- must be logged and stepped over, not
        // allowed to take the rest of the batch with it. The loop's try/catch
        // is the whole of that guarantee.
        Map<String, Object> inputs = new HashMap<>();
        inputs.put("weblogs", List.of("first-blog", "second-blog"));
        inputs.put("feed-entries-atom", "true");
        job.input(inputs);

        assertDoesNotThrow(job::execute,
                "A weblog that fails to render must not escape the job. If this throws, a "
                        + "single bad weblog silently kills every scheduled warmup after it");

        assertEquals(Map.of(), job.output(),
                "and the job still reports normally to its scheduler afterwards");

        // nothing was rendered, so nothing may have been cached under either
        // handle -- a half-rendered feed served to readers would be worse than
        // no warmup at all
        assertNull(WeblogFeedCache.getInstance()
                        .get("cache.weblogfeed:first-blog/entries/atom", 0L),
                "A weblog whose render failed must leave nothing behind in the feed cache");
        assertNull(WeblogFeedCache.getInstance()
                        .get("cache.weblogfeed:second-blog/entries/atom", 0L),
                "and the same for the weblog after it, which the job must still have tried");
    }

    @Test
    public void anEmptyWeblogListIsAcceptedForEveryFormat() {
        Map<String, Object> inputs = new HashMap<>();
        inputs.put("weblogs", List.of());
        inputs.put("feed-entries-atom", "true");
        job.input(inputs);

        // atom requested, no weblogs to do it for
        job.execute();

        assertEquals(Map.of(), job.output(), "This job reports nothing back to its scheduler");
    }

    /**
     * The rendering models are reflectively instantiated and receive the
     * business-tier facade and the url strategy through {@code initData}; this
     * job was the one caller that supplied neither and leaned on a fallback
     * to the static locator that no longer exists (plan Task 10). Observed at
     * the loader, because everything past it needs a Velocity engine.
     */
    @Test
    @SuppressWarnings("unchecked")
    public void theModelsAreHandedTheFacadeAndItsUrlStrategy() {
        Map<String, Object> inputs = new HashMap<>();
        inputs.put("weblogs", List.of("myblog"));
        inputs.put("feed-entries-atom", "true");
        job.input(inputs);

        try (MockedStatic<ModelLoader> loader = mockStatic(ModelLoader.class)) {
            // Stop the job right after the loader: the next step is
            // RendererManager, whose static initialiser needs a rendering
            // runtime this test deliberately does not have. A failing render
            // is exactly what the job's per-weblog try/catch is for.
            loader.when(() -> ModelLoader.loadModels(any(), any(), any(), anyBoolean()))
                    .thenThrow(new WebloggerException("stop at the loader"));

            job.execute();

            ArgumentCaptor<Map<String, Object>> initData = ArgumentCaptor.forClass(Map.class);
            loader.verify(() -> ModelLoader.loadModels(any(), any(), initData.capture(), anyBoolean()));
            assertSame(weblogger, initData.getValue().get("weblogger"),
                    "The job must pass the facade it was constructed with");
            assertSame(urlStrategy, initData.getValue().get("urlStrategy"),
                    "and that facade's url strategy -- the models no longer fall back to one");
        }
    }
}
