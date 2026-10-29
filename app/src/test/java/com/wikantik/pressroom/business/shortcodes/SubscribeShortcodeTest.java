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
package com.wikantik.pressroom.business.shortcodes;

import java.util.Map;

import com.wikantik.pressroom.config.WebloggerRuntimeConfig;
import com.wikantik.pressroom.pojos.Weblog;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * [subscribe] emits a placeholder div, never a form: the sanitizer strips
 * form elements from authored content by design, and #showAudienceAssets
 * builds the real form client-side. Same pattern as [contact], [map] and
 * [video].
 */
class SubscribeShortcodeTest {

    private static final String VALID_UUID = "2f0f1b0c-1111-2222-3333-444455556666";

    private final SubscribeShortcode shortcode = new SubscribeShortcode();

    private String previousRelativeContextURL;

    @BeforeEach
    void setUp() {
        // The endpoint the shortcode emits is built from this -- pin it to a
        // known, non-empty value so the assertions below do not depend on
        // whatever some earlier-running test left behind in this shared
        // static field (see ContactShortcodeTest/URLModelTest for the same
        // discipline).
        previousRelativeContextURL = WebloggerRuntimeConfig.getRelativeContextURL();
        WebloggerRuntimeConfig.setRelativeContextURL("/roller");
    }

    @AfterEach
    void tearDown() {
        WebloggerRuntimeConfig.setRelativeContextURL(previousRelativeContextURL);
    }

    private static ShortcodeContext context(Weblog weblog) {
        return new ShortcodeContext() {
            @Override public Weblog getWeblog() { return weblog; }
            @Override public String getSlug() { return "subscribe"; }
            @Override public String getRawText() { return "[subscribe]"; }
        };
    }

    private static Weblog weblogWithUuid(String uuid) {
        Weblog weblog = new Weblog();
        weblog.setNewsletterListUuid(uuid);
        return weblog;
    }

    @Test
    void emitsAPlaceholderCarryingTheListUuid() {
        String html = shortcode.render(Map.of(), null, context(weblogWithUuid(VALID_UUID)));

        assertTrue(html.contains("class=\"subscribe-form-slot\""), html);
        assertTrue(html.contains("data-list-uuid=\"" + VALID_UUID + "\""), html);
        assertFalse(html.contains("<form"), "the macro injects the form, not the shortcode");
        assertFalse(html.contains("<input"), html);
    }

    /**
     * Same reasoning as {@code ContactShortcodeTest.theEndpointCarriesTheContextPathServerSide}:
     * an absolute-root {@code /newsletter/subscribe} 404s under any non-root
     * context path, so the endpoint is built here, server-side, from the
     * context path {@code InitFilter} published -- never guessed by the
     * client script.
     */
    @Test
    void theEndpointCarriesTheContextPathServerSide() {
        String html = shortcode.render(Map.of(), null, context(weblogWithUuid(VALID_UUID)));

        assertTrue(html.contains("data-endpoint=\"/roller/newsletter/subscribe\""), html);
    }

    @Test
    void withoutAWeblogItLeavesTheAuthorsTextVisible() {
        assertNull(shortcode.render(Map.of(), null, context(null)));
    }

    @Test
    void withABlankUuidItLeavesTheAuthorsTextVisible() {
        assertNull(shortcode.render(Map.of(), null, context(weblogWithUuid(null))));
        assertNull(shortcode.render(Map.of(), null, context(weblogWithUuid(""))));
    }

    @Test
    void withAMalformedUuidItRendersNothingRatherThanJunk() {
        assertNull(shortcode.render(Map.of(), null, context(weblogWithUuid("not-a-uuid"))));
        assertNull(shortcode.render(Map.of(), null,
                context(weblogWithUuid("2f0f1b0c-1111-2222-3333-4444'; DROP TABLE x;--"))));
    }

    @Test
    void theCardIsDiscoverable() {
        ShortcodeCard card = shortcode.getCard();
        assertEquals("subscribe", card.name());
        assertTrue(card.snippet().startsWith("[subscribe"), card.snippet());
        assertFalse(card.snippet().contains("<"));
    }

    @Test
    void bothHandlersAreRegisteredInTheDefaultExpander() {
        assertTrue(BuiltInExpanders.withMocks().cards().stream()
                .anyMatch(c -> "contact".equals(c.name())));
        assertTrue(BuiltInExpanders.withMocks().cards().stream()
                .anyMatch(c -> "subscribe".equals(c.name())));
    }
}
