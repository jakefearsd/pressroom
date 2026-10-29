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
package com.wikantik.pressroom.business;

import java.math.BigDecimal;
import java.util.Date;
import java.util.List;

import com.wikantik.pressroom.TestUtils;
import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.pojos.Business;
import com.wikantik.pressroom.pojos.User;
import com.wikantik.pressroom.pojos.Weblog;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The shared business record: round trip, touch-on-save, refuse-while-in-use. */
class BusinessManagerTest {

    private static final Date OLD = new Date(1_000_000_000_000L);

    private User user;
    private Weblog a;
    private Weblog b;
    private Weblog c;
    private Business biz;

    @BeforeEach
    void setUp() throws Exception {
        TestUtils.setupWeblogger();
        user = TestUtils.setupUser("bizuser");
        a = TestUtils.setupWeblog("bizbloga", user);
        b = TestUtils.setupWeblog("bizblogb", user);
        c = TestUtils.setupWeblog("bizblogc", user);
        TestUtils.endSession(true);

        biz = new Business();
        biz.setName("Casa do Mar");
        biz.setBusinessType(Business.BusinessType.LocalBusiness);
        biz.setWebsiteUrl("https://casa.example");
        biz.setBookingUrl("https://book.example/casa");
        biz.setTelephone("+351 912 345 678");
        biz.setEmail("hi@casa.example");
        biz.setLogoUrl("https://casa.example/logo.png");
        biz.setSameAs("https://a.example/x\n\n  https://b.example/y  \r\nhttps://c.example/z");
        biz.setAreaServed("Azores");
        biz.setDescription("A small guesthouse.");
        mgr().saveBusiness(biz);
        TestUtils.endSession(true);
    }

    @AfterEach
    void tearDown() throws Exception {
        TestUtils.teardownWeblog(a.getId());
        TestUtils.teardownWeblog(b.getId());
        TestUtils.teardownWeblog(c.getId());
        Business gone = mgr().getBusiness(biz.getId());
        if (gone != null) {
            mgr().removeBusiness(gone);
        }
        TestUtils.teardownUser(user.getUserName());
        TestUtils.endSession(true);
    }

    private static BusinessManager mgr() {
        return TestUtils.weblogger().getBusinessManager();
    }

    private void use(Weblog w, Business business) throws Exception {
        Weblog m = TestUtils.getManagedWebsite(w);
        m.setBusiness(business == null ? null : mgr().getBusiness(business.getId()));
        TestUtils.weblogger().getWeblogManager().saveWeblog(m);
        TestUtils.endSession(true);
    }

    @Test
    void saveAndReadBack() throws Exception {
        Business r = mgr().getBusiness(biz.getId());
        assertNotNull(r);
        assertEquals("Casa do Mar", r.getName());
        assertEquals(Business.BusinessType.LocalBusiness, r.getBusinessType());
        assertEquals("https://casa.example", r.getWebsiteUrl());
        assertEquals("https://book.example/casa", r.getBookingUrl());
        assertEquals("+351 912 345 678", r.getTelephone());
        assertEquals("hi@casa.example", r.getEmail());
        assertEquals("https://casa.example/logo.png", r.getLogoUrl());
        assertEquals("Azores", r.getAreaServed());
        assertEquals("A small guesthouse.", r.getDescription());
        assertNotNull(r.getCreated());
        assertNotNull(r.getLastModified());
        assertEquals(List.of("https://a.example/x", "https://b.example/y",
                "https://c.example/z"), r.getSameAsList());
        assertTrue(mgr().getBusinesses().stream()
                .anyMatch(x -> x.getId().equals(biz.getId())));
    }

    @Test
    void savingBusinessTouchesOnlyWeblogsUsingIt() throws Exception {
        use(a, biz);
        use(b, biz);
        for (Weblog w : List.of(a, b, c)) {
            Weblog m = TestUtils.getManagedWebsite(w);
            // not saveWeblog: it stamps lastModified itself. The managed
            // instance is dirty, so the flush at session end writes it.
            m.setLastModified(OLD);
        }
        TestUtils.endSession(true);

        Business x = mgr().getBusiness(biz.getId());
        x.setName("Casa do Mar II");
        mgr().saveBusiness(x);
        TestUtils.endSession(true);

        assertTrue(TestUtils.getManagedWebsite(a).getLastModified().after(OLD));
        assertTrue(TestUtils.getManagedWebsite(b).getLastModified().after(OLD));
        assertEquals(OLD.getTime(), TestUtils.getManagedWebsite(c).getLastModified().getTime());
    }

    @Test
    void removeRefusedWhileInUse() throws Exception {
        use(a, biz);
        Business x = mgr().getBusiness(biz.getId());
        assertThrows(WebloggerException.class, () -> mgr().removeBusiness(x));
        TestUtils.endSession(true);

        use(a, null);
        Business y = mgr().getBusiness(biz.getId());
        mgr().removeBusiness(y);
        TestUtils.endSession(true);
        assertNull(mgr().getBusiness(biz.getId()));
    }

    @Test
    void getWeblogsUsingListsOnlyTheBlogsThatPointAtIt() throws Exception {
        use(a, biz);
        use(b, biz);

        List<String> handles = mgr().getWeblogsUsing(mgr().getBusiness(biz.getId())).stream()
                .map(Weblog::getHandle).sorted().toList();

        assertEquals(List.of("bizbloga", "bizblogb"), handles);
    }

    @Test
    void countWeblogsUsing() throws Exception {
        use(a, biz);
        use(b, biz);
        assertEquals(2L, mgr().countWeblogsUsing(mgr().getBusiness(biz.getId())));
    }

    @Test
    void placeColumnsRoundTrip() throws Exception {
        Weblog m = TestUtils.getManagedWebsite(a);
        m.setPlaceType("LodgingBusiness");
        m.setPlaceLocality("Ponta Delgada");
        m.setPlaceRegion("Sao Miguel");
        m.setPlaceCountry("PT");
        m.setPlaceLat(new BigDecimal("38.71234"));
        m.setPlaceLng(new BigDecimal("-9.145"));
        m.setBookingUrl("https://book.example/a");
        TestUtils.weblogger().getWeblogManager().saveWeblog(m);
        TestUtils.endSession(true);

        Weblog r = TestUtils.getManagedWebsite(a);
        assertEquals("LodgingBusiness", r.getPlaceType());
        assertEquals("Ponta Delgada", r.getPlaceLocality());
        assertEquals("Sao Miguel", r.getPlaceRegion());
        assertEquals("PT", r.getPlaceCountry());
        assertEquals(new BigDecimal("38.71"), r.getPlaceLat());
        assertEquals(new BigDecimal("-9.15"), r.getPlaceLng());
        assertEquals("https://book.example/a", r.getBookingUrl());
    }
}
