/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  The ASF licenses this file to You
 * under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License.  You may obtain a copy
 * of the License at
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
package com.wikantik.pressroom.ui.rendering;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import com.wikantik.pressroom.TestUtils;
import com.wikantik.pressroom.business.BusinessManager;
import com.wikantik.pressroom.pojos.Business;
import com.wikantik.pressroom.pojos.User;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.ui.rendering.servlets.RenderingTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code #showBusinessCard}, called from every bundled theme's footer: the
 * visible counterpart of the business JSON-LD. Assertions run on the
 * extracted {@code <aside class="business-card">} element (Jsoup is not a
 * test dependency), never on a bare substring, because Velocity prints an
 * unresolved reference literally and a loose match would pass on that.
 */
class BusinessCardRenderingTest {

    private static final List<String> THEMES = List.of("journal", "portfolio", "travel");
    private static final String HANDLE = "bizcardrenderblog";

    private static final Pattern CARD = Pattern.compile(
            "<aside class=\"business-card\">(.*?)</aside>", Pattern.DOTALL);

    private User user;
    private Weblog weblog;
    private Business business;

    @BeforeEach
    void setUp() throws Exception {
        RenderingTestSupport.ensureRenderingRuntime();
        RenderingTestSupport.clearRenderCaches();
        user = TestUtils.setupUser("bizcardrenderuser");
        weblog = TestUtils.setupWeblog(HANDLE, user);
        TestUtils.endSession(true);
    }

    @AfterEach
    void tearDown() throws Exception {
        TestUtils.teardownWeblog(weblog.getId());
        if (business != null) {
            BusinessManager businesses = TestUtils.weblogger().getBusinessManager();
            businesses.removeBusiness(businesses.getBusiness(business.getId()));
            business = null;
        }
        TestUtils.teardownUser(user.getUserName());
        TestUtils.endSession(true);
    }

    // ---------------------------------------------------------------- helpers

    private void configure(String theme, Business toSave, String placeLocality, String bookingUrl)
            throws Exception {
        BusinessManager businesses = TestUtils.weblogger().getBusinessManager();
        if (toSave != null) {
            business = toSave;
            businesses.saveBusiness(business);
            TestUtils.endSession(true);
        }
        Weblog managed = TestUtils.getManagedWebsite(weblog);
        managed.setEditorTheme(theme);
        if (business != null) {
            managed.setBusiness(businesses.getBusiness(business.getId()));
        }
        if (placeLocality != null) {
            managed.setPlaceType("LodgingBusiness");
            managed.setPlaceLocality(placeLocality);
        }
        managed.setBookingUrl(bookingUrl);
        TestUtils.weblogger().getWeblogManager().saveWeblog(managed);
        TestUtils.endSession(true);
        RenderingTestSupport.clearRenderCaches();
    }

    private String render(String pathInfo) throws Exception {
        MockHttpServletRequest request = RenderingTestSupport
                .anonymousGet("/pressroom-ui/rendering/page", pathInfo);
        MockHttpServletResponse response = RenderingTestSupport
                .execute(RenderingTestSupport.pageServlet(), request);
        assertEquals(200, response.getStatus(), "page must render for " + pathInfo);
        return response.getContentAsString();
    }

    /** The inner HTML of the one card, or null when the page has none. */
    private static String card(String body) {
        Matcher m = CARD.matcher(body);
        return m.find() ? m.group(1) : null;
    }

    private static Business namedBusiness(String name) {
        Business b = new Business();
        b.setName(name);
        return b;
    }

    // ------------------------------------------------------------------ AC15

    /**
     * An actual invocation: the macro call alone on its line, as the themes
     * write it. A commented-out call, a renamed macro or a mention in prose
     * does not count.
     */
    private static final Pattern MACRO_CALL = Pattern.compile(
            "(?m)^[ \\t]*#showBusinessCard\\(\\$model\\.weblog \"[^\"\\r\\n]+\"\\)[ \\t]*$");

