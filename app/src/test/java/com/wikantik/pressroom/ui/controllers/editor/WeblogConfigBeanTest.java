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

import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.business.BusinessManager;
import com.wikantik.pressroom.pojos.Weblog;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** {@link WeblogConfigBean#copyTo(Weblog, BusinessManager)} resolves the business before it touches the weblog. */
class WeblogConfigBeanTest {

    private static Weblog weblog() {
        Weblog w = new Weblog();
        w.setName("Original");
        w.setTagline("Original tagline");
        w.setPlaceLocality("Original town");
        return w;
    }

    private static WeblogConfigBean bean() {
        WeblogConfigBean bean = new WeblogConfigBean();
        bean.setName("Changed");
        bean.setTagline("Changed tagline");
        bean.setPlaceLocality("Changed town");
        bean.setBusinessId("biz-1");
        return bean;
    }

    @Test
    void aLookupThatThrowsLeavesTheWeblogUntouched() throws Exception {
        BusinessManager businesses = mock(BusinessManager.class);
        when(businesses.getBusiness("biz-1")).thenThrow(new WebloggerException("db down"));
        Weblog w = weblog();

        assertThrows(WebloggerException.class, () -> bean().copyTo(w, businesses));

        assertEquals("Original", w.getName());
        assertEquals("Original tagline", w.getTagline());
        assertEquals("Original town", w.getPlaceLocality());
    }

    @Test
    void anUnknownBusinessLeavesTheWeblogUntouched() throws Exception {
        BusinessManager businesses = mock(BusinessManager.class);
        Weblog w = weblog();

        assertThrows(WebloggerException.class, () -> bean().copyTo(w, businesses));

        assertEquals("Original", w.getName());
        assertNull(w.getBusiness());
    }

    @Test
    void theCommentSettingsRoundTripThroughTheBean() {
        Weblog w = weblog();
        w.setCommentsEnabled(Boolean.TRUE);
        w.setCommentNotify(Boolean.FALSE);
        WeblogConfigBean b = new WeblogConfigBean();
        b.copyFrom(w);
        org.junit.jupiter.api.Assertions.assertTrue(b.getCommentsEnabled());
        org.junit.jupiter.api.Assertions.assertFalse(b.getCommentNotify());

        b.setCommentsEnabled(false);
        b.setCommentNotify(true);
        b.copyTo(w);
        assertEquals(Boolean.FALSE, w.getCommentsEnabled());
        assertEquals(Boolean.TRUE, w.getCommentNotify());
    }

    @Test
    void nullCommentColumnsReadAsTheirDefaults() {
        Weblog w = weblog();
        w.setCommentsEnabled(null);
        w.setCommentNotify(null);
        WeblogConfigBean b = new WeblogConfigBean();
        b.copyFrom(w);
        org.junit.jupiter.api.Assertions.assertFalse(b.getCommentsEnabled());
        org.junit.jupiter.api.Assertions.assertTrue(b.getCommentNotify());
    }

    /**
     * An unticked checkbox posts nothing, and the controller binds a fresh bean per request,
     * so a bean default of true could never be turned off. Pinned through the real binder.
     */
    @Test
    void uncheckedCommentCheckboxesBindToFalseAndCheckedOnesToTrue() {
        WeblogConfigBean absent = new WeblogConfigBean();
        new org.springframework.web.bind.WebDataBinder(absent, "bean")
                .bind(new org.springframework.beans.MutablePropertyValues());
        org.junit.jupiter.api.Assertions.assertFalse(absent.getCommentsEnabled());
        org.junit.jupiter.api.Assertions.assertFalse(absent.getCommentNotify());

        WeblogConfigBean present = new WeblogConfigBean();
        org.springframework.beans.MutablePropertyValues pvs = new org.springframework.beans.MutablePropertyValues();
        pvs.add("commentsEnabled", "true");
        pvs.add("commentNotify", "true");
        new org.springframework.web.bind.WebDataBinder(present, "bean").bind(pvs);
        org.junit.jupiter.api.Assertions.assertTrue(present.getCommentsEnabled());
        org.junit.jupiter.api.Assertions.assertTrue(present.getCommentNotify());
    }

    @Test
    void showMoreFromSiteRoundTripsAndNullReadsAsTrue() {
        Weblog w = weblog();
        w.setShowMoreFromSite(null);
        WeblogConfigBean b = new WeblogConfigBean();
        b.copyFrom(w);
        org.junit.jupiter.api.Assertions.assertTrue(b.getShowMoreFromSite());

        w.setShowMoreFromSite(Boolean.FALSE);
        b.copyFrom(w);
        org.junit.jupiter.api.Assertions.assertFalse(b.getShowMoreFromSite());

        b.setShowMoreFromSite(true);
        b.copyTo(w);
        assertEquals(Boolean.TRUE, w.getShowMoreFromSite());
    }

    /** An unticked checkbox posts nothing, so the bean default must be false for it to be switched off. */
    @Test
    void uncheckedBoxSavesFalse() {
        WeblogConfigBean absent = new WeblogConfigBean();
        new org.springframework.web.bind.WebDataBinder(absent, "bean")
                .bind(new org.springframework.beans.MutablePropertyValues());
        org.junit.jupiter.api.Assertions.assertFalse(absent.getShowMoreFromSite());

        WeblogConfigBean present = new WeblogConfigBean();
        org.springframework.beans.MutablePropertyValues pvs = new org.springframework.beans.MutablePropertyValues();
        pvs.add("showMoreFromSite", "true");
        new org.springframework.web.bind.WebDataBinder(present, "bean").bind(pvs);
        org.junit.jupiter.api.Assertions.assertTrue(present.getShowMoreFromSite());
    }

    @Test
    void copyrightRoundTrips() {
        Weblog w = weblog();
        w.setCopyright("\u00a9 2024\u20132026 Maiia");
        WeblogConfigBean b = new WeblogConfigBean();
        b.copyFrom(w);
        assertEquals("\u00a9 2024\u20132026 Maiia", b.getCopyright());

        Weblog other = weblog();
        b.copyTo(other);
        assertEquals("\u00a9 2024\u20132026 Maiia", other.getCopyright());
    }

    @Test
    void blankCopyrightStoresNull() {
        Weblog w = weblog();
        w.setCopyright("old");
        WeblogConfigBean b = new WeblogConfigBean();
        b.copyFrom(w);
        b.setCopyright("   ");
        b.copyTo(w);
        assertNull(w.getCopyright());
    }
}
