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
package com.wikantik.pressroom.ui.controllers.editor;

import com.wikantik.pressroom.business.analytics.TrafficState;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The request-value and formatting rules behind the Traffic page, called
 * directly: a value the page does not offer falls back to the default
 * rather than reaching a query, and a link is offered only to an http(s)
 * dashboard.
 */
class TrafficViewHelpersTest {

    @Test
    void anOfferedValueIsKeptAndAnythingElseFallsBackToTheDefault() {
        assertEquals("7d", TrafficView.periodOf("7d"));
        assertEquals("30d", TrafficView.periodOf(null));
        assertEquals("30d", TrafficView.periodOf("1y"));

        assertEquals("posts", TrafficView.tabOf(null));
        assertEquals("posts", TrafficView.tabOf("'; drop"));

        assertEquals("trend", TrafficView.sortOf("trend"));
        assertEquals("views", TrafficView.sortOf(null));
        assertEquals("views", TrafficView.sortOf("pubTime"));
    }

    @Test
    void theDashboardLinkNeedsAnHttpBaseAndAWebsite() {
        assertNull(TrafficView.umamiUrl(null, "w1"));
        assertNull(TrafficView.umamiUrl("https://stats.example.com", null));
        assertNull(TrafficView.umamiUrl("https://stats.example.com", " "));
        assertNull(TrafficView.umamiUrl("javascript:alert(1)//", "w1"), "never a script link");
        assertNull(TrafficView.umamiUrl("ftp://stats.example.com", "w1"));
        assertEquals("HTTP://stats.example.com/websites/w1", TrafficView.umamiUrl(" HTTP://stats.example.com// ", "w1"),
                "the scheme is matched in any case, the base trimmed of space and trailing slashes");
    }

    @Test
    void aChangeThatIsNotANumberShowsADashAndNoTrend() {
        assertEquals(TrafficView.DASH, TrafficView.changeText(Double.NaN));
        assertEquals(TrafficView.DASH, TrafficView.changeText(Double.POSITIVE_INFINITY));
        assertEquals("", TrafficView.changeClass(Double.NaN));
        assertEquals("", TrafficView.changeClass(Double.NEGATIVE_INFINITY));
        assertEquals("+25%", TrafficView.changeText(0.25));
        assertEquals("-10%", TrafficView.changeText(-0.1));
        assertEquals("0%", TrafficView.changeText(0.0));
        assertEquals("", TrafficView.changeClass(0.001), "a change that rounds to nothing has no trend");
    }

    @Test
    void eachStateHasItsNoticeAndAnAllZeroOkUsesTheZeroText() {
        assertEquals("traffic.state.notConfigured", TrafficView.noticeKey(TrafficState.NOT_CONFIGURED, true, "z"));
        assertEquals("traffic.state.noData", TrafficView.noticeKey(TrafficState.NO_DATA, false, "z"));
        assertEquals("traffic.state.stale", TrafficView.noticeKey(TrafficState.STALE, true, "z"));
        assertEquals("z", TrafficView.noticeKey(TrafficState.OK, true, "z"));
        assertEquals("", TrafficView.noticeKey(TrafficState.OK, false, "z"));
    }
}
