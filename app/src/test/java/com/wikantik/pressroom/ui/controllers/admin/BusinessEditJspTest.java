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
package com.wikantik.pressroom.ui.controllers.admin;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** The business edit form, checked on the JSP source. */
class BusinessEditJspTest {

    private static final Path JSP = Path.of("src/main/webapp/WEB-INF/jsps/admin/BusinessEdit.jsp");

    /** The sameAs help text takes the cap from the model, never a literal that can drift from the rule. */
    @Test
    void theSameAsHelpUsesTheCapTheControllerSupplies() throws IOException {
        Matcher help = Pattern.compile("<spring:message code=\"businesses.sameAs.help\"[^>]*>")
                .matcher(Files.readString(JSP, StandardCharsets.UTF_8));
        assertTrue(help.find(), "sameAs help not found");
        assertTrue(help.group().contains("arguments=\"${maxSameAs}\""), help.group());
    }
}