    /** Velocity block comments (any number of lines) and "##" line comments. */
    private static final Pattern COMMENTS = Pattern.compile("(?s)#\\*.*?\\*#|##[^\\r\\n]*");

    static boolean callsCardMacro(String template) {
        String live = COMMENTS.matcher(template).replaceAll("");
        return MACRO_CALL.matcher(live).find();
    }

    @Test
    void onlyARealMacroInvocationCountsAsTheFooterCall() {
        assertTrue(callsCardMacro("<footer>\n    #showBusinessCard($model.weblog \"Book a stay\")\n</footer>"));
        assertFalse(callsCardMacro("## #showBusinessCard($model.weblog \"Book a stay\")"));
        assertFalse(callsCardMacro("#* #showBusinessCard($model.weblog \"Book a stay\") *#"));
        assertFalse(callsCardMacro("#*\n    #showBusinessCard($model.weblog \"Book a stay\")\n*#"));
        assertFalse(callsCardMacro("#*\n  disabled\n*#\n## #showBusinessCard($model.weblog \"Book a stay\")"));
        assertTrue(callsCardMacro("#* note *#\n    #showBusinessCard($model.weblog \"Book a stay\")"));
        assertFalse(callsCardMacro("#showBusinessCardX($model.weblog \"Book a stay\")"));
        assertFalse(callsCardMacro("<p>call the showBusinessCard macro</p>"));
        assertFalse(callsCardMacro("<b>#showBusinessCard($model.weblog \"x\")</b>"));
    }

    @Test
    void everyThemeTemplateWithAFooterCallsTheCardMacro() throws Exception {
        List<String> missing = new ArrayList<>();
        int footers = 0;
        for (String theme : THEMES) {
            try (Stream<Path> files = Files.walk(Path.of("src/main/webapp/themes", theme))) {
                for (Path vm : files.filter(f -> f.toString().endsWith(".vm")).toList()) {
                    String text = Files.readString(vm);
                    if (text.contains("<footer")) {
                        footers++;
                        if (!callsCardMacro(text)) {
                            missing.add(vm.toString());
                        }
                    }
                }
            }
        }
        assertTrue(footers >= 9, "expected to find the themes' footers, found " + footers);
        assertTrue(missing.isEmpty(), "footers without the business card: " + missing);
    }

    @Test
    void aBlogWithNeitherBusinessNorPlaceRendersNoCardInAnyTheme() throws Exception {
        List<String> failures = new ArrayList<>();
        for (String theme : THEMES) {
            configure(theme, null, null, null);
            String body = render("/" + HANDLE);
            if (body.contains("business-card") || body.contains("showBusinessCard")
                    || body.contains("$utils.businessCard")) {
                failures.add(theme);
            }
        }
        assertTrue(failures.isEmpty(), "themes that leaked a card or macro text: " + failures);
    }

    @Test
    void everyThemeRendersTheCardOnTheHomePage() throws Exception {
        List<String> failures = new ArrayList<>();
        for (String theme : THEMES) {
            configure(theme, business == null ? namedBusiness("Casa Azul") : null, null, null);
            String card = card(render("/" + HANDLE));
            if (card == null || !card.contains("<p class=\"business-card-name\">Casa Azul</p>")) {
                failures.add(theme + " -> " + card);
            }
        }
        assertTrue(failures.isEmpty(), "themes without a rendered card: " + failures);
    }

    // -------------------------------------------------------------- RF1 / RF2

    @Test
    void aNameOnlyBusinessGivesJustTheName() throws Exception {
        configure("journal", namedBusiness("Casa Azul"), null, null);
        String card = card(render("/" + HANDLE));
        assertTrue(card != null, "a card is expected");
        assertTrue(card.contains("<p class=\"business-card-name\">Casa Azul</p>"), card);
        assertFalse(card.contains("business-card-tel"), card);
        assertFalse(card.contains("business-card-book"), card);
        assertFalse(card.contains("business-card-locality"), card);
        assertFalse(card.contains("<a "), card);
    }

