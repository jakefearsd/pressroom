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

package com.wikantik.pressroom.ui;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import jakarta.el.ELProcessor;
import org.junit.jupiter.api.Test;

import com.wikantik.pressroom.ui.controllers.editor.TrafficView;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Source scan for the Traffic page's JSPs. The numbers are worked out in
 * {@link TrafficView}; these pin what the markup does with them: the page's
 * marker, no script or inline style (the admin CSP stays as it is), escaping
 * of what an author controls, and every message code present in the bundle.
 */
class TrafficJspTest {

    private static final Path JSPS = Path.of("src/main/webapp/WEB-INF/jsps/editor");
    private static final Path PAGE = JSPS.resolve("Traffic.jsp");
    private static final Path BODY = JSPS.resolve("TrafficBody.jsp");
    private static final Path HEADER = JSPS.resolve("TrafficHeader.jsp");
    private static final Path BUNDLE = Path.of("src/main/resources/ApplicationResources.properties");

    /** The JSP without its {@code <%-- --%>} comments, which describe things by name. */
    private static String read(Path path) throws IOException {
        return Files.readString(path, StandardCharsets.UTF_8).replaceAll("(?s)<%--.*?--%>", "");
    }

    @Test
    void thePageCarriesItsMarkerAndIncludesTheBody() throws IOException {
        String page = read(PAGE);
        assertTrue(page.contains("<div id=\"trafficPage\""), "the marker Routes pins");
        assertTrue(page.contains("TrafficBody.jsp"), "the fragment the Global page reuses");
    }

    @Test
    void noScriptNoHandlersNoInlineStyle() throws IOException {
        for (Path jsp : List.of(PAGE, BODY, HEADER)) {
            String src = read(jsp);
            assertFalse(src.contains("<script"), jsp + " carries a script");
            assertFalse(src.toLowerCase(java.util.Locale.ROOT).contains("onclick"), jsp + " has a handler");
            assertFalse(Pattern.compile("\\sstyle=").matcher(src).find(), jsp + " has an inline style");
        }
    }

    @Test
    void theChartIsAnInlineSvgDrawnFromTheModel() throws IOException {
        String src = read(BODY);
        assertTrue(src.contains("<svg"));
        assertTrue(src.contains("role=\"img\""));
        assertTrue(src.contains("<c:forEach items=\"${traffic.chart.bars}\""));
        assertTrue(src.contains("<title>${fn:escapeXml(bar.title)}</title>"));
    }

    @Test
    void postTitlesAreBareAndPathsAreEscaped() throws IOException {
        String src = read(BODY);
        // stored escaped, like every entry title; escaping again shows &amp;
        assertTrue(src.contains("${line.title}"), "entry titles go out as stored");
        assertTrue(src.contains("${fn:escapeXml(line.path)}"), "a raw path is escaped");
        assertFalse(Pattern.compile("\\$\\{\\s*line\\.path\\s*\\}").matcher(src).find());
        assertTrue(src.contains("/pressroom-ui/authoring/entryEdit.rol"));
        assertTrue(src.contains("name=\"bean.id\""));
    }

    /** Ruling R23: the Posts tab says when it shows only the top N paths. */
    @Test
    void aCappedPathListIsNoted() throws IOException {
        String src = read(BODY);
        assertTrue(src.contains("<c:if test=\"${traffic.pathsCappedAt gt 0}\">"), src);
        assertTrue(src.contains("code=\"traffic.posts.capped\" arguments=\"${[traffic.pathsCappedAt]}\""));
    }

    @Test
    void theBlogNameIsEscapedAndEveryAuthorValueGoesThroughEscapeXml() throws IOException {
        String all = read(PAGE) + read(BODY);
        assertFalse(Pattern.compile("\\$\\{[^}]*actionWeblog\\.name[^}]*\\}").matcher(all
                .replace("fn:escapeXml(actionWeblog.name)", "")).find(), "raw blog name");
        assertTrue(all.contains("fn:escapeXml(actionWeblog.name)"));
        assertTrue(all.contains("${fn:escapeXml(traffic.umamiUrl)}"));
        // breakdown labels come from visitors' browsers: referrers, campaigns, events
        assertTrue(all.contains("${fn:escapeXml(row.label)}"));
        // (the speed rows' label is a fixed metric name, used once as a message code, never printed)
        assertFalse(Pattern.compile("\\$\\{\\s*row\\.label\\s*\\}").matcher(
                all.replace("code=\"traffic.speed.${row.label}\"", "")).find());
    }

