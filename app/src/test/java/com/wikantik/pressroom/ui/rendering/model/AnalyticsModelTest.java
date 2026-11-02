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

import java.util.Map;

import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.business.Weblogger;
import com.wikantik.pressroom.business.analytics.AnalyticsManager;
import com.wikantik.pressroom.business.analytics.AnalyticsStatus;
import com.wikantik.pressroom.config.WebloggerRuntimeConfig;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.pojos.wrapper.WeblogWrapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** {@code $analytics}: registrations read from memory, and the host the tracker should count. */
class AnalyticsModelTest {

    private String previousUrl;
    private AnalyticsManager manager;
    private AnalyticsModel model;

    @BeforeEach
    void setUp() throws WebloggerException {
        previousUrl = WebloggerRuntimeConfig.getAbsoluteContextURL();
        WebloggerRuntimeConfig.setAbsoluteContextURL("https://Blogs.Example.com:8443/pressroom");
        manager = mock(AnalyticsManager.class);
        when(manager.getStatus()).thenReturn(new AnalyticsStatus(true, true, 1, 1, null, null, null));
        Weblogger weblogger = mock(Weblogger.class);
        when(weblogger.getAnalyticsManager()).thenReturn(manager);
        model = new AnalyticsModel();
        model.init(Map.of("weblogger", weblogger));
    }

    @AfterEach
    void tearDown() {
        WebloggerRuntimeConfig.setAbsoluteContextURL(previousUrl);
    }

    private static WeblogWrapper blog(String id, String customDomain) {
        Weblog weblog = new Weblog();
        weblog.setId(id);
        weblog.setCustomDomain(customDomain);
        return WeblogWrapper.wrap(weblog, null, null);
    }

    @Test
    void namedAnalytics() {
        assertEquals("analytics", model.getModelName());
    }

    @Test
    void initNeedsAWeblogger() {
        assertThrows(WebloggerException.class, () -> new AnalyticsModel().init(Map.of()));
    }

    @Test
    void registeredBlogHasItsWebsiteId() {
        when(manager.isRegistered("b1")).thenReturn(true);
        when(manager.websiteIdFor("b1")).thenReturn("site-1");
        assertEquals("site-1", model.websiteId(blog("b1", null)));
    }

    @Test
    void unregisteredBlankOrMissingGivesNull() {
        assertNull(model.websiteId(blog("b1", null)));
        when(manager.isRegistered("b1")).thenReturn(true);
        assertNull(model.websiteId(blog("b1", null)), "registered but no id");
        when(manager.websiteIdFor("b1")).thenReturn(" ");
        assertNull(model.websiteId(blog("b1", null)), "blank id");
        assertNull(model.websiteId(null));
        assertNull(model.websiteId(blog(null, null)));
    }

    @Test
    void notConfiguredGivesNull() {
        when(manager.getStatus()).thenReturn(AnalyticsStatus.notConfigured());
        when(manager.isRegistered("b1")).thenReturn(true);
        when(manager.websiteIdFor("b1")).thenReturn("site-1");
        assertNull(model.websiteId(blog("b1", null)));
    }

    @Test
    void trackedHostIsTheCustomDomainElseTheSiteHost() {
        assertEquals("blogs.example.com", model.trackedHost(blog("b1", null)));
        assertEquals("my.blog.org", model.trackedHost(blog("b1", "My.Blog.org:81")));
        assertNull(model.trackedHost(null));
    }

    @Test
    void noDerivableHostGivesNull() {
        WebloggerRuntimeConfig.setAbsoluteContextURL(null);
        assertNull(model.trackedHost(blog("b1", null)));
    }
}
