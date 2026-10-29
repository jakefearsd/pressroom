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
package com.wikantik.pressroom.pojos;

import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.business.WeblogEntryManager;
import com.wikantik.pressroom.business.Weblogger;
import com.wikantik.pressroom.pojos.WeblogEntry.PubStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers {@link WeblogCategory}'s computed state.
 *
 * <p>{@code position} determines the order categories appear in the editor and
 * in themes that list them; it is assigned at construction from the categories
 * already on the weblog rather than stored by the caller, so it is easy to get
 * subtly wrong and hard to notice.
 */
class WeblogCategoryTest {

    private Weblog weblog;

    @BeforeEach
    void setUp() {
        weblog = new Weblog();
        weblog.setHandle("testblog");
    }

    @Test
    void theFirstCategoryOnAWeblogTakesPositionZero() {
        WeblogCategory first = new WeblogCategory(weblog, "General", "", null);

        assertEquals(0, first.getPosition(),
                "Positions are 0-based; starting at 1 would leave a gap the UI renders "
                        + "as a blank slot at the top of the list");
        assertEquals(List.of(first), weblog.getWeblogCategories(),
                "The constructor attaches the category to the weblog it was given");
    }

    @Test
    void eachNewCategoryTakesThePositionAfterThePreviousOne() {
        WeblogCategory first = new WeblogCategory(weblog, "General", "", null);
        WeblogCategory second = new WeblogCategory(weblog, "Travel", "", null);
        WeblogCategory third = new WeblogCategory(weblog, "Photos", "", null);

        assertEquals(0, first.getPosition());
        assertEquals(1, second.getPosition());
        assertEquals(2, third.getPosition(),
                "Positions must keep increasing so the display order is stable; two "
                        + "categories sharing a position order arbitrarily between renders");
    }

    @Test
    void positionContinuesFromTheHighestExistingCategory() {
        // Categories can be reordered by hand, so the new one has to follow the
        // category currently last in the list rather than assume list.size().
        WeblogCategory existing = new WeblogCategory();
        existing.setName("General");
        existing.setPosition(17);
        weblog.setWeblogCategories(new ArrayList<>(List.of(existing)));

        WeblogCategory added = new WeblogCategory(weblog, "Travel", "", null);

        assertEquals(18, added.getPosition(),
                "A category added after one at position 17 must land at 18, not at 1 -- "
                        + "reusing an existing position makes the order ambiguous");
    }

    @Test
    void markupIsStrippedFromTheNameAndDescription() {
        WeblogCategory category = new WeblogCategory();
        category.setName("Trav<script>alert(1)</script>el");
        category.setDescription("About <b>travel</b>");

        assertFalse(category.getName().contains("<script>"),
                "The category name appears in the category URL and in navigation links, "
                        + "so markup must not survive into it");
        assertFalse(category.getDescription().contains("<b>"),
                "The description is rendered as text in theme templates");
        assertTrue(category.getDescription().contains("travel"),
                "The author's actual words must survive the strip");
    }

    @Test
    void categoriesSortByName() {
        WeblogCategory apples = new WeblogCategory();
        apples.setName("Apples");
        WeblogCategory pears = new WeblogCategory();
        pears.setName("Pears");

        assertTrue(apples.compareTo(pears) < 0, "Apples sorts before Pears");
        assertTrue(pears.compareTo(apples) > 0, "and the comparison must be antisymmetric");
        assertEquals(0, apples.compareTo(apples));
    }

}
