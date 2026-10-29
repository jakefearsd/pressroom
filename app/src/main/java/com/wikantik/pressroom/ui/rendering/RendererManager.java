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
package com.wikantik.pressroom.ui.rendering;

import java.util.ArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.wikantik.pressroom.pojos.Template;

import java.util.List;
import java.util.stream.Collectors;
import com.wikantik.pressroom.util.Reflection;

/**
 * Returns Renderer for Template via configured RendererFactories.
 * <p/>
 * The purpose of the RendererManager is to provide a level of abstraction
 * between classes that are rendering content and the implementations of the
 * rendering technology.  This allows us to provide easily pluggable rendering
 * implementations.
 */
public final class RendererManager {

    private static final Logger log = LoggerFactory.getLogger(RendererManager.class);
    
    // a set of all renderer factories we are consulting
    private static final List<RendererFactory> rendererFactories = new ArrayList<>();

    static {
        
        // lookup set of renderer factories we are going to use
        try {
            rendererFactories.addAll(Reflection.newInstancesFromProperty("rendering.userRendererFactories"));
        } catch (ReflectiveOperationException ex) {
            log.error("Unable to create user rendering factories", ex);
        }
        try {
            rendererFactories.addAll(Reflection.newInstancesFromProperty("rendering.rollerRendererFactories"));
        } catch (ReflectiveOperationException ex) {
            log.error("Unable to create roller rendering factories", ex);
        }

        if (rendererFactories.isEmpty()) {
            // hmm ... failed to load any renderer factories?
            log.warn("Failed to load any renderer factories.  "
                    + "Rendering probably won't function as you expect.");
        }

        // The second line here does genuinely non-trivial work (stream +
        // collect), unlike a plain accessor -- both lines are guarded
        // together as one startup log entry, per GuardLogStatement's own
        // textbook case.
        if (log.isInfoEnabled()) {
            log.info("Renderer Manager Initialized, {} factories configured.", rendererFactories.size());
            log.info(rendererFactories.stream().map(t -> t.getClass().toString())
                    .collect(Collectors.joining(",", "[", "]")));
        }
    }

    // this class is non-instantiable
    private RendererManager() {
    }

    /**
     * Find the appropriate Renderer for the given content.
     * <p/>
     * This method checks all renderer factories configured for the Roller
     * instance and tries to find a Renderer for the content.  If no Renderer
     * can be found then we throw an exception.
     */
    public static Renderer getRenderer(Template template)
            throws RenderingException {

        Renderer renderer;

        // iterate over our renderer factories and see if one of them
        // wants to handle this content
        for (RendererFactory rendererFactory : rendererFactories) {
            renderer = rendererFactory.getRenderer(template);
            if (renderer != null) {
                return renderer;
            }
        }

        throw new RenderingException("No renderer found for template " + template.getId() + "!");
    }
}
