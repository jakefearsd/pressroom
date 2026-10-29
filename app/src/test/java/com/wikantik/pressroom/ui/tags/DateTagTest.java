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
package com.wikantik.pressroom.ui.tags;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.Locale;
import java.util.TimeZone;

import com.wikantik.pressroom.pojos.Weblog;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockPageContext;
import org.springframework.mock.web.MockServletContext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link DateTag}, the one place the admin UI turns a
 * {@code java.util.Date} into a timestamp a reader sees.
 *
 * <p>The two halves of the element answer different questions and must not be
 * confused. {@code datetime} is machine-readable and therefore absolute -- an
 * ISO-8601 instant in UTC, the same string whoever is looking -- while the
 * text is the wall clock a human is expected to reconcile against their own,
 * which for this application means <em>the weblog's</em> timezone, not the
 * server's and not the browser's. An author in Berlin editing a weblog set to
 * America/New_York schedules posts against New York's clock (see
 * {@code EntryEditController}'s pubTime handling), so a list that showed
 * Berlin time would disagree with the field the author typed into.
 */
class DateTagTest {

    /** 2026-09-09T14:30:00Z, chosen so every timezone under test lands on a different date-time. */
    private static final Date WHEN = utc(2026, 9, 9, 14, 30);

    private static Date utc(int year, int month, int day, int hour, int minute) {
        Calendar cal = new GregorianCalendar(TimeZone.getTimeZone("UTC"));
        cal.clear();
        cal.set(year, month - 1, day, hour, minute, 0);
        return cal.getTime();
    }

    private static Weblog weblogIn(String timeZoneId) {
        Weblog weblog = new Weblog();
        weblog.setHandle("testblog");
        weblog.setTimeZone(timeZoneId);
        return weblog;
    }

    private static String render(Object actionWeblog, Date value, String pattern) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        if (actionWeblog != null) {
            request.setAttribute("actionWeblog", actionWeblog);
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockPageContext context = new MockPageContext(new MockServletContext(), request, response);

        DateTag tag = new DateTag();
        tag.setJspContext(context);
        tag.setValue(value);
        if (pattern != null) {
            tag.setPattern(pattern);
        }
        tag.doTag();
        context.getOut().flush();
        return response.getContentAsString();
    }

    @Test
    void theDatetimeAttributeIsAnAbsoluteIsoInstantAndTheTextIsTheWeblogsWallClock() throws Exception {
        assertEquals("<time datetime=\"2026-09-09T14:30:00Z\" class=\"data\">2026-09-09 14:30</time>",
                render(weblogIn("UTC"), WHEN, null));
    }

    /**
     * Same instant, a weblog nine hours ahead: the machine-readable half must
     * not move, the human-readable half must.
     */
    @Test
    void theWeblogsTimeZoneDecidesTheDisplayedWallClock() throws Exception {
        assertEquals("<time datetime=\"2026-09-09T14:30:00Z\" class=\"data\">2026-09-09 23:30</time>",
                render(weblogIn("Asia/Tokyo"), WHEN, null));
    }

    @Test
    void withNoActionWeblogOnTheRequestTheJvmDefaultZoneIsUsed() throws Exception {
        // Locale.ROOT, matching DateTag: with the JVM default locale this
        // expectation would drift from the tag on a non-Gregorian calendar
        // locale (th-TH, ja-JP-u-ca-japanese), where the same instant
        // formats to a different YEAR.
        SimpleDateFormat expected = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT);
        expected.setTimeZone(TimeZone.getDefault());

        assertEquals("<time datetime=\"2026-09-09T14:30:00Z\" class=\"data\">"
                        + expected.format(WHEN) + " " + jvmZoneLabel() + "</time>",
                render(null, WHEN, null));
    }

    /**
     * With no weblog to lend its clock (API tokens, Users, Account), the
     * text is the JVM's zone, UTC in the container image, and a bare
     * "14:05" reads as the reader's own local time. So the zone is named.
     * A weblog's own zone is not: the page is about that weblog, and its
     * settings say which zone it keeps.
     */
    @Test
    void aTimeInTheServersZoneSaysWhichZoneItIs() throws Exception {
        String rendered = render(null, WHEN, null);
        String label = jvmZoneLabel();
        assertTrue(rendered.endsWith(" " + label + "</time>"),
                "expected the zone named after the time: " + rendered);
        assertTrue(render(weblogIn("Asia/Tokyo"), WHEN, null).endsWith("23:30</time>"),
                "a weblog's own zone is not appended");
        Weblog noZone = new Weblog();
        assertTrue(render(noZone, WHEN, null).endsWith(" " + label + "</time>"),
                "a weblog with no zone of its own falls back to the server's, and says so");
    }

    private static String jvmZoneLabel() {
        return DateTag.zoneLabel(TimeZone.getDefault().toZoneId(), WHEN.toInstant());
    }

    /** A short name, not "GMT-07:00": Locale.ROOT has no zone names. */
    @Test
    void theZoneIsNamedShortly() {
        assertEquals("UTC", DateTag.zoneLabel(java.time.ZoneId.of("UTC"), WHEN.toInstant()));
        assertEquals("PDT", DateTag.zoneLabel(java.time.ZoneId.of("America/Los_Angeles"), WHEN.toInstant()));
    }

    /**
     * The request attribute is set by {@code PressroomHandlerInterceptor} and is
     * a {@code Weblog} on every admin screen -- but a tag that renders a date
     * must degrade rather than throw if it is ever something else, because the
     * date is never the reason anyone opened the page.
     */
    @Test
    void anActionWeblogAttributeThatIsNotAWeblogFallsBackInsteadOfThrowing() throws Exception {
        // Locale.ROOT, matching DateTag: with the JVM default locale this
        // expectation would drift from the tag on a non-Gregorian calendar
        // locale (th-TH, ja-JP-u-ca-japanese), where the same instant
        // formats to a different YEAR.
        SimpleDateFormat expected = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT);
        expected.setTimeZone(TimeZone.getDefault());

        assertEquals("<time datetime=\"2026-09-09T14:30:00Z\" class=\"data\">"
                        + expected.format(WHEN) + " " + jvmZoneLabel() + "</time>",
                render("not a weblog", WHEN, null));
    }

    @Test
    void anExplicitPatternOverridesTheDefaultDisplayFormat() throws Exception {
        assertEquals("<time datetime=\"2026-09-09T14:30:00Z\" class=\"data\">09 Sep 2026</time>",
                render(weblogIn("UTC"), WHEN, "dd MMM yyyy"));
    }

    /**
     * A null date renders nothing at all -- no empty {@code <time>} element.
     * Every caller in this codebase used to wrap the format call in a
     * {@code <c:if test="${x != null}">}; the tag owning that check is what
     * lets those disappear.
     */
    @Test
    void aNullValueRendersNothing() throws Exception {
        assertEquals("", render(weblogIn("UTC"), null, null));
    }
}
