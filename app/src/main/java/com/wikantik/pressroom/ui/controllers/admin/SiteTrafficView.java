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
package com.wikantik.pressroom.ui.controllers.admin;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

import com.wikantik.pressroom.business.analytics.SiteTraffic;
import com.wikantik.pressroom.business.analytics.TrafficState;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.ui.controllers.editor.TrafficView;
import com.wikantik.pressroom.ui.controllers.editor.TrafficView.PostLine;
import com.wikantik.pressroom.ui.controllers.editor.TrafficView.Row;

/**
 * Everything the Global Traffic page shows, worked out from one
 * {@link SiteTraffic} so that the JSP has only to print it. The formatting,
 * the em dash rule and the post merge are {@link TrafficView}'s.
 *
 * <p>There is no visitor total: each blog is its own Umami website, so a sum
 * would count a reader of two blogs twice. Views and visits add.
 *
 * @param period      the period shown, one of {@link TrafficView#PERIODS}
 * @param periods     the periods the switch offers
 * @param noticeKey   the bundle key of the state message, or empty
 * @param ageMinutes  for a stale snapshot, how long ago it was read
 * @param asOfText    when the oldest snapshot was read, as HH:mm, or null
 * @param showBody    whether there are numbers to show
 * @param totalViews  views across the blogs, formatted
 * @param totalVisits visits across the blogs, formatted
 * @param blogs       one line per blog with a snapshot
 * @param posts       the site's top posts, merged by entry
 * @param sources     the site's top sources
 */
public record SiteTrafficView(String period, List<String> periods, String noticeKey, long ageMinutes,
                              String asOfText, boolean showBody, String totalViews, String totalVisits,
                              List<BlogLine> blogs, List<PostLine> posts, List<Row> sources) {

    /**
     * One blog. {@code name} is author text and raw (the JSP escapes it);
     * {@code topPostTitle} is stored escaped and goes out bare.
     */
    public record BlogLine(String handle, String name, boolean siteSpace, String visitors, String views,
                           String visits, String change, String changeClass, String topPostTitle) {
    }

    /** Builds the view for an already accepted {@code period}. */
    static SiteTrafficView of(SiteTraffic site, String period, Locale locale, ZoneId zone, Instant now) {
        TrafficState state = site.state();
        boolean showBody = state != TrafficState.NO_DATA && state != TrafficState.NOT_CONFIGURED
                && !site.blogs().isEmpty();
        Instant asOf = site.asOf();
        long age = state == TrafficState.STALE && asOf != null
                ? Math.max(0, Duration.between(asOf, now).toMinutes()) : 0;
        boolean allZero = site.totalViews() == 0 && site.totalVisits() == 0
                && site.blogs().stream().allMatch(b -> b.visitors() == 0);
        List<BlogLine> blogs = showBody
                ? site.blogs().stream().map(b -> new BlogLine(b.handle(), b.name(),
                        Weblog.SITE_SPACE_HANDLE.equals(b.handle()),
                        TrafficView.number(b.visitors(), locale), TrafficView.number(b.views(), locale),
                        TrafficView.number(b.visits(), locale),
                        TrafficView.changeText(b.change()), TrafficView.changeClass(b.change()),
                        b.topPostTitle())).toList()
                : List.of();
        return new SiteTrafficView(period, TrafficView.PERIODS,
                TrafficView.noticeKey(state, allZero, "traffic.state.siteZero"), age,
                asOf == null ? null : DateTimeFormatter.ofPattern("HH:mm").withZone(zone).format(asOf),
                showBody, TrafficView.number(site.totalViews(), locale),
                TrafficView.number(site.totalVisits(), locale), blogs,
                showBody ? TrafficView.posts(site.topPosts(), "views") : List.of(),
                showBody ? TrafficView.rows("sources", site.topSources(), locale) : List.of());
    }
}
