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
package org.apache.roller.weblogger.ui.tags;

import java.io.IOException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

import jakarta.servlet.jsp.JspException;
import jakarta.servlet.jsp.PageContext;
import jakarta.servlet.jsp.tagext.SimpleTagSupport;

import org.apache.commons.text.StringEscapeUtils;
import org.apache.roller.weblogger.pojos.Weblog;

/**
 * The admin UI's one timestamp renderer: {@code <rc:date value="${x}"/>} emits
 * {@code <time datetime="<ISO instant>" class="data">yyyy-MM-dd HH:mm</time>}.
 *
 * <p><strong>The two halves answer different questions and neither is
 * optional.</strong> {@code datetime} is machine-readable, so it is an
 * absolute ISO-8601 instant in UTC -- the same characters for every reader,
 * which is what makes the element quotable, sortable and copy-pasteable
 * without a timezone argument attached. The text is the wall clock a human
 * reconciles against their own, and on this application that clock belongs to
 * <em>the weblog</em>: an entry's pubtime has always been interpreted in the
 * weblog's timezone (see {@code EntryEditController}, which parses
 * {@code bean.pubTimeLocal} against {@code getTimeZoneInstance()}), so a list
 * rendered in the server's zone would show a different time than the field the
 * author typed into.
 *
 * <p>This replaced two idioms that were each wrong in their own way.
 * {@code <fmt:formatDate>} formats in the JVM default zone -- the server's,
 * which is nobody's clock in particular -- and emits no machine-readable half
 * at all. {@code <spring:message code="weblogEntryQuery.date.toStringFormat"
 * arguments="${d}"/>} routed a timestamp through the message bundle, which
 * made the display format a translatable string and the timezone the request
 * locale's.
 *
 * <p>The display pattern is deliberately locale-independent ({@link
 * Locale#ROOT} and a fixed numeric pattern). These are data cells in the mono
 * tabular face, where a column of timestamps has to line up and sort by eye;
 * that is a different job from prose, and a localized date format does it
 * worse rather than better.
 *
 * <p>On a page with no action weblog (API tokens, Users, Account) the text
 * is in the JVM's zone, which is nobody's clock in particular, so the zone's
 * short name follows the time ({@code 2026-10-22 14:05 UTC}). A weblog's own
 * zone is not named: the page is about that weblog.
 *
 * <p>A null value renders <em>nothing</em> -- not an empty {@code <time>}.
 * Every caller used to wrap the format call in its own
 * {@code <c:if test="${x != null}">}; the tag owning that check is what let
 * those disappear.
 */
public class DateTag extends SimpleTagSupport {

    /**
     * ISO-ish and sortable, matching the {@code .data} face's tabular numerals.
     * Not a translated value: see the class comment.
     */
    static final String DEFAULT_PATTERN = "yyyy-MM-dd HH:mm";

    private Date value;
    private String pattern;

    public void setValue(Date value) {
        this.value = value == null ? null : new Date(value.getTime());
    }

    public void setPattern(String pattern) {
        this.pattern = pattern;
    }

    @Override
    public void doTag() throws JspException, IOException {
        if (value == null) {
            return;
        }
        Instant instant = value.toInstant();
        ZoneId weblogZone = weblogZone();
        ZoneId zone = weblogZone == null ? TimeZone.getDefault().toZoneId() : weblogZone;
        String display = DateTimeFormatter
                .ofPattern(pattern == null ? DEFAULT_PATTERN : pattern, Locale.ROOT)
                .withZone(zone)
                .format(instant);
        if (weblogZone == null) {
            // Nobody's clock in particular (UTC in the container image): a
            // bare "14:05" would read as the reader's own local time.
            display = display + " " + zoneLabel(zone, instant);
        }

        getJspContext().getOut().write("<time datetime=\""
                + DateTimeFormatter.ISO_INSTANT.format(instant)
                + "\" class=\"data\">"
                // The pattern is developer-authored and the formatted result is
                // ASCII digits and separators, so this escape has nothing to do
                // today. It stays because "this string can never contain markup"
                // is an assumption a future pattern is free to break silently.
                + StringEscapeUtils.escapeHtml4(display)
                + "</time>");
    }

    /**
     * The zone's short name at that instant, in English ("UTC", "PDT"):
     * Locale.ROOT has no names and prints "GMT-07:00", too long for a table
     * cell.
     */
    static String zoneLabel(ZoneId zone, Instant instant) {
        return DateTimeFormatter.ofPattern("zzz", Locale.ENGLISH).withZone(zone).format(instant);
    }

    /**
     * The action weblog's zone when the request has one, else null: the
     * caller then uses the JVM default and names it after the time.
     *
     * <p>Note it reads {@code getTimeZone()} before calling
     * {@code getTimeZoneInstance()}. That accessor <em>writes</em> when the
     * column is null ({@code roller_weblog.timezone} is nullable), which on a
     * managed entity during view rendering would dirty the weblog and let the
     * persistence session flush a column nobody asked to change. The guard
     * falls to the identical zone that accessor would have -- the JVM default
     * -- without the write.
     */
    private ZoneId weblogZone() {
        if (getJspContext() instanceof PageContext pageContext) {
            Object attribute = pageContext.getRequest().getAttribute("actionWeblog");
            if (attribute instanceof Weblog weblog && weblog.getTimeZone() != null) {
                return weblog.getTimeZoneInstance().toZoneId();
            }
        }
        return null;
    }
}
