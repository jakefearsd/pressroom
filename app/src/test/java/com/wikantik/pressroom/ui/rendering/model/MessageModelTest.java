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
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.ui.rendering.util.WeblogRequest;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link MessageModel}, the {@code $text} object themes use for
 * translated strings.
 *
 * <p>The interesting behaviour is locale selection: the model resolves messages
 * against the locale of the request being rendered, not the server's. A blog
 * served in French must not pick up the server's English bundle just because
 * that is what the JVM defaults to.
 */
class MessageModelTest {

    private static MessageModel modelFor(String locale) throws WebloggerException {
        WeblogRequest request = new WeblogRequest();
        request.setWeblog(new Weblog("testblog", "testuser", "Test Blog", "d",
                "e@example.com", "journal", "en_US", "UTC"));
        request.setLocale(locale);

        Map<String, Object> initData = new HashMap<>();
        initData.put("parsedRequest", request);
        MessageModel model = new MessageModel();
        model.init(initData);
        return model;
    }

    @Test
    void modelIsRegisteredUnderTheNameTheVelocityToolsUsed() {
        assertEquals("text", new MessageModel().getModelName(),
                "The name is 'text' for backwards compatibility with themes written "
                        + "against the old Velocity Tools.");
    }

    @Test
    void initWithoutAParsedRequestFails() {
        MessageModel model = new MessageModel();
        WebloggerException thrown = assertThrows(WebloggerException.class,
                () -> model.init(new HashMap<>()),
                "init() must reject init data with no request.");
        assertTrue(thrown.getMessage().contains("weblogRequest"),
                "The failure should name what was missing; was: " + thrown.getMessage());
    }

    @Test
    void messagesResolveAgainstTheRequestLocale() throws Exception {
        // Both locales have their own bundle on the classpath, so neither result
        // can be an artefact of the JVM's own default locale.
        assertEquals("Enregistrer", modelFor("fr").get("generic.save"),
                "A French request must be served from the French bundle.");
        assertEquals("Speichern", modelFor("de").get("generic.save"),
                "A German request must be served from the German bundle.");
        assertEquals("Enregistrer", modelFor("fr_FR").get("generic.save"),
                "A language_COUNTRY locale must resolve through to the language "
                        + "bundle rather than falling back to the default.");
    }

    @Test
    void parameterisedMessagesSubstituteTheirArguments() throws Exception {
        // Themes pass a list because Velocity has no varargs.
        String message = modelFor("fr").get("categoryForm.changesSaved", List.of("Pressroom"));

        assertTrue(message.contains("Pressroom"),
                "The argument must be substituted into the message; was: " + message);
        assertFalse(message.contains("{0}"),
                "The placeholder must be gone; was: " + message);
    }

    @Test
    void anUnknownKeyRendersAsTheKeyItselfSoItIsVisible() throws Exception {
        // A missing translation must be obvious to whoever is looking at the
        // page rather than silently rendering as nothing.
        assertEquals("no.such.message.key.exists",
                modelFor("en").get("no.such.message.key.exists"),
                "An unresolvable key must render as the key.");
    }
}