    @Test
    void theUmamiLinkOnlyShowsWhenTheModelHasOne() throws IOException {
        String src = read(BODY);
        int at = src.indexOf("${fn:escapeXml(traffic.umamiUrl)}");
        String before = src.substring(0, at);
        assertTrue(before.lastIndexOf("<c:if test=\"${not empty traffic.umamiUrl}\">") > before.lastIndexOf("</c:if>"),
                "the Umami link must sit inside the not-empty guard");
        assertTrue(src.substring(at, src.indexOf("</a>", at)).length() > 0);
        assertTrue(src.contains("rel=\"noopener\""));
    }

    @Test
    void everyStateHasItsOwnNoticeAndTheNoticeIsPickedFromTheModel() throws IOException {
        String src = read(HEADER);
        assertTrue(src.contains("code=\"${traffic.noticeKey}\""));
        Properties bundle = bundle();
        for (String key : List.of("traffic.state.notConfigured", "traffic.state.noData",
                "traffic.state.stale", "traffic.state.zero")) {
            assertTrue(bundle.containsKey(key), key);
        }
        assertEquals(4, List.of(bundle.getProperty("traffic.state.notConfigured"),
                bundle.getProperty("traffic.state.noData"), bundle.getProperty("traffic.state.stale"),
                bundle.getProperty("traffic.state.zero")).stream().distinct().count());
    }

    @Test
    void eventsAreCountedInVisitorsAsUmamiReportsThem() throws IOException {
        assertEquals("Visitors", bundle().getProperty("traffic.column.value.events"));
    }

    @Test
    void aGroupHeaderIsOnlyDrawnForARowThatHasAGroup() throws IOException {
        String src = read(BODY);
        int at = src.indexOf("code=\"traffic.group.${row.group}\"");
        String before = src.substring(0, at);
        String guard = before.substring(before.lastIndexOf("<c:if"));
        assertTrue(guard.contains("not empty row.group"), guard);
    }

    @Test
    void theStaleAgeAgreesWithItsNumber() throws IOException {
        String msg = bundle().getProperty("traffic.state.stale");
        assertTrue(java.text.MessageFormat.format(msg, 1L).contains("1 minute ago"), msg);
        assertTrue(java.text.MessageFormat.format(msg, 30L).contains("30 minutes ago"), msg);
    }

    @Test
    void theFreshnessLineGivesTheTimeAndTheRefreshInterval() throws IOException {
        String line = bundle().getProperty("traffic.asOf");
        assertTrue(line.contains("{0}") && line.contains("15 minutes"), line);
        assertTrue(read(BODY).contains("code=\"traffic.asOf\""));
    }

    @Test
    void tabAndPeriodLinksAreBuiltWithCUrlSoNoContextPathIsAssumed() throws IOException {
        String src = read(BODY) + read(HEADER);
        assertTrue(src.contains("<c:url var=\"tabUrl\""));
        assertTrue(src.contains("<c:url var=\"periodUrl\""));
        assertTrue(src.contains("<c:url var=\"sortUrl\""));
        assertFalse(src.contains("href=\"/"), "a hard-coded absolute href");
    }

