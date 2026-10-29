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

package com.wikantik.pressroom.ui.controllers;

import java.util.List;
import java.util.Set;
import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.business.BusinessManager;
import com.wikantik.pressroom.business.WeblogManager;
import com.wikantik.pressroom.pojos.Business;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.ui.controllers.editor.WeblogConfigBean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** The settings rules, without a request or a form: one bean in, the broken rules out. */
class WeblogSettingsRulesTest {

    private BusinessManager businesses;
    private WeblogManager weblogs;
    private WeblogSettingsRules.Context context;

    @BeforeEach
    void setUp() {
        businesses = mock(BusinessManager.class);
        weblogs = mock(WeblogManager.class);
        context = new WeblogSettingsRules.Context("myblog", 30, "https://blog.example.com", businesses, weblogs);
    }

    private static WeblogConfigBean valid() {
        WeblogConfigBean bean = new WeblogConfigBean();
        bean.setEntryDisplayCount(15);
        bean.setLocale("en_US");
        bean.setTimeZone("Europe/Berlin");
        return bean;
    }

    private List<String> fieldsOf(WeblogConfigBean bean) {
        return WeblogSettingsRules.check(bean, context).stream().map(WeblogSettingsRules.Violation::field).toList();
    }

    @Test
    void aValidBeanBreaksNoRule() {
        assertEquals(List.of(), WeblogSettingsRules.check(valid(), context));
    }

    @Test
    void entryDisplayCountMustBeBetweenOneAndTheSiteMaximum() {
        WeblogConfigBean zero = valid();
        zero.setEntryDisplayCount(0);
        WeblogConfigBean over = valid();
        over.setEntryDisplayCount(31);
        assertEquals(List.of("entryDisplayCount"), fieldsOf(zero));
        assertEquals("entryDisplayCount must be between 1 and 30.",
                WeblogSettingsRules.check(over, context).get(0).detail());
    }

    @Test
    void aLocaleOrTimeZoneTheFormDoesNotOfferIsRefused() {
        WeblogConfigBean bean = valid();
        bean.setLocale("xx_NOWHERE");
        bean.setTimeZone("Mars/Olympus_Mons");
        assertEquals(List.of("locale", "timeZone"), fieldsOf(bean));
    }

    @Test
    void iconAndAboutLongerThanTheirColumnAreRefusedWithTheirLabels() {
        WeblogConfigBean bean = valid();
        bean.setIcon("i".repeat(256));
        bean.setAbout("a".repeat(256));
        List<WeblogSettingsRules.Violation> found = WeblogSettingsRules.check(bean, context);
        assertEquals(List.of("icon", "about"), found.stream().map(WeblogSettingsRules.Violation::field).toList());
        assertEquals("websiteSettings.icon", found.get(0).labelKey());
        assertEquals(255, found.get(0).max());
        assertEquals("'icon' must be 255 characters or fewer.", found.get(0).detail());
    }

    @Test
    void aPlaceTypeWithoutALocalityInvolvesBothFields() {
        WeblogConfigBean bean = valid();
        bean.setPlaceType("LodgingBusiness");
        WeblogSettingsRules.Violation v = WeblogSettingsRules.check(bean, context).get(0);
        assertEquals("placeLocality", v.field());
        assertTrue(v.involvesAny(Set.of("placeType")));
    }

    @Test
    void halfACoordinatePairIsRefused() {
        WeblogConfigBean bean = valid();
        bean.setPlaceLng("10");
        assertEquals(List.of("placeLat"), fieldsOf(bean));
    }

    @Test
    void aCustomDomainAnotherWeblogHoldsIsAConflict() throws Exception {
        Weblog other = new Weblog();
        other.setHandle("other");
        when(weblogs.getWeblogByCustomDomain("travel.example.org")).thenReturn(other);
        WeblogConfigBean bean = valid();
        bean.setCustomDomain(" Travel.Example.org ");
        WeblogSettingsRules.Violation v = WeblogSettingsRules.check(bean, context).get(0);
        assertTrue(v.conflict());
        assertEquals("customDomain is already in use by another weblog.", v.detail());
        assertEquals("travel.example.org", bean.getCustomDomain(), "normalised onto the bean");
    }

    @Test
    void aBusinessLookupThatCannotRunIsNotOneThatPassed() throws Exception {
        when(businesses.getBusiness("b1")).thenThrow(new WebloggerException("db down"));
        WeblogConfigBean bean = valid();
        bean.setBusinessId("b1");
        assertEquals(List.of("businessId"), fieldsOf(bean));
    }

    @Test
    void aKnownBusinessPasses() throws Exception {
        when(businesses.getBusiness("b1")).thenReturn(new Business());
        WeblogConfigBean bean = valid();
        bean.setBusinessId("b1");
        assertEquals(List.of(), fieldsOf(bean));
    }

    @Test
    void anAnalyticsShareUrlMustBeHttpAndFitItsColumn() {
        WeblogConfigBean ftp = valid();
        ftp.setAnalyticsShareUrl("ftp://x");
        WeblogConfigBean longUrl = valid();
        longUrl.setAnalyticsShareUrl("https://x/" + "a".repeat(250));
        assertEquals(List.of("analyticsShareUrl"), fieldsOf(ftp));
        assertEquals(List.of("analyticsShareUrl"), fieldsOf(longUrl));
    }
}
