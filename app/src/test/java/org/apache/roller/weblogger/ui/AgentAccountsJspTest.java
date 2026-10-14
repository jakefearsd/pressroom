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

package org.apache.roller.weblogger.ui;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Markup the agent-accounts screens promise (spec 2026-10-13), which a
 * rendered page cannot show a unit test: which controls are offered when,
 * and which ask before they act (criterion 25).
 */
class AgentAccountsJspTest {

    static final Path JSPS = Path.of("src/main/webapp/WEB-INF/jsps");

    static String jsp(String relative) throws IOException {
        Path path = JSPS.resolve(relative);
        assertTrue(Files.isRegularFile(path), "missing " + path.toAbsolutePath());
        return Files.readString(path, StandardCharsets.UTF_8).replaceAll("(?s)<%--.*?--%>", "");
    }

    /** Criterion 17: "Email a set-password link" sits inside the mailConfigured branch. */
    @Test
    void theEmailedLinkIsOfferedOnlyWhileMailIsConfigured() throws IOException {
        String src = jsp("admin/UserEdit.jsp");
        int when = src.indexOf("<c:when test=\"${mailConfigured}\">");
        int link = src.indexOf("id=\"bean_passwordModeLink\"");
        assertTrue(when >= 0 && link > when && link < src.indexOf("</c:when>", when),
                "the link choice must render only while mail is configured");
    }

    /** The form whose action ends with {@code actionSuffix}, from its tag to its close. */
    static String formPostingTo(String src, String actionSuffix) {
        Matcher m = Pattern.compile("(?s)<form\\b[^>]*?action=\"[^\"]*" + Pattern.quote(actionSuffix)
                + "[^\"]*\".*?</form>").matcher(src);
        assertTrue(m.find(), "no form posting to " + actionSuffix);
        return m.group();
    }

    /** Criterion 25: removing a membership asks first. */
    @Test
    void removingAMembershipIsConfirmed() throws IOException {
        assertTrue(formPostingTo(jsp("admin/UserWeblogs.jsp"), "userWeblogs!remove.rol").contains("data-confirm="));
    }

    /** Criterion 18: with group blogging off the section says so instead of offering to add. */
    @Test
    void addingIsOfferedOnlyWhileGroupBloggingIsOn() throws IOException {
        String src = jsp("admin/UserWeblogs.jsp");
        int off = src.indexOf("<c:when test=\"${not groupBloggingEnabled}\">");
        assertTrue(off >= 0, "the section has a group-blogging-off branch");
        int offEnd = src.indexOf("</c:when>", off);
        assertTrue(src.substring(off, offEnd).contains("userWeblogs.groupBloggingOff"));
        assertTrue(src.indexOf("userWeblogs!grant.rol") > offEnd, "the add form sits outside that branch");
    }

    /** No field is named weblog: RollerHandlerInterceptor would read it as the action weblog. */
    @Test
    void noMembershipFieldIsNamedWeblog() throws IOException {
        assertFalse(jsp("admin/UserWeblogs.jsp").contains("name=\"weblog\""));
    }

    /** Criterion 25: revoking a token asks first. */
    @Test
    void revokingATokenIsConfirmed() throws IOException {
        assertTrue(formPostingTo(jsp("core/TokenList.jsp"), "tokenRevokeAction").contains("data-confirm="));
    }

    /** Criteria 14 and 25: a site admin's Revoke asks first. */
    @Test
    void revokingFromTheSiteListIsConfirmed() throws IOException {
        assertTrue(formPostingTo(jsp("admin/ApiTokens.jsp"), "apiTokens!revoke.rol").contains("data-confirm="));
    }

    /** Review focus 1: on the reveal page the secret is a form value, never part of a link. */
    @Test
    void theSecretIsNeverPartOfALink() throws IOException {
        String src = jsp("core/TokenReveal.jsp");
        assertTrue(src.contains("value=\"${fn:escapeXml(revealedToken)}\""));
        Matcher hrefs = Pattern.compile("href=\"([^\"]*)\"").matcher(src);
        while (hrefs.find()) {
            assertFalse(hrefs.group(1).contains("revealedToken") || hrefs.group(1).contains("mcpSnippet"),
                    hrefs.group());
        }
    }

    /** The pin field is "pin", never "weblog": RollerHandlerInterceptor would read that as the action weblog. */
    @Test
    void theTokenPinFieldIsNotNamedWeblog() throws IOException {
        String src = jsp("core/TokenForm.jsp");
        assertFalse(src.contains("name=\"weblog\""));
        assertTrue(src.contains("name=\"pin\""));
    }

    /** Criterion 6: an agent's pages carry no password field and no set-password link. */
    @Test
    void anAgentsPagesOfferNoPasswordAndNoSetPasswordLink() throws IOException {
        for (String page : List.of("admin/Agent.jsp", "admin/AgentCreate.jsp")) {
            String src = jsp(page);
            assertFalse(src.contains("sendPasswordLink"), page);
            assertFalse(src.contains("type=\"password\""), page);
        }
    }

    /** Criteria 8 and 25: Disable and Enable each ask first. */
    @Test
    void enablingAndDisablingAnAgentAreConfirmed() throws IOException {
        String form = formPostingTo(jsp("admin/Agent.jsp"), "agent!setEnabled.rol");
        assertTrue(form.contains("id=\"agentDisableButton\"") && form.contains("id=\"agentEnableButton\""));
        assertEquals(2, form.split("data-confirm=", -1).length - 1);
    }
}