    @Test
    void everyMessageCodeTheJspsNameIsInTheBundle() throws IOException {
        Properties bundle = bundle();
        List<String> missing = new ArrayList<>();
        for (Path jsp : List.of(PAGE, BODY, HEADER)) {
            Matcher m = Pattern.compile("code=\"([a-zA-Z0-9_.]+)\"").matcher(read(jsp));
            while (m.find()) {
                if (!bundle.containsKey(m.group(1))) {
                    missing.add(m.group(1));
                }
            }
        }
        assertTrue(missing.isEmpty(), "missing from ApplicationResources.properties: " + missing);
        for (String tab : List.of("posts", "sources", "places", "devices", "events", "campaigns", "speed")) {
            assertTrue(bundle.containsKey("traffic.tab." + tab), tab);
        }
        for (String p : List.of("24h", "7d", "30d", "90d")) {
            assertTrue(bundle.containsKey("traffic.period." + p), p);
        }
        for (String s : List.of("lcp", "inp", "fcp", "ttfb", "cls")) {
            assertTrue(bundle.containsKey("traffic.speed." + s), s);
        }
        for (String g : List.of("channel", "device", "browser", "os", "utm_source", "utm_medium",
                "utm_campaign", "utm_content", "utm_term")) {
            assertTrue(bundle.containsKey("traffic.group." + g), g);
        }
    }

    /** The keys the JSP builds from a prefix and a value are all listed whole, and all in the bundle. */
    @Test
    void theDynamicallyBuiltMessageKeysAreListedAndPresent() throws IOException {
        Properties bundle = bundle();
        List<String> expected = new ArrayList<>();
        for (String tab : TrafficView.TABS) {
            expected.add("traffic.tab." + tab);
            if (!"posts".equals(tab)) {
                expected.add("traffic.column.name." + tab);
                expected.add("traffic.column.value." + tab);
            }
        }
        for (String p : TrafficView.PERIODS) {
            expected.add("traffic.period." + p);
        }
        for (String c : List.of("views", "visitors", "trend")) {
            expected.add("traffic.column." + c);
        }
        for (String s : List.of("lcp", "inp", "fcp", "ttfb", "cls")) {
            expected.add("traffic.speed." + s);
        }
        for (String g : List.of("channel", "device", "browser", "os", "utm_source", "utm_medium",
                "utm_campaign", "utm_content", "utm_term")) {
            expected.add("traffic.group." + g);
        }
        assertEquals(expected.stream().sorted().toList(),
                TrafficView.DYNAMIC_MESSAGE_KEYS.stream().sorted().toList());
        for (String key : expected) {
            assertTrue(bundle.containsKey(key), key);
        }
    }

    /** The JSPs read the view model's records by accessor name; EL 6 must resolve those. */
    @Test
    void elReadsRecordComponents() {
        ELProcessor el = new ELProcessor();
        el.defineBean("line", new TrafficView.PostLine("e", "T &amp; t", "/p", 3, 2, "+1%", "up"));
        assertEquals("T &amp; t", el.eval("line.title"));
        assertEquals(Long.valueOf(3), el.eval("line.views"));
    }

    @Test
    void theMenuOffersTrafficToBlogAdminsOnly() throws IOException {
        String xml = Files.readString(Path.of(
                "src/main/resources/com/wikantik/pressroom/ui/menu/editor-menu.xml"));
        Matcher m = Pattern.compile("<menu-item action=\"traffic\"[^>]*>").matcher(xml);
        assertTrue(m.find(), "no traffic menu item");
        assertTrue(m.group().contains("name=\"tabbedmenu.weblog.traffic\""));
        assertTrue(m.group().contains("weblogPerms=\"admin\""));
        assertTrue(bundle().containsKey("tabbedmenu.weblog.traffic"));
    }

    @Test
    void theBrowserSweepPinsThePageByItsMarker() throws IOException {
        String routes = Files.readString(Path.of(
                "../it-selenium/src/test/java/com/wikantik/pressroom/it/support/Routes.java"));
        Matcher m = Pattern.compile(
                "new Route\\(\"/pressroom-ui/authoring/traffic\\.rol\",[^;]*?\"#trafficPage\"").matcher(routes);
        assertTrue(m.find(), "Routes must pin /pressroom-ui/authoring/traffic.rol to #trafficPage");
    }

    private static Properties bundle() throws IOException {
        Properties props = new Properties();
        try (Reader reader = Files.newBufferedReader(BUNDLE, StandardCharsets.UTF_8)) {
            props.load(reader);
        }
        return props;
    }
}
