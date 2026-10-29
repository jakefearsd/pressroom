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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Source-text scan pinning that Cancel, on the most destructive screen in the
 * admin UI, is not a POST to the GET-only {@code weblogConfig.rol} -- that
 * shape 405s the escape hatch of this page. {@code weblogConfig.rol} is only
 * ever mapped {@code @GetMapping} in {@code WeblogConfigController}, so a
 * {@code <form method="post">} targeting it here can never work.
 */
class WeblogRemoveConfirmJspTest {

    private static final Path JSP =
            Path.of("src/main/webapp/WEB-INF/jsps/editor/WeblogRemoveConfirm.jsp");

    @Test
    void cancelIsNotAFormPostingToTheGetOnlyWeblogConfigEndpoint() throws IOException {
        assertTrue(Files.isRegularFile(JSP), "Expected " + JSP.toAbsolutePath());
        String source = Files.readString(JSP, StandardCharsets.UTF_8);

        assertFalse(source.contains("<form action=\"${pageContext.request.contextPath}"
                        + "/pressroom-ui/authoring/weblogConfig.rol\""),
                "Cancel must not POST to weblogConfig.rol, which is GET-only -- "
                        + "that would 405 the escape hatch of the delete-weblog screen");

        assertTrue(source.contains("<c:url value=\"/pressroom-ui/authoring/weblogConfig.rol\" var=\"cancelUrl\">"),
                "Expected Cancel to build its target with <c:url>, the same pattern every "
                        + "other weblogConfig.rol link in this codebase uses");
        assertTrue(source.contains("<a href=\"${cancelUrl}\" class=\"btn btn-secondary\">"),
                "Cancel must be a plain secondary-styled link, not a form button styled "
                        + "btn-success next to the destructive btn-danger button");
    }
}
