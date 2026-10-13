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
package org.apache.roller.weblogger.business;

import java.util.LinkedHashMap;
import java.util.Map;

import org.apache.roller.weblogger.config.StaticConfigOverride;
import org.apache.roller.weblogger.config.WebloggerRuntimeConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The admin-UI URLs every strategy shares ({@code AbstractURLStrategy}),
 * checked through its concrete {@code MultiWeblogURLStrategy}: each one is
 * rooted at the relative context path, or at the absolute site URL when the
 * caller asks for an absolute link (mail, feeds, redirects).
 */
class AbstractURLStrategyTest {

    private final URLStrategy strategy = new MultiWeblogURLStrategy();

    private MockWeblogger runtime;
    private String previousRelative;
    private StaticConfigOverride absolute;

    @BeforeEach
    void pinContextUrls() throws Exception {
        runtime = MockWeblogger.attached();
        previousRelative = WebloggerRuntimeConfig.getRelativeContextURL();
        WebloggerRuntimeConfig.setRelativeContextURL("/ctx");
        absolute = StaticConfigOverride.set("site.absoluteurl", "https://blog.example.com/ctx");
    }

    @AfterEach
    void restore() {
        absolute.close();
        WebloggerRuntimeConfig.setRelativeContextURL(previousRelative);
        runtime.detach();
    }

    @Test
    void loginAndLogoutUrlsAreRelativeOrAbsoluteAsAsked() {
        assertEquals("/ctx/roller-ui/login-redirect.rol", strategy.getLoginURL(false));
        assertEquals("https://blog.example.com/ctx/roller-ui/login-redirect.rol", strategy.getLoginURL(true));
        assertEquals("/ctx/roller-ui/logout.rol", strategy.getLogoutURL(false));
        assertEquals("https://blog.example.com/ctx/roller-ui/logout.rol", strategy.getLogoutURL(true));
    }

    @Test
    void anActionUrlCarriesTheWeblogHandleAndCallerParametersWithTheCallersWinning() {
        Map<String, String> extra = new LinkedHashMap<>();
        extra.put("page", "2");

        assertEquals("/ctx/roller-ui/authoring/entries.rol",
                strategy.getActionURL("entries", "/roller-ui/authoring", null, null, false),
                "no handle and no parameters means no query string");
        assertEquals("/ctx/roller-ui/authoring/entries.rol?weblog=blog",
                strategy.getActionURL("entries", "/roller-ui/authoring", "blog", null, false));
        String both = strategy.getActionURL("entries", "/roller-ui/authoring", "blog", extra, true);
        assertEquals("https://blog.example.com/ctx/roller-ui/authoring/entries.rol", both.substring(0, both.indexOf('?')));
        assertTrue(both.contains("weblog=blog") && both.contains("page=2"), both);
        assertTrue(strategy.getActionURL("entries", "/roller-ui/authoring", "blog",
                Map.of("weblog", "override"), false).endsWith("?weblog=override"),
                "an explicit weblog parameter replaces the handle argument");
    }

    @Test
    void authoringLinksAreRelativeOrAbsoluteAsAsked() {
        assertEquals("/ctx/roller-ui/authoring/entryAdd.rol?weblog=blog", strategy.getEntryAddURL("blog", false));
        assertEquals("https://blog.example.com/ctx/roller-ui/authoring/entryAdd.rol?weblog=blog",
                strategy.getEntryAddURL("blog", true));

        String relative = strategy.getEntryEditURL("blog", "id-1", false);
        String absoluteUrl = strategy.getEntryEditURL("blog", "id-1", true);
        assertEquals("/ctx/roller-ui/authoring/entryEdit.rol", relative.substring(0, relative.indexOf('?')));
        assertEquals("https://blog.example.com/ctx/roller-ui/authoring/entryEdit.rol",
                absoluteUrl.substring(0, absoluteUrl.indexOf('?')));
        assertTrue(absoluteUrl.contains("weblog=blog") && absoluteUrl.contains("bean.id=id-1"), absoluteUrl);

        assertEquals("/ctx/roller-ui/authoring/weblogConfig.rol?weblog=blog",
                strategy.getWeblogConfigURL("blog", false));
        assertEquals("https://blog.example.com/ctx/roller-ui/authoring/weblogConfig.rol?weblog=blog",
                strategy.getWeblogConfigURL("blog", true));
    }
}
