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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The endpoint check of the contact and subscribe forms in the
 * showAudienceAssets macro ({@code WEB-INF/velocity/weblog.vm}), read as
 * source; {@link CommentsScriptSourceTest} covers the comments block.
 *
 * <p>The script builds a PII-bearing form around any slot whose
 * {@code data-endpoint} passes {@code endpointOk}, and HTMLSanitizer lets an
 * author write {@code data-endpoint} on a div. A suffix check alone passed
 * {@code https://evil.example/pressroom-ui/rendering/contact.rol}: only the
 * theme's {@code connect-src 'self'} kept readers' data on the site, and a
 * custom theme without that CSP leaked it (code review, 2026-11-03).
 */
class AudienceEndpointScriptSourceTest {

    private static final Path WEBLOG_VM = Path.of("src/main/webapp/WEB-INF/velocity/weblog.vm");

    @Test
    void anEndpointMustBeOnThisOriginAsWellAsEndInItsSuffix() throws IOException {
        String body = functionBody(macro("showAudienceAssets"), "endpointOk");

        assertTrue(body.contains("slice(-suffix.length)"), "the suffix check stays: " + body);
        assertTrue(Pattern.compile("new URL\\(endpoint,\\s*window\\.location\\.href\\)\\.origin\\s*"
                + "===\\s*window\\.location\\.origin").matcher(body).find(),
                "the endpoint is resolved against the page and compared by origin: " + body);
        assertTrue(body.contains("catch"), "an endpoint that is no URL at all is refused, not thrown: " + body);
    }

    @Test
    void bothFormsAskTheCheckBeforeBuildingAnything() throws IOException {
        String macro = macro("showAudienceAssets");
        for (String suffix : List.of("/pressroom-ui/rendering/contact.rol", "/newsletter/subscribe")) {
            assertTrue(macro.contains("!endpointOk(endpoint, '" + suffix + "')"), suffix + " is checked");
        }
    }

    /** Velocity reads a dollar or a hash; the check must reach the page verbatim. */
    @Test
    void theCheckCarriesNoVelocityMetacharacters() throws IOException {
        String body = functionBody(macro("showAudienceAssets"), "endpointOk");
        assertFalse(body.contains("$") || body.contains("#"), body);
    }

    private static String macro(String name) throws IOException {
        String vm = Files.readString(WEBLOG_VM, StandardCharsets.UTF_8);
        int start = vm.indexOf("#macro(" + name);
        assertTrue(start >= 0, "no macro " + name + " in " + WEBLOG_VM);
        int end = vm.indexOf("\n#end", start);
        return vm.substring(start, end);
    }

    /** The body of {@code function name(...) {...}}, braces balanced. */
    private static String functionBody(String code, String name) {
        int at = code.indexOf("function " + name + "(");
        assertTrue(at >= 0, "no function " + name);
        int open = code.indexOf('{', at);
        int depth = 0;
        for (int i = open; i < code.length(); i++) {
            char c = code.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return code.substring(open, i + 1);
                }
            }
        }
        throw new AssertionError("unbalanced braces in function " + name);
    }
}
