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
package com.wikantik.pressroom.pojos;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * {@code copy()} must carry every persistent field of a weblog and an entry
 * (the preview clones were silently dropping tags, geo, JSON-LD, siteSpace,
 * business, ...). The reflection tests fail when a newly added field is
 * neither copied nor named in the exclusion list of its class.
 */
class WeblogCopyTest {

    /** Per-load / per-save bookkeeping that belongs to the original only. */
    private static final Set<String> ENTRY_EXCLUDED = Set.of(
            "loadedContent", "loadedStatus", "loadedAnchor", "removedTags", "addedTags");
    private static final Set<String> WEBLOG_EXCLUDED = Set.of("mediaFileDirectories");

    @Test
    void entryCopyCarriesEveryField() throws Exception {
        WeblogEntry src = new WeblogEntry();
        populate(src, ENTRY_EXCLUDED);
        WeblogEntry copy = src.copy();
        assertNotSame(src, copy);
        assertFieldsCopied(src, copy, ENTRY_EXCLUDED);
    }

    @Test
    void entryCopyDoesNotAliasMutableCollections() throws Exception {
        WeblogEntry src = new WeblogEntry();
        src.setTagsAsString("alpha beta");
        WeblogEntryAttribute att = new WeblogEntryAttribute();
        att.setName("k");
        att.setValue("v");
        src.getEntryAttributes().add(att);
        WeblogEntry copy = src.copy();
        assertEquals(src.getTagsAsString(), copy.getTagsAsString());
        assertEquals("v", copy.findEntryAttribute("k"));
        copy.addTag("gamma");
        copy.putEntryAttribute("k", "changed");
        assertEquals("alpha beta", src.getTagsAsString());
        assertEquals("v", src.findEntryAttribute("k"));
    }

    @Test
    void setDataDelegatesToCopySemantics() throws Exception {
        WeblogEntry src = new WeblogEntry();
        populate(src, ENTRY_EXCLUDED);
        WeblogEntry target = new WeblogEntry();
        target.setData(src);
        assertFieldsCopied(src, target, ENTRY_EXCLUDED);
        assertNotNull(new WeblogEntry(src).getGeoLatitude());
    }

    @Test
    void copyAsDraftKeepsTravelFieldsAndClearsIdentity() throws Exception {
        WeblogEntry src = new WeblogEntry();
        src.setId("orig");
        src.setTitle("t");
        src.setGeoLatitude(1.5);
        src.setGeoLongitude(2.5);
        src.setJsonLdType(JsonLdType.values()[0]);
        src.setEventLocation("Paris");
        src.setEventStart(new Timestamp(1000));
        src.setEventEnd(new Timestamp(2000));
        src.setNewsletterSentAt(new Timestamp(3000));
        src.setStatus(WeblogEntry.PubStatus.PUBLISHED);
        src.setTagsAsString("one two");
        WeblogEntry d = src.copyAsDraft("copy");
        assertEquals(1.5, d.getGeoLatitude());
        assertEquals(2.5, d.getGeoLongitude());
        assertEquals(src.getJsonLdType(), d.getJsonLdType());
        assertEquals("Paris", d.getEventLocation());
        assertEquals(new Timestamp(1000), d.getEventStart());
        assertEquals(new Timestamp(2000), d.getEventEnd());
        assertNull(d.getNewsletterSentAt());
        assertEquals(WeblogEntry.PubStatus.DRAFT, d.getStatus());
        assertTrue(!"orig".equals(d.getId()));
        assertEquals("copy", d.getTitle());
        assertEquals("one two", d.getTagsAsString());
        assertTrue(d.getTags().stream().allMatch(t -> src.getTags().stream().noneMatch(o -> o == t)));
    }

    @Test
    void weblogCopyCarriesEveryField() throws Exception {
        Weblog src = new Weblog();
        populate(src, WEBLOG_EXCLUDED);
        Weblog copy = src.copy();
        assertNotSame(src, copy);
        assertFieldsCopied(src, copy, WEBLOG_EXCLUDED);
    }

