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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The business section of the blog settings form, checked on the JSP source:
 * what it posts when the business list could not load, and the input limits
 * that match the place columns.
 */
class WeblogConfigBusinessJspTest {

    private static final Path JSP = Path.of(
            "src/main/webapp/WEB-INF/jsps/editor/WeblogConfig.jsp");

    private static String read() throws IOException {
        return Files.readString(JSP, StandardCharsets.UTF_8);
    }

    /**
     * With no list to choose from, a select offering only "None" would post
     * an empty id and the save would unlink the business. The form instead
     * carries the current id in a hidden input and disables the select.
     */
    @Test
    void whenTheBusinessListIsUnavailableTheFormCarriesTheCurrentBusiness() throws IOException {
        Matcher branch = Pattern.compile(
                "<c:when test=\"\\$\\{businessesUnavailable}\">(.*?)</c:when>\\s*\\n\\s*<c:otherwise>",
                Pattern.DOTALL)
                .matcher(read());
        assertTrue(branch.find(), "the business field needs a businessesUnavailable branch");
        String body = branch.group(1);
        assertTrue(body.contains("<input type=\"hidden\" name=\"bean.businessId\" "
                        + "value=\"${fn:escapeXml(bean.businessId)}\"/>"),
                "the branch must post the current business id: " + body);
        assertTrue(body.contains("disabled=\"disabled\""), "the select must be disabled: " + body);
        assertTrue(!body.contains("name=\"bean.businessId\" class"),
                "only the hidden input may post bean.businessId: " + body);
    }

    /**
     * The front-door select lists page titles an author typed: a title with
     * {@code <} or {@code "} must come out as text, in the option's value and label.
     */
    @Test
    void theFrontDoorSelectEscapesPageIdsAndTitles() throws IOException {
        Matcher select = Pattern.compile(
                "<select[^>]*name=\"bean.frontPageId\".*?</select>", Pattern.DOTALL).matcher(read());
        assertTrue(select.find(), "the front-door select");
        String body = select.group();
        assertTrue(body.contains("<option value=\"${fn:escapeXml(p.id)}\""), body);
        assertTrue(body.contains(">${fn:escapeXml(p.title)}</option>"), body);
        assertTrue(!body.contains("${p.title}") && !body.contains("${p.id}"), "no raw EL: " + body);
    }

    @Test
    void localityAndRegionAreLimitedToTheirColumnLength() throws IOException {
        String jsp = read();
        for (String field : new String[] {"placeLocality", "placeRegion"}) {
            Matcher input = Pattern.compile("<input id=\"weblog_bean_" + field + "\"[^>]*>").matcher(jsp);
            assertTrue(input.find(), field + " input not found");
            assertTrue(input.group().contains("maxlength=\"128\""), input.group());
        }
    }

    @Test
    void theSettingsFormOffersTheTwoCommentCheckboxes() throws IOException {
        String jsp = read();
        assertTrue(jsp.contains("name=\"bean.commentsEnabled\""), "commentsEnabled checkbox");
        assertTrue(jsp.contains("name=\"bean.commentNotify\""), "commentNotify checkbox");
        assertTrue(jsp.contains("code=\"weblogSettings.commentsEnabled\""));
        assertTrue(jsp.contains("code=\"weblogSettings.commentNotify\""));
    }

    @Test
    void checkboxNamedAndLabelled() throws IOException {
        String jsp = read();
        assertTrue(jsp.contains("name=\"bean.showMoreFromSite\""), "showMoreFromSite checkbox");
        assertTrue(jsp.contains("code=\"weblogSettings.showMoreFromSite\""));
        assertTrue(jsp.contains("code=\"weblogSettings.showMoreFromSite.help\""));
    }

    @Test
    void entryOrderSelectOffersNewestAndOldest() throws IOException {
        String jsp = read();
        assertTrue(jsp.contains("<select") && jsp.contains("name=\"bean.entryOrder\""), "entryOrder select");
        assertTrue(jsp.contains("value=\"NEWEST\"") && jsp.contains("value=\"OLDEST\""), "both options");
        for (String key : new String[] {"weblogSettings.entryOrder", "weblogSettings.entryOrder.newest",
                "weblogSettings.entryOrder.oldest", "weblogSettings.entryOrder.help"}) {
            assertTrue(jsp.contains("code=\"" + key + "\""), key);
        }
    }

    @Test
    void pageWidthSelectOffersStandardAndWide() throws IOException {
        String jsp = read();
        assertTrue(jsp.contains("<select") && jsp.contains("name=\"bean.pageWidth\""), "pageWidth select");
        assertTrue(jsp.contains("value=\"STANDARD\"") && jsp.contains("value=\"WIDE\""), "both options");
        for (String key : new String[] {"weblogSettings.pageWidth", "weblogSettings.pageWidth.standard",
                "weblogSettings.pageWidth.wide", "weblogSettings.pageWidth.help"}) {
            assertTrue(jsp.contains("code=\"" + key + "\""), key);
        }
    }

    /** 0.9.1 criterion 20. The name never says "share link": that term is the tokened URLs V020 removed. */
    @Test
    void shareRowCheckboxNamedAndLabelled() throws IOException {
        String jsp = read();
        assertTrue(jsp.contains("name=\"bean.shareRowEnabled\" value=\"true\" ${bean.shareRowEnabled ? 'checked' : ''}"),
                "shareRowEnabled checkbox");
        assertTrue(jsp.contains("code=\"weblogSettings.shareRowEnabled\""));
        assertTrue(jsp.contains("code=\"weblogSettings.shareRowEnabled.help\""));
        assertTrue(!jsp.toLowerCase(java.util.Locale.ROOT).contains("sharelink"), "never 'share link'");
    }

    /**
     * 0.9.1 criteria 17 and 18: the status sits in the newsletter list field's row, as a message
     * (text, not a colour), one message per state the controller can put in the model.
     */
    @Test
    void subscribeFormStatusIsTextBesideTheListField() throws IOException {
        String jsp = read();
        Matcher row = Pattern.compile("(?s)for=\"weblog_bean_newsletterListUuid\".*?id=\"settings-business\"")
                .matcher(jsp);
        assertTrue(row.find(), "newsletter row not found");
        String body = row.group();
        assertTrue(body.contains("id=\"subscribe-form-status\""), body);
        assertTrue(body.contains("${subscribeFormStatus.key == 'on'}"), "chosen by the model's status: " + body);
        for (com.wikantik.pressroom.business.SubscribeFormStatus status
                : com.wikantik.pressroom.business.SubscribeFormStatus.values()) {
            assertTrue(body.contains("<spring:message code=\"websiteSettings.subscribeForm." + status.getKey() + "\"/>"),
                    "a message for " + status + ": " + body);
        }
    }
}
