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
package org.apache.roller.weblogger.ui;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockPageContext;
import org.springframework.mock.web.MockServletContext;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.context.support.StaticWebApplicationContext;
import org.springframework.web.servlet.DispatcherServlet;
import org.springframework.web.servlet.tags.MessageTag;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code <spring:message arguments="${x}">} splits a String argument on
 * commas, so a value that can hold one -- a business or weblog name, a
 * formatted date ("Oct 22, 2026, 3:00 PM"), a joined role list -- printed
 * only up to its first comma: "Smith, Jones B&amp;B" became "Smith". Those
 * sites pass a one-element EL list, {@code ${[x]}}, which the tag takes as
 * the argument array as it stands.
 */
class MessageArgumentCommaTest {

    private static final Path JSPS = Path.of("src/main/webapp/WEB-INF/jsps");

    /** The tag's own behaviour, the reason for the list form. */
    @Test
    void aListArgumentKeepsItsCommasWhereAStringIsSplit() throws Exception {
        assertEquals("Delete Smith?", render("Smith, Jones B&B"));
        assertEquals("Delete Smith, Jones B&B?", render(List.of("Smith, Jones B&B")));
    }

    /** Every site that passes free text a comma can occur in uses the list form. */
    @Test
    void freeTextArgumentsArePassedAsAList() throws IOException {
        Map<String, String> sites = Map.of(
                "admin/Businesses.jsp", "arguments=\"${[b.name]}\"",
                "editor/WeblogRemoveConfirm.jsp", "arguments=\"${[fn:escapeXml(actionWeblog.name)]}\"",
                "editor/EntryEdit.jsp", "arguments=\"${[newsletterSentAtText]}\"",
                "core/MainMenu.jsp", "arguments=\"${[fn:escapeXml(permLabels)]}\"",
                "editor/MediaFileView.jsp", "arguments=\"${[fn:escapeXml(bean.name)]}\"",
                "editor/TemplateEdit.jsp", "arguments=\"${[fn:escapeXml(bean.name), actionWeblog.handle]}\"");
        List<String> missing = new ArrayList<>();
        for (Map.Entry<String, String> site : sites.entrySet()) {
            String src = Files.readString(JSPS.resolve(site.getKey()), StandardCharsets.UTF_8);
            if (!src.contains(site.getValue())) {
                missing.add(site.getKey() + " lacks " + site.getValue());
            }
        }
        assertTrue(missing.isEmpty(), String.join("\n", missing));
        assertTrue(Files.readString(JSPS.resolve("editor/MediaFileView.jsp"), StandardCharsets.UTF_8)
                        .contains("arguments=\"${[fn:escapeXml(bean.tags)]}\""),
                "MediaFileView.jsp: the tag search echo");
    }

    private static String render(Object arguments) throws Exception {
        MockServletContext servletContext = new MockServletContext();
        StaticWebApplicationContext wac = new StaticWebApplicationContext();
        wac.setServletContext(servletContext);
        wac.getStaticMessageSource().addMessage("k", Locale.ENGLISH, "Delete {0}?");
        wac.refresh();
        servletContext.setAttribute(WebApplicationContext.ROOT_WEB_APPLICATION_CONTEXT_ATTRIBUTE, wac);

        MockHttpServletRequest request = new MockHttpServletRequest(servletContext);
        request.addPreferredLocale(Locale.ENGLISH);
        request.setAttribute(DispatcherServlet.WEB_APPLICATION_CONTEXT_ATTRIBUTE, wac);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockPageContext pageContext = new MockPageContext(servletContext, request, response);

        MessageTag tag = new MessageTag();
        tag.setPageContext(pageContext);
        tag.setCode("k");
        tag.setArguments(arguments);
        tag.doStartTag();
        tag.doEndTag();
        pageContext.getOut().flush();
        return response.getContentAsString();
    }
}
