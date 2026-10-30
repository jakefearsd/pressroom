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
package com.wikantik.pressroom.it;

import com.codeborne.selenide.Selenide;
import com.wikantik.pressroom.it.support.BrowserHealth;
import com.wikantik.pressroom.it.support.Editor;
import com.wikantik.pressroom.it.support.PressroomIT;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static com.codeborne.selenide.CollectionCondition.sizeGreaterThanOrEqual;
import static com.codeborne.selenide.Condition.appear;
import static com.codeborne.selenide.Condition.exist;
import static com.codeborne.selenide.Condition.text;
import static com.codeborne.selenide.Selenide.$;
import static com.codeborne.selenide.Selenide.$$;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Closes the map loop in a real browser: publish an entry containing the
 * {@code [map]} shortcode with two pins and a route, then prove the public
 * permalink turns the shortcode's inert {@code <div class="travel-map">} into
 * a live Leaflet map -- the one deliverable no server-side test can cover,
 * because everything after the div is {@code #showMapAssets} JavaScript
 * executing against the shortcode's data-attribute contract.
 *
 * <p>{@code TravelShortcodeRenderingTest} proves the markup and its payload
 * reach an anonymous reader through the sanitizer;
 * {@code MapAssetsRenderingTest} proves all nine theme heads ship the assets
 * exactly once with the {@code data:}-widened CSP. This is the browser half:
 * leaflet.js really is injected by the lazy guard, it really does load under
 * the theme CSP, and the pins really do become marker elements with the
 * author's label in the popup.
 *
 * <p><b>What this test deliberately does not assert: tiles.</b> The map
 * fetches its raster tiles browser-direct from
 * {@code tile.openstreetmap.org} (the OSM policy forbids proxying them
 * through the server, and the default Java User-Agent is blocked anyway), so
 * asserting on rendered tile imagery would make CI depend on a third-party
 * best-effort service with no SLA -- flaky for reasons that have nothing to
 * do with Pressroom. Every element asserted below appears whether or not a
 * single tile arrives: {@code .leaflet-container}, the marker icons, the
 * polyline and the attribution control are all created by Leaflet from the
 * data payload alone.
 */
class MapIT extends PressroomIT {

    private static final String ENTRY_ADD = "/pressroom-ui/authoring/entryAdd.rol?weblog=" + WEBLOG_HANDLE;

    /** Rendered on the edit page only once the entry is actually published. */
    private static final String PERMALINK = "#entry_bean_permalink";

    @BeforeEach
    void logIn() {
        loginAsAdmin();
    }

    @Test
    void aPublishedMapShortcodeBecomesALeafletMapWithMarkers() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String label = "Eiffel Tower " + suffix;

        // --- author and publish the map entry -------------------------------
        openPath(ENTRY_ADD);
        $("#entry").should(exist);
        $("[name='bean.title']").setValue("IT Map " + suffix);
        Editor.setText("<p>Route notes " + suffix + "</p>"
                        + "<p>[map route=\"true\"]"
                        + "[pin lat=\"48.8584\" lng=\"2.2945\" label=\"" + label + "\"]"
                        + "[pin lat=\"48.8606\" lng=\"2.3376\" label=\"Louvre\"]"
                        + "[/map]</p>");
        $("button[formaction$='entryAdd!publish.rol']").click();
        $(PERMALINK).should(exist);
        String permalink = $(PERMALINK).getAttribute("href");
        assertNotNull(permalink);

        // --- the public page renders the placeholder the guard looks for ----
        Selenide.open(permalink);
        BrowserHealth.current().settle();
        $(".travel-map").should(exist);

        // --- ... and #showMapAssets turns it into a real map -----------------
        // .leaflet-container is added by L.map() itself, so its presence is
        // proof that the lazy guard fired, leaflet.js was injected and loaded
        // under the theme CSP, and the data-pins payload parsed.
        $(".travel-map.leaflet-container").should(appear);
        $$(".leaflet-marker-icon").shouldHave(sizeGreaterThanOrEqual(2));
        // the polyline data-route asked for; an SVG path inside the overlay pane
        $(".leaflet-overlay-pane path").should(exist);
        // required by the OSM tile usage policy, and never suppressed
        $(".leaflet-control-attribution a[href$='/copyright']").should(exist);

        // The marker icons must resolve to the webjar, not to a page-relative
        // path: Leaflet's own path heuristic caches a wrong answer on a static
        // field for the life of the page, which is why the macro pins the URLs.
        String iconSrc = $(".leaflet-marker-icon").getAttribute("src");
        assertNotNull(iconSrc);
        assertTrue(iconSrc.contains("/webjars/leaflet/1.9.4/dist/images/marker-icon"),
                "marker icons must come from the Leaflet webjar, not a guessed "
                        + "page-relative path; got " + iconSrc);

        // --- the pin label is the author's text, in a popup -----------------
        $(".leaflet-marker-icon").click();
        $(".leaflet-popup-content").should(appear).shouldHave(text(label));
    }
}