    @Test
    void weblogSetDataCarriesTheFieldsItDropped() {
        Weblog src = new Weblog();
        src.setSiteSpace(true);
        src.setCustomDomain("example.org");
        src.setAbout("about");
        src.setIconPath("icon.png");
        src.setBookingUrl("https://b.example");
        src.setNewsletterListUuid("uuid");
        src.setPlaceLocality("Lyon");
        Business b = new Business();
        src.setBusiness(b);
        Weblog t = new Weblog();
        t.setData(src);
        assertTrue(t.isSiteSpace());
        assertEquals("example.org", t.getCustomDomain());
        assertEquals("about", t.getAbout());
        assertEquals("icon.png", t.getIconPath());
        assertEquals("https://b.example", t.getBookingUrl());
        assertEquals("uuid", t.getNewsletterListUuid());
        assertEquals("Lyon", t.getPlaceLocality());
        assertEquals(b, t.getBusiness());
    }

    // ------------------------------------------------------------ helpers

    private static List<Field> fields(Class<?> type, Set<String> excluded) {
        List<Field> out = new ArrayList<>();
        for (Field f : type.getDeclaredFields()) {
            if (!Modifier.isStatic(f.getModifiers()) && !f.isSynthetic() && !excluded.contains(f.getName())) {
                f.setAccessible(true);
                out.add(f);
            }
        }
        return out;
    }

    private static void populate(Object target, Set<String> excluded) throws Exception {
        int n = 1;
        for (Field f : fields(target.getClass(), excluded)) {
            f.set(target, sample(f, n++));
        }
    }

    private static Object sample(Field f, int n) throws Exception {
        Class<?> t = f.getType();
        if (t == String.class) {
            return "v" + n;
        } else if (t == Boolean.class || t == boolean.class) {
            return n % 2 == 0;
        } else if (t == Integer.class || t == int.class) {
            return n + 100;
        } else if (t == Double.class) {
            return n + 0.5;
        } else if (t == BigDecimal.class) {
            return new BigDecimal(n + ".25");
        } else if (t == Timestamp.class) {
            return new Timestamp(86_400_000L * n);
        } else if (t == Date.class) {
            return new Date(86_400_000L * n);
        } else if (t.isEnum()) {
            return t.getEnumConstants()[t.getEnumConstants().length - 1];
        } else if (t == Set.class) {
            Class<?> el = (Class<?>) ((ParameterizedType) f.getGenericType()).getActualTypeArguments()[0];
            Set<Object> s = el == WeblogEntryAttribute.class ? new TreeSet<>() : new HashSet<>();
            if (el == WeblogEntryTag.class) {
                WeblogEntryTag tag = new WeblogEntryTag();
                tag.setName("tag" + n);
                tag.setTime(new Timestamp(5));
                s.add(tag);
            } else if (el == WeblogEntryAttribute.class) {
                WeblogEntryAttribute a = new WeblogEntryAttribute();
                a.setName("att" + n);
                a.setValue("val");
                s.add(a);
            }
            return s;
        } else if (t == List.class) {
            List<Object> l = new ArrayList<>();
            l.add(new WeblogCategory());
            return l;
        } else {
            return t.getDeclaredConstructor().newInstance();
        }
    }

    private static void assertFieldsCopied(Object src, Object copy, Set<String> excluded) throws Exception {
        for (Field f : fields(src.getClass(), excluded)) {
            Object a = f.get(src);
            Object b = f.get(copy);
            if (a instanceof Collection<?> ca) {
                assertNotNull(b, f.getName());
                assertNotSame(a, b, f.getName() + " aliased");
                assertEquals(ca.size(), ((Collection<?>) b).size(), f.getName());
            } else if (!Objects.equals(a, b)) {
                fail(src.getClass().getSimpleName() + "." + f.getName()
                        + " is not copied: add it to copy() or to the exclusion list");
            }
        }
    }
}
