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
package com.wikantik.pressroom.business;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.pojos.WeblogEntry;
import com.wikantik.pressroom.pojos.WeblogPage;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Who may be named as a part of a strand: same blog, free or already here, once. */
class StrandRulesTest {

    private final Weblog blog = blog("b1");
    private final WeblogPage strand = strand("s1", blog);

    private static Weblog blog(String id) {
        Weblog w = new Weblog();
        w.setId(id);
        return w;
    }

    private static WeblogPage strand(String id, Weblog w) {
        WeblogPage p = new WeblogPage();
        p.setId(id);
        p.setWeblog(w);
        p.setStrand(Boolean.TRUE);
        return p;
    }

    private static WeblogEntry post(String id, Weblog w, String strandId) {
        WeblogEntry e = new WeblogEntry();
        e.setId(id);
        e.setWebsite(w);
        e.setStrandPageId(strandId);
        return e;
    }

    @Test
    void anAcceptableListIsEmptyEvenWithAPostAlreadyInThisStrand() {
        List<WeblogEntry> req = List.of(post("a", blog, null), post("b", blog, "s1"));
        assertTrue(StrandRules.refusals(strand, req, List.of("a", "b")).isEmpty());
    }

    @Test
    void anEmptyListIsAcceptable() {
        assertTrue(StrandRules.refusals(strand, List.of(), List.of()).isEmpty());
    }

    @Test
    void anUnknownPostIsRefused() {
        Map<String, String> r = StrandRules.refusals(strand, Arrays.asList((WeblogEntry) null), List.of("zz"));
        assertEquals(Map.of("zz", "strand.error.unknownPost"), r);
    }

    @Test
    void aPostOfAnotherBlogIsRefused() {
        List<WeblogEntry> req = List.of(post("a", blog("b2"), null));
        assertEquals(Map.of("a", "strand.error.otherBlog"), StrandRules.refusals(strand, req, List.of("a")));
    }

    @Test
    void aPostInAnotherStrandIsRefused() {
        List<WeblogEntry> req = List.of(post("a", blog, "s2"));
        assertEquals(Map.of("a", "strand.error.inAnotherStrand"), StrandRules.refusals(strand, req, List.of("a")));
    }

    @Test
    void aPostListedTwiceIsRefusedOnce() {
        WeblogEntry a = post("a", blog, null);
        Map<String, String> r = StrandRules.refusals(strand, List.of(a, a), List.of("a", "a"));
        assertEquals(Map.of("a", "strand.error.duplicate"), r);
    }

    @Test
    void anUnknownPostListedTwiceKeepsItsFirstReason() {
        Map<String, String> r = StrandRules.refusals(strand, Arrays.asList(null, null), List.of("zz", "zz"));
        assertEquals(Map.of("zz", "strand.error.unknownPost"), r);
    }
}
