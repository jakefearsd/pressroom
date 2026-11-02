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
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Source scan for the Global Traffic page: its marker, the totals row, the
 * per-blog links, escaping and the bundle keys it names.
 */
class SiteTrafficJspTest {

    private static final Path JSPS = Path.of("src/main/webapp/WEB-INF/jsps");
    private static final Path PAGE = JSPS.resolve("admin/SiteTraffic.jsp");
    private static final Path BUNDLE = Path.of("src/main/resources/ApplicationResources.properties");

    private static String read(Path path) throws IOException {
        return Files.readString(path, StandardCharsets.UTF_8).replaceAll("(?s)<%--.*?--%>", "");
    }

    @Test
    void thePageCarriesItsMarkerAndSharesTheHeader() throws IOException {
        String page = read(PAGE);
        assertTrue(page.contains("<div id=\"siteTrafficPage\""), "the marker Routes pins");
        assertTrue(page.contains("editor/TrafficHeader.jsp"), "the period switch and notice are shared");
    }

    @Test
    void listsEveryBlogWithTotalsForViewsAndVisitsOnly() throws IOException {
        String page = read(PAGE);
        assertTrue(page.contains("${fn:escapeXml(traffic.totalViews)}"));
        assertTrue(page.contains("fn:escapeXml(traffic.totalVisits)"));
        assertFalse(page.contains("totalVisitors"), "no visitor total (AC 12)");
        assertTrue(page.contains("code=\"siteTraffic.visitors.why\""),
                "the visitors header carries the hover explanation");
        assertTrue(Pattern.compile("title=\"<spring:message code=.siteTraffic\\.visitors\\.why.").matcher(page).find());
    }

    @Test
    void theTotalRowPutsViewsAndVisitsUnderTheirOwnHeaders() throws IOException {
        String page = read(PAGE);
        assertTrue(page.contains("code=\"traffic.tile.visits\""), "a Visits column header");
        assertTrue(page.contains("${fn:escapeXml(line.visits)}"));
        assertFalse(page.contains("siteTraffic.totalVisits"), "visits are no longer squeezed into another column");
    }

    @Test
    void topPostsReadTheLoopVariableAndNothingElse() throws IOException {
        String page = read(PAGE);
        assertFalse(page.contains("post."), "nothing binds `post`");
        assertTrue(page.contains("<c:when test=\"${not empty top.title}\">"));
    }

    /** Inside each forEach, a dotted reference may only start from the loop's variable or a page-wide name. */
    @Test
    void everyLoopBodyReadsOnlyVariablesInScope() throws IOException {
        java.util.Set<String> pageWide = java.util.Set.of("traffic", "fn", "param", "pageContext", "actionWeblog");
        for (Path jsp : List.of(PAGE, JSPS.resolve("editor/TrafficBody.jsp"), JSPS.resolve("editor/TrafficHeader.jsp"))) {
            String src = read(jsp);
            var open = Pattern.compile("<c:forEach [^>]*var=\"(\\w+)\"").matcher(src);
            while (open.find()) {
                String var = open.group(1);
                int end = src.indexOf("</c:forEach>", open.end());
                var ref = Pattern.compile("\\$\\{[^}]*\\}").matcher(src.substring(open.end(), end));
                while (ref.find()) {
                    var id = Pattern.compile("(?<![\\w.'\"])([a-zA-Z]\\w*)\\.").matcher(ref.group());
                    while (id.find()) {
                        String root = id.group(1);
                        assertTrue(root.equals(var) || pageWide.contains(root),
                                jsp.getFileName() + ": " + ref.group() + " uses " + root + " inside loop " + var);
                    }
                }
            }
        }
    }

    @Test
    void rowsLinkToEachBlogsTrafficPage() throws IOException {
        String page = read(PAGE);
        assertTrue(page.contains("<c:url var=\"blogUrl\" value=\"/pressroom-ui/authoring/traffic.rol\">"));
        assertTrue(page.contains("<c:param name=\"weblog\" value=\"${line.handle}\"/>"));
        assertTrue(page.contains("<c:param name=\"period\" value=\"${traffic.period}\"/>"));
    }

    @Test
    void authorTextIsEscapedAndStoredTitlesAreBare() throws IOException {
        String page = read(PAGE);
        assertTrue(page.contains("${fn:escapeXml(line.name)}"));
        assertTrue(page.contains("${line.topPostTitle}"), "stored escaped");
        assertTrue(page.contains("${top.title}"), "stored escaped");
        assertFalse(Pattern.compile("\\$\\{\\s*line\\.name\\s*\\}").matcher(page).find());
        assertFalse(Pattern.compile("\\$\\{\\s*top\\.path\\s*\\}").matcher(page).find());
        assertFalse(page.contains("<script"));
        assertFalse(page.toLowerCase(Locale.ROOT).contains("onclick"));
        assertFalse(Pattern.compile("\\sstyle=").matcher(page).find());
    }

    @Test
    void everyMessageCodeTheJspNamesIsInTheBundle() throws IOException {
        Properties bundle = new Properties();
        try (Reader in = Files.newBufferedReader(BUNDLE, StandardCharsets.UTF_8)) {
            bundle.load(in);
        }
        var m = Pattern.compile("code=\"([A-Za-z0-9_.]+)\"").matcher(read(PAGE));
        int found = 0;
        while (m.find()) {
            found++;
            assertTrue(bundle.containsKey(m.group(1)), m.group(1) + " is not in the bundle");
        }
        assertTrue(found > 5);
        assertTrue(bundle.containsKey("traffic.state.siteZero"));
        assertTrue(bundle.containsKey("entries.views30"));
    }
}
