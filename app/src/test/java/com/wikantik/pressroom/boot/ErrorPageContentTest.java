/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.wikantik.pressroom.boot;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Content checks for the three container-level error pages {@link
 * WebContainerConfigTest} proves {@code ErrorPageRegistrar} routes 403/404/
 * 500 to ({@code pressroom-ui/errors/403.jsp}/{@code 404.jsp}/{@code error.jsp}).
 * Together the two tests cover both halves of "a 404 on an unknown URL
 * renders our page, not the container default": {@code WebContainerConfigTest}
 * pins that Spring registers OUR path for each status with Tomcat, and this
 * one pins what that path actually contains.
 *
 * <p>This does not render the JSPs through a real Jasper/Tomcat pipeline --
 * {@code SecurityConfigTest}'s class-level comment explains why {@code
 * @SpringBootTest} (the shape that would let {@code MockMvc} exercise a real
 * embedded container) throws {@code NoSuchMethodError} under this project's
 * pinned JUnit/Spring Test versions, and a mock {@code
 * DispatcherServlet}-only {@code MockMvc} setup does not compile JSPs at
 * all -- {@code forward()} on a mock request just records the target path.
 * A source-text scan is the same tool {@link
 * com.wikantik.pressroom.ui.MessageKeyTest} and {@code
 * WebjarReferenceTest} already use for exactly this reason.
 *
 * <p>These pages used to be a raw, unstyled {@code <table>} of servlet error
 * attributes titled via {@code <fmt:message>}, which resolves locale off the
 * request's {@code Accept-Language} (or the server's default locale, absent
 * one) -- reported live as an unstyled table rendered in German. This test
 * pins the fix: hardcoded English copy, no {@code <fmt:message>}/{@code
 * <spring:message>} (so no locale dependency at all), no raw exception
 * detail from the request attributes, and a self-contained inline
 * {@code <style>} rather than a theme/admin stylesheet dependency.
 */
class ErrorPageContentTest {

    private static final Path ERRORS_DIR = Paths.get("src/main/webapp/pressroom-ui/errors");

    @Test
    void the404PageIsPlainEnglishSelfContainedAndHasNoStackTrace() throws IOException {
        assertCleanErrorPage(ERRORS_DIR.resolve("404.jsp"), "Page not found");
    }

    @Test
    void the500PageIsPlainEnglishSelfContainedAndHasNoStackTrace() throws IOException {
        assertCleanErrorPage(ERRORS_DIR.resolve("error.jsp"), "Something went wrong");
    }

    @Test
    void the403PageIsPlainEnglishSelfContainedAndHasNoStackTrace() throws IOException {
        assertCleanErrorPage(ERRORS_DIR.resolve("403.jsp"), "Access denied");
    }

    /** Strips {@code <%-- ... --%>} JSP comments so this scan checks live
     * markup, not prose that happens to mention a tag by name (as the JSPs'
     * own explanatory comments do). */
    private static final Pattern JSP_COMMENT = Pattern.compile("<%--.*?--%>", Pattern.DOTALL);

    private void assertCleanErrorPage(Path jsp, String expectedHeading) throws IOException {
        assertTrue(Files.exists(jsp), "Expected to find " + jsp.toAbsolutePath());
        String content = JSP_COMMENT.matcher(Files.readString(jsp, StandardCharsets.UTF_8)).replaceAll("");

        assertTrue(content.contains(expectedHeading),
                jsp + " should render the heading \"" + expectedHeading + "\"");

        for (String localeDependentTag : List.of("<fmt:message", "<spring:message")) {
            assertFalse(content.contains(localeDependentTag),
                    jsp + " must not resolve any copy through " + localeDependentTag
                            + " -- that resolves locale from the request's Accept-Language "
                            + "(or the server's default locale, absent one), which is how this "
                            + "page used to render in German for a non-English client.");
        }

        for (String stackTraceSource : List.of(
                "jakarta.servlet.error.exception", "jakarta.servlet.error.message",
                "jakarta.servlet.error.type", "<%@ page import", "getStackTrace")) {
            assertFalse(content.contains(stackTraceSource),
                    jsp + " must not surface " + stackTraceSource + " to the reader");
        }

        assertTrue(content.contains("<style>"),
                jsp + " should carry its own self-contained inline <style>, no theme/admin "
                        + "stylesheet dependency");
        assertFalse(content.contains("roller.css"),
                jsp + " must not depend on the admin stylesheet");

        assertTrue(content.contains("<html lang=\"en\">"),
                jsp + " should declare English explicitly rather than negotiating a locale");
    }

    /**
     * The three pages wore a grey-and-blue system card from before the
     * design system; they now carry the quiet-instrument neutrals and teal
     * accent, as literals (no stylesheet may be needed), light and dark.
     * Their home link was a {@code <c:url>}, which rewrote it to
     * {@code /;jsessionid=...} for an anonymous visitor -- an address
     * crawlers then indexed -- and the page made a session for every one.
     */
    @Test
    void theErrorPagesWearTheQuietPaletteAndCarryNoSessionId() throws IOException {
        for (String name : List.of("404.jsp", "403.jsp", "error.jsp")) {
            Path jsp = ERRORS_DIR.resolve(name);
            String content = JSP_COMMENT.matcher(Files.readString(jsp, StandardCharsets.UTF_8)).replaceAll("");
            assertTrue(content.contains("#0F6E68") && content.contains("#4FB3AA"),
                    jsp + " uses the teal accent, light and dark");
            assertTrue(content.contains("@media (prefers-color-scheme: dark)"), jsp + " has a dark palette");
            assertFalse(content.contains("#06c"), jsp + " still uses the old link blue");
            assertFalse(content.contains("<c:url"), jsp + " must not rewrite a link with a session id");
            assertTrue(content.contains("session=\"false\""), jsp + " must not create a session");
        }
    }

    /** A 404 inside a weblog offers the way back to it, escaped (the name is author input). */
    @Test
    void theNotFoundPageOffersTheWayBackToTheWeblog() throws IOException {
        String content = JSP_COMMENT.matcher(Files.readString(ERRORS_DIR.resolve("404.jsp"),
                StandardCharsets.UTF_8)).replaceAll("");
        assertTrue(content.contains("roller.notFound.weblogName")
                        && content.contains("roller.notFound.weblogUrl"),
                "404.jsp reads the weblog PageServlet left on the request");
        assertTrue(content.contains("fn:escapeXml(requestScope['roller.notFound.weblogName'])")
                        && content.contains("fn:escapeXml(requestScope['roller.notFound.weblogUrl'])"),
                "both are escaped");
    }
}
