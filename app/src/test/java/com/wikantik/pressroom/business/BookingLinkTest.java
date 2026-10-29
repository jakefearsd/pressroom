/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  The ASF licenses this file to You
 * under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License.  You may obtain a copy
 * of the License at
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
package com.wikantik.pressroom.business;

import com.wikantik.pressroom.pojos.Business;
import com.wikantik.pressroom.pojos.Weblog;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

/** The blog's booking link: resolution order and UTM tagging. */
class BookingLinkTest {

    private static Weblog weblog(String own, String business) {
        Weblog w = new Weblog();
        w.setHandle("stay");
        w.setBookingUrl(own);
        if (business != null) {
            Business b = new Business();
            b.setBookingUrl(business);
            w.setBusiness(b);
        }
        return w;
    }

    @Test
    void theWeblogsOwnUrlWinsOverTheBusinesss() {
        assertEquals("https://own.example.com/b",
                BookingLink.resolve(weblog("https://own.example.com/b", "https://biz.example.com/b")));
    }

    @Test
    void theBusinessUrlIsTheFallback() {
        assertEquals("https://biz.example.com/b",
                BookingLink.resolve(weblog(null, "https://biz.example.com/b")));
        assertEquals("https://biz.example.com/b",
                BookingLink.resolve(weblog("  ", "https://biz.example.com/b")));
    }

    @Test
    void nothingConfiguredResolvesToNull() {
        assertNull(BookingLink.resolve(weblog(null, null)));
        assertNull(BookingLink.resolve(weblog(null, "")));
        assertNull(BookingLink.resolve(null));
    }

    @Test
    void utmGoesBeforeTheFragmentAndNeverDuplicatesAnExistingParameter() {
        assertEquals("https://b.example.com/x?ref=a&utm_source=keep&utm_medium=blog&utm_campaign=s#dates",
                BookingLink.withUtmParams("https://b.example.com/x?ref=a&utm_source=keep#dates",
                        weblog(null, null), "s"));
    }

    @Test
    void destHostDropsUserinfoPortPathAndCase() {
        assertEquals("book.example.com",
                BookingLink.destHost("https://User@Book.Example.com:8443/p?q=1"));
        assertNull(BookingLink.destHost("not a url"));
    }

    /**
     * java.net.URI treats "my_shop.example.com" as a registry-based
     * authority and getHost() is null; the host is then read from the raw
     * authority. Whether such a URL can reach destHost at all is pinned
     * separately below (it cannot through isHttpUrl).
     */
    @Test
    void destHostOfAnUnderscoreHostIsReadFromTheRawAuthority() {
        assertEquals("my_shop.example.com", BookingLink.destHost("https://my_shop.example.com/book"));
        assertEquals("my_shop.example.com",
                BookingLink.destHost("https://User:pw@My_Shop.Example.com:8443/p?q=1#f"));
        assertEquals("my_shop.example.com", BookingLink.destHost("https://my_shop.example.com?x=1"));
    }

    @Test
    void destHostOfAnUnparseableOrHostlessUrlIsNull() {
        assertNull(BookingLink.destHost("https://"));
        assertNull(BookingLink.destHost("mailto:a@b.com"));
        assertNull(BookingLink.destHost(null));
    }

    /** Characterisation: the validator both callers use already refuses an underscore host. */
    @Test
    void anUnderscoreHostNeverPassesTheBookingUrlValidator() {
        assertFalse(BookingLink.isHttpUrl("https://my_shop.example.com/book"));
    }

    @Test
    void aNonHttpWeblogUrlFallsThroughToAValidBusinessUrl() {
        assertEquals("https://biz.example.com/b",
                BookingLink.resolve(weblog("javascript:alert(1)", "https://biz.example.com/b")));
    }

    @Test
    void whenNeitherUrlIsHttpTheResultIsNull() {
        assertNull(BookingLink.resolve(weblog("javascript:alert(1)", "data:text/html,x")));
        assertNull(BookingLink.resolve(weblog("/relative/path", "ftp://biz.example.com/b")));
    }
}
