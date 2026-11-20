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

import java.util.HashMap;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.jsp.PageContext;

import com.wikantik.pressroom.business.URLStrategy;
import com.wikantik.pressroom.ui.rendering.util.ParsedRequest;

/**
 * Builder for the {@code initData} map every {@link Model#init} receives, so
 * the four rendering servlets stop spelling its keys by hand. The keys are the
 * ones the models read; {@code weblogger} is added by
 * {@code RenderingServletUtils.loadModels}.
 */
public final class ModelInitData {

    private final Map<String, Object> data = new HashMap<>();

    private ModelInitData(ParsedRequest request, URLStrategy urlStrategy) {
        data.put(ModelLoader.PARSED_REQUEST, request);
        data.put(ModelLoader.URL_STRATEGY, urlStrategy);
    }

    /** Starts the init data for this parsed request and url strategy. */
    public static ModelInitData forRequest(ParsedRequest request, URLStrategy urlStrategy) {
        return new ModelInitData(request, urlStrategy);
    }

    /** The JSP page context some models render through. */
    public ModelInitData pageContext(PageContext pageContext) {
        return with("pageContext", pageContext);
    }

    /** The raw servlet request, for the models that read it directly. */
    public ModelInitData request(HttpServletRequest request) {
        return with("request", request);
    }

    /** The request's parameters, as {@code getParameterMap()} returns them. */
    public ModelInitData requestParameters(Map<String, String[]> parameters) {
        return with("requestParameters", parameters);
    }

    /** Any further entry a particular model list expects. */
    public ModelInitData with(String key, Object value) {
        data.put(key, value);
        return this;
    }

    /** The mutable map, as the loader and models take it. */
    public Map<String, Object> build() {
        return data;
    }
}
