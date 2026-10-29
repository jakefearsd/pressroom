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

package com.wikantik.pressroom.ui.rendering.velocity;

import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.pojos.Template;
import com.wikantik.pressroom.pojos.TemplateRendition.RenditionType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link VelocityRendererFactory}, specifically the non-static
 * ({@link Template}, not {@link com.wikantik.pressroom.pojos.StaticThemeTemplate})
 * path: a persisted template with no {@code STANDARD} rendition, or a
 * rendition lookup that fails outright, must degrade to "no renderer", not
 * throw out of what {@code PageServlet} treats as a plain factory call.
 */
class VelocityRendererFactoryTest {

    private final VelocityRendererFactory factory = new VelocityRendererFactory();

    @Test
    void aTemplateWithNoStandardRenditionYieldsNoRenderer() throws Exception {
        Template template = mock(Template.class);
        when(template.getId()).thenReturn("template-1");
        when(template.getTemplateRendition(RenditionType.STANDARD)).thenReturn(null);

        assertNull(factory.getRenderer(template),
                "a template with no STANDARD rendition has nothing to render");
    }

    @Test
    void aFailingRenditionLookupYieldsNoRendererRatherThanThrowing() throws Exception {
        Template template = mock(Template.class);
        when(template.getId()).thenReturn("template-1");
        when(template.getTemplateRendition(RenditionType.STANDARD))
                .thenThrow(new WebloggerException("database is down"));

        assertNull(factory.getRenderer(template),
                "a failed rendition lookup must not propagate out of the factory");
    }
}