    @Test
    void aTelephoneShowsAsTypedButLinksAsDigits() throws Exception {
        Business b = namedBusiness("Casa Azul");
        b.setTelephone("+351 912 345.678");
        configure("travel", b, null, null);
        String card = card(render("/" + HANDLE));
        assertTrue(card != null, "a card is expected");
        assertTrue(card.contains("<a class=\"business-card-tel\" href=\"tel:+351912345678\">"
                + "+351 912 345.678</a>"), card);
    }

    @Test
    void withoutAPlaceTheBusinessNameIsShownEscaped() throws Exception {
        configure("portfolio", namedBusiness("Fish & <b>Chips</b>"), null, null);
        String card = card(render("/" + HANDLE));
        assertTrue(card != null, "a card is expected");
        assertTrue(card.contains("<p class=\"business-card-name\">Fish &amp; &lt;b&gt;Chips&lt;/b&gt;</p>"),
                card);
    }

    @Test
    void aPlaceShowsItsOwnNameAndLocalityEscaped() throws Exception {
        configure("portfolio", namedBusiness("Casa Azul Group"), "São Miguel", null);
        Weblog managed = TestUtils.getManagedWebsite(weblog);
        // Markup in a blog name is stripped on save; the ampersand survives.
        managed.setName("Fish & Chips");
        TestUtils.weblogger().getWeblogManager().saveWeblog(managed);
        TestUtils.endSession(true);
        RenderingTestSupport.clearRenderCaches();

        String card = card(render("/" + HANDLE));
        assertTrue(card != null, "a card is expected");
        assertTrue(card.contains("<p class=\"business-card-name\">Fish &amp; Chips</p>"),
                "a place shows the place (blog) name, escaped: " + card);
        assertFalse(card.contains("Casa Azul Group"), card);
        assertTrue(card.contains("<p class=\"business-card-locality\">S&atilde;o Miguel</p>"), card);
    }

    // ---------------------------------------------------------- booking button

    @Test
    void theBookButtonIsTaggedAndUsesTheThemesLabel() throws Exception {
        Business b = namedBusiness("Casa Azul");
        b.setBookingUrl("https://b.example.com/x?ref=a&utm_source=keep#dates");
        configure("journal", b, null, null);
        String card = card(render("/" + HANDLE));
        assertTrue(card != null, "a card is expected");
        assertTrue(card.contains("class=\"business-card-book\""), card);
        assertTrue(card.contains(
                "href=\"https://b.example.com/x?ref=a&amp;utm_source=keep&amp;utm_medium=blog#dates\""),
                card);
        assertTrue(card.contains("data-umami-event=\"business-card-click\""), card);
        assertTrue(card.contains("data-umami-event-dest=\"b.example.com\""), card);
        assertFalse(card.contains("data-umami-event-entry"), card);
        assertTrue(card.contains(">Book a stay</a>"), card);
    }

    @Test
    void onAPermalinkTheBookLinkCarriesTheEntryAnchor() throws Exception {
        Business b = namedBusiness("Casa Azul");
        b.setBookingUrl("https://b.example.com/x");
        configure("travel", b, null, null);
        TestUtils.setupWeblogEntry("card-entry", weblog, user);
        TestUtils.endSession(true);
        RenderingTestSupport.clearRenderCaches();

        String card = card(render("/" + HANDLE + "/entry/card-entry"));
        assertTrue(card != null, "a card is expected");
        assertTrue(card.contains("data-umami-event-entry=\"card-entry\""), card);
        assertTrue(card.contains("utm_campaign=card-entry"), card);
    }

    @Test
    void aJavascriptBookingUrlGivesNoBookLink() throws Exception {
        Business b = namedBusiness("Casa Azul");
        b.setBookingUrl("javascript:alert(1)");
        configure("journal", b, null, "javascript:alert(2)");
        String card = card(render("/" + HANDLE));
        assertTrue(card != null, "a card is expected");
        assertFalse(card.contains("business-card-book"), card);
        assertFalse(card.contains("javascript:"), card);
    }
}
