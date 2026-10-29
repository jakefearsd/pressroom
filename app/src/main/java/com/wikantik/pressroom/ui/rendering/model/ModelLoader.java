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

import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.business.URLStrategy;
import com.wikantik.pressroom.business.Weblogger;
import com.wikantik.pressroom.util.Reflection;
import com.wikantik.pressroom.util.Utilities;


/**
 * Helps with model loading process.
 */
public final class ModelLoader {

    private ModelLoader() {
    }

    private static final Logger log = LoggerFactory.getLogger(ModelLoader.class);

    /** The {@code initData} key under which every model receives the business-tier facade. */
    static final String WEBLOGGER = "weblogger";

    /** The {@code initData} key under which the url-building models receive their strategy. */
    static final String URL_STRATEGY = "urlStrategy";

    /**
     * The business-tier facade a renderer put into {@code initData}, or a
     * {@link WebloggerException} naming the key if it is missing or not a
     * {@link Weblogger}. Models call this from {@code init()} instead of
     * reaching for a static locator.
     */
    static Weblogger requireWeblogger(Map<String, Object> initData) throws WebloggerException {
        Object value = initData == null ? null : initData.get(WEBLOGGER);
        if (value instanceof Weblogger weblogger) {
            return weblogger;
        }
        throw new WebloggerException("expected '" + WEBLOGGER + "' (a Weblogger) from init data");
    }

    /**
     * The url strategy a renderer put into {@code initData}, or a
     * {@link WebloggerException} naming the key. There is deliberately no
     * fallback to the application's default strategy: the preview servlet
     * installs its own, and the one caller that used to pass none
     * ({@code WeblogCacheWarmupJob}) now passes one.
     */
    static URLStrategy requireUrlStrategy(Map<String, Object> initData) throws WebloggerException {
        Object value = initData == null ? null : initData.get(URL_STRATEGY);
        if (value instanceof URLStrategy strategy) {
            return strategy;
        }
        throw new WebloggerException("expected '" + URL_STRATEGY + "' (a URLStrategy) from init data");
    }

    /**
     * Convenience method to load a comma-separated list of page models.
     *
     * Optionally fails if any exceptions are thrown when initializing
     * the Model instances.
     */
    public static void loadModels(String modelsString, Map<String, Object> modelMap,
            Map<String, Object> initData, boolean fail) throws WebloggerException {

        // Every model receives the business-tier facade through initData (the
        // models are reflectively instantiated, so this is their only way in).
        // A caller that forgot it is a programming error, not a per-model
        // failure, so it is refused whatever the fail flag says.
        requireWeblogger(initData);

        String[] models = Utilities.stringToStringArray(modelsString, ",");
        if (models != null) {
            for (String model : models) {
                try {
                    Model pageModel = (Model) Reflection.newInstance(model);
                    pageModel.init(initData);
                    modelMap.put(pageModel.getModelName(), pageModel);
                } catch (WebloggerException re) {
                    if(fail) {
                        throw re;
                    } else {
                        log.warn("Error initializing model: {}", model);
                    }
                } catch (ClassNotFoundException cnfe) {
                    if(fail) {
                        throw new WebloggerException("Error finding model: " + model, cnfe);
                    } else {
                        log.warn("Error finding model: {}", model);
                    }
                } catch (ReflectiveOperationException ex) {
                    if(fail) {
                        throw new WebloggerException("Error instantiating model: " + model, ex);
                    } else {
                        log.warn("Error instantiating model: {}", model);
                    }
                }
            }
        }
    }
    
}
