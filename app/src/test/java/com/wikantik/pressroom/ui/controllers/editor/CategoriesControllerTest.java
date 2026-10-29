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
package com.wikantik.pressroom.ui.controllers.editor;

import java.util.List;

import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.pojos.WeblogCategory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ui.Model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link CategoriesController}, the read-only category list page.
 *
 * <p>The only real behaviour here is falling back to an error message instead
 * of a broken page when the category lookup fails. Adding, editing and deleting
 * live in their own controllers; this one renders the list.
 */
class CategoriesControllerTest extends EditorControllerTestSupport {

    private CategoriesController controller;
    private Model model;

    @BeforeEach
    void setUp() {
        controller = prepare(new CategoriesController());
        model = newModel();
    }

    @Test
    void listsAllCategoriesForTheActionWeblog() throws Exception {
        WeblogCategory travel = new WeblogCategory();
        travel.setId("cat-1");
        travel.setName("Travel");
        when(weblogger.getWeblogEntryManager().getWeblogCategories(weblog))
                .thenReturn(List.of(travel));

        String view = controller.execute(request, model);

        assertEquals(".Categories", view);
        assertEquals(List.of(travel), model.getAttribute("allCategories"));
    }

    @Test
    void aLookupFailureIsReportedRatherThanBlowingUpThePage() throws Exception {
        when(weblogger.getWeblogEntryManager().getWeblogCategories(weblog))
                .thenThrow(new WebloggerException("database down"));

        String view = controller.execute(request, model);

        assertEquals(".Categories", view);
        assertTrue(errors(model).contains("generic.error.check.logs"),
                "Expected a categories-list error, got: " + errors(model));
    }

    @Test
    void theInUseCategoriesAreNamedForTheDeleteButton() throws Exception {
        // Categories.jsp used to ask each raw category ${category.inUse}, a
        // getter that located the entry manager statically. The controller now
        // answers that question once per row and the JSP reads the set.
        WeblogCategory travel = new WeblogCategory();
        travel.setId("cat-1");
        travel.setName("Travel");
        WeblogCategory empty = new WeblogCategory();
        empty.setId("cat-2");
        empty.setName("Empty");
        when(weblogger.getWeblogEntryManager().getWeblogCategories(weblog))
                .thenReturn(List.of(travel, empty));
        when(weblogger.getWeblogEntryManager().isWeblogCategoryInUse(travel)).thenReturn(true);
        when(weblogger.getWeblogEntryManager().isWeblogCategoryInUse(empty)).thenReturn(false);

        controller.execute(request, model);

        assertEquals(java.util.Set.of("cat-1"), model.getAttribute("categoriesInUse"));
    }

    @Test
    void theListPageShowsTheWeblogsCategories() throws Exception {
        WeblogCategory travel = new WeblogCategory();
        travel.setId("cat-1");
        travel.setName("Travel");
        when(weblogger.getWeblogEntryManager().getWeblogCategories(weblog))
                .thenReturn(List.of(travel));

        String view = controller.execute(request, model);

        assertEquals(".Categories", view);
        assertEquals(List.of(travel), model.getAttribute("allCategories"));
    }
}
