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

package com.wikantik.pressroom.ui.rendering.model;

import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.business.Weblogger;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * Unit tests for {@link ModelLoader}, which turns the comma-separated class
 * names in {@code roller.properties} into the objects a template can reference.
 *
 * <p>The {@code fail} flag is the whole point of this class. Renderers pass
 * {@code true} for the models a page cannot do without and {@code false} for
 * optional extras, so a broken optional model must not take a page down and a
 * broken required one must not be swallowed.
 */
class ModelLoaderTest {

    /** A model that loads cleanly. */
    public static class GoodModel implements Model {
        static int initCount;

        @Override
        public String getModelName() {
            return "good";
        }

        @Override
        public void init(Map<String, Object> initData) {
            initCount++;
        }
    }

    /** A second working model, to prove loading continues past a bad one. */
    public static class SecondModel implements Model {
        @Override
        public String getModelName() {
            return "second";
        }

        @Override
        public void init(Map<String, Object> initData) {
            // nothing to do
        }
    }

    /** A model whose init() rejects the data it was handed. */
    public static class FailingModel implements Model {
        @Override
        public String getModelName() {
            return "failing";
        }

        @Override
        public void init(Map<String, Object> initData) throws WebloggerException {
            throw new WebloggerException("missing something this model needs");
        }
    }

    /** Cannot be instantiated reflectively — stands in for an abstract model. */
    public abstract static class UninstantiableModel implements Model {
    }

    private static final String GOOD = GoodModel.class.getName();
    private static final String SECOND = SecondModel.class.getName();
    private static final String FAILING = FailingModel.class.getName();
    private static final String UNINSTANTIABLE = UninstantiableModel.class.getName();
    private static final String MISSING = "com.wikantik.pressroom.no.such.Model";

    private Map<String, Object> load(String models, boolean fail) throws WebloggerException {
        Map<String, Object> modelMap = new HashMap<>();
        Map<String, Object> initData = new HashMap<>();
        initData.put("weblogger", mock(Weblogger.class));
        ModelLoader.loadModels(models, modelMap, initData, fail);
        return modelMap;
    }

    // ------------------------------------------------------------ weblogger

    /**
     * Every model receives the business-tier facade through {@code initData}
     * (spec Decision 3); a caller that forgets it is a programming error, so
     * the loader refuses up front — whatever the {@code fail} flag says —
     * instead of letting a model NPE half-way through a render.
     */
    @Test
    void aMissingWebloggerIsFatalWhateverTheFailFlagSays() {
        for (boolean fail : new boolean[] {true, false}) {
            Map<String, Object> initData = new HashMap<>();
            WebloggerException thrown = assertThrows(WebloggerException.class,
                    () -> ModelLoader.loadModels(GOOD, new HashMap<>(), initData, fail),
                    "init data without a 'weblogger' must be refused (fail=" + fail + ")");
            assertTrue(thrown.getMessage().contains("weblogger"),
                    "The failure must name the missing key; was: " + thrown.getMessage());
        }
    }

    @Test
    void aWebloggerOfTheWrongTypeIsRefusedLikeAMissingOne() {
        Map<String, Object> initData = new HashMap<>();
        initData.put("weblogger", "not a facade");
        WebloggerException thrown = assertThrows(WebloggerException.class,
                () -> ModelLoader.loadModels(GOOD, new HashMap<>(), initData, true));
        assertTrue(thrown.getMessage().contains("weblogger"),
                "The failure must name the key; was: " + thrown.getMessage());
    }

    @Test
    void modelsAreKeyedByTheNameTheyPublish() throws Exception {
        Map<String, Object> models = load(GOOD, false);

        assertEquals(1, models.size(), "One class name means one model.");
        assertTrue(models.get("good") instanceof GoodModel,
                "The key must be the model's own getModelName(), because that is the "
                        + "name templates reference it by.");
    }

    @Test
    void everyModelInTheListIsLoadedAndInitialised() throws Exception {
        int before = GoodModel.initCount;

        Map<String, Object> models = load(GOOD + "," + SECOND, false);

        assertEquals(2, models.size(), "Both class names must be loaded.");
        assertEquals(before + 1, GoodModel.initCount,
                "init() must be called exactly once per model; a model that is "
                        + "registered but never initialised renders as empty.");
    }

    @Test
    void aNullOrEmptyModelListLoadsNothing() throws Exception {
        assertTrue(load(null, true).isEmpty(),
                "A null list must be tolerated — several renderers have no optional "
                        + "models configured at all.");
        assertTrue(load("", true).isEmpty(), "An empty list must load nothing.");
    }

    // ------------------------------------------------------- optional models

    @Test
    void anUnknownClassIsSkippedWhenFailureIsTolerated() throws Exception {
        Map<String, Object> models = load(MISSING + "," + GOOD, false);

        assertEquals(1, models.size(),
                "A typo in an optional model's class name must not stop the models "
                        + "after it from loading.");
        assertTrue(models.containsKey("good"), "The working model must still load.");
    }

    @Test
    void aModelWhoseInitRejectsTheDataIsSkippedWhenFailureIsTolerated() throws Exception {
        Map<String, Object> models = load(FAILING + "," + GOOD, false);

        assertFalse(models.containsKey("failing"),
                "A model that failed to initialise must not be registered.");
        assertTrue(models.containsKey("good"),
                "The models after it must still load.");
    }

    @Test
    void aModelThatCannotBeInstantiatedIsSkippedWhenFailureIsTolerated() throws Exception {
        Map<String, Object> models = load(UNINSTANTIABLE + "," + GOOD, false);

        assertEquals(1, models.size(),
                "A class that cannot be constructed must be skipped like any other "
                        + "broken optional model.");
    }

    // ------------------------------------------------------- required models

    @Test
    void anUnknownClassIsFatalWhenFailureIsNotTolerated() {
        WebloggerException thrown = assertThrows(WebloggerException.class,
                () -> load(MISSING, true),
                "A required model that does not exist must abort the render rather "
                        + "than produce a half-populated page.");
        assertTrue(thrown.getMessage().contains(MISSING),
                "The failure must name the class so the misconfiguration can be "
                        + "found; was: " + thrown.getMessage());
    }

    @Test
    void aFailedInitIsRethrownUnchangedWhenFailureIsNotTolerated() {
        WebloggerException thrown = assertThrows(WebloggerException.class,
                () -> load(FAILING, true),
                "A required model whose init() fails must abort the render.");
        assertEquals("missing something this model needs", thrown.getMessage(),
                "The model's own exception must be propagated as-is, not wrapped in "
                        + "a generic one that hides what went wrong.");
    }

    @Test
    void anUninstantiableClassIsFatalWhenFailureIsNotTolerated() {
        WebloggerException thrown = assertThrows(WebloggerException.class,
                () -> load(UNINSTANTIABLE, true),
                "A required model that cannot be constructed must abort the render.");
        assertTrue(thrown.getMessage().contains(UNINSTANTIABLE),
                "The failure must name the class; was: " + thrown.getMessage());
        assertInstanceOf(ReflectiveOperationException.class, thrown.getCause(),
                "The underlying reflection failure must be kept so the real reason "
                        + "survives into the log.");
    }
}
