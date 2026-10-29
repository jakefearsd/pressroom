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

package com.wikantik.pressroom.ui.rendering.util.cache;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.wikantik.pressroom.util.Utilities;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Tests the pieces the rendering caches build their keys out of.
 *
 * Every one of these values is text the visitor chose -- a tag, a search term,
 * a query parameter. The rule they all have to obey is that a value can never
 * spill out of its own segment of the key, because two requests that produce
 * one key means one visitor is served the page rendered for another.
 */
public class CacheKeysTest {

    @Test
    public void encodingRemovesTheSegmentDelimiter() {
        assertEquals("a%2Fb", CacheKeys.encode("a/b"),
                "'/' separates key segments, so an encoded value must not contain one");
        assertNotEquals(CacheKeys.encode("a/b"), CacheKeys.encode("a") + "/" + CacheKeys.encode("b"),
                "and a value containing a slash must not encode to the same thing as two "
                        + "separate segments");
    }

    @Test
    public void encodingRemovesTheTagDelimiter() {
        assertEquals("a%20b", CacheKeys.encode("a b"),
                "A space must not encode to '+': '+' separates tags from one another, so "
                        + "the tag 'a b' would otherwise be indistinguishable from the two "
                        + "tags 'a' and 'b'");
    }

    @Test
    public void encodingLeavesOrdinaryValuesAlone() {
        assertEquals("MyCategory", CacheKeys.encode("MyCategory"),
                "Encoding must not mangle ordinary values -- keys are read by humans "
                        + "debugging a cache");
    }

    @Test
    public void tagOrderDoesNotChangeTheKey() {
        assertEquals(CacheKeys.tags(List.of("apple", "banana")), CacheKeys.tags(List.of("banana", "apple")),
                "Asking for the same tags in a different order selects the same entries "
                        + "and must hit the same cache entry");
    }

    @Test
    public void differentTagsGiveDifferentKeys() {
        assertNotEquals(CacheKeys.tags(List.of("apple")), CacheKeys.tags(List.of("banana")),
                "Different tags select different entries and must not share a key");
        assertNotEquals(CacheKeys.tags(List.of("apple", "banana")), CacheKeys.tags(List.of("apple")),
                "Narrowing by a second tag changes what is rendered");
    }

    @Test
    public void aTagContainingADelimiterCannotImpersonateTwoTags() {
        // reachable as /tags/a%2Bb or ?tags=... : the tag text is decoded
        // before it reaches us, so it can contain anything
        assertNotEquals(CacheKeys.tags(List.of("a+b")), CacheKeys.tags(List.of("a", "b")),
                "One tag written 'a+b' must not produce the key of the two tags 'a' and 'b'");
        assertNotEquals(CacheKeys.tags(List.of("a/b")), CacheKeys.tags(List.of("a")) + "/b",
                "and a tag containing a slash must not be able to close its own segment");
    }

    @Test
    public void parameterOrderDoesNotChangeTheKey() {
        Map<String, String[]> oneOrder = new LinkedHashMap<>();
        oneOrder.put("colour", new String[]{"red"});
        oneOrder.put("size", new String[]{"large"});

        Map<String, String[]> otherOrder = new LinkedHashMap<>();
        otherOrder.put("size", new String[]{"large"});
        otherOrder.put("colour", new String[]{"red"});

        assertEquals(CacheKeys.params(oneOrder), CacheKeys.params(otherOrder),
                "?colour=red&size=large and ?size=large&colour=red are the same request and "
                        + "must share a cache entry, whatever order the parameter map "
                        + "happens to iterate in");
    }

    @Test
    public void differentParameterValuesGiveDifferentKeys() {
        Map<String, String[]> red = Map.of("colour", new String[]{"red"});
        Map<String, String[]> blue = Map.of("colour", new String[]{"blue"});

        assertNotEquals(CacheKeys.params(red), CacheKeys.params(blue),
                "A custom page template can read its query parameters, so two requests "
                        + "with different parameters render differently and must not share "
                        + "a cache entry");
    }

    @Test
    public void everyValueOfARepeatedParameterCountsTowardsTheKey() {
        Map<String, String[]> ab = Map.of("tag", new String[]{"a", "b"});
        Map<String, String[]> ac = Map.of("tag", new String[]{"a", "c"});

        assertNotEquals(CacheKeys.params(ab), CacheKeys.params(ac),
                "?tag=a&tag=b must not be served the page rendered for ?tag=a&tag=c. "
                        + "Only the first value of a repeated parameter used to reach the "
                        + "key, so these two collided.");
    }

    @Test
    public void aParameterCannotImpersonateTheSeparatorsAroundIt() {
        Map<String, String[]> nameEqualsValue = Map.of("a=b", new String[]{"c"});
        Map<String, String[]> plainPair = Map.of("a", new String[]{"b=c"});

        assertNotEquals(CacheKeys.params(nameEqualsValue), CacheKeys.params(plainPair),
                "?a%3Db=c and ?a=b%3Dc are different requests");

        Map<String, String[]> commaInValue = Map.of("a", new String[]{"1,b=2"});
        Map<String, String[]> twoParams = new HashMap<>();
        twoParams.put("a", new String[]{"1"});
        twoParams.put("b", new String[]{"2"});

        // a comma in a value is entirely ordinary -- a list of ids, a decimal
        // written the European way -- and it used to end the parameter
        assertNotEquals(CacheKeys.params(commaInValue), CacheKeys.params(twoParams),
                "?a=1%2Cb%3D2 must not produce the key of ?a=1&b=2");
    }

    @Test
    public void parametersWithNothingUsableAreSkippedRatherThanThrowing() {
        Map<String, String[]> params = new HashMap<>();
        params.put(null, new String[]{"value"});
        params.put("present", null);

        // a Map can hold either of these; a NullPointerException while building
        // a cache key would take out the page, not just its caching
        assertEquals(CacheKeys.params(new HashMap<>()), CacheKeys.params(params),
                "Entries with no usable name or value contribute nothing to the key");
    }

    @Test
    public void parametersAreEncodedAsASortedCommaSeparatedList() {
        Map<String, String[]> params = new LinkedHashMap<>();
        params.put("size", new String[]{"large"});
        params.put("colour", new String[]{"red", "blue"});

        // spelled out because the shape is the guarantee: one entry per
        // parameter, in name order, with every value of each
        assertEquals(Utilities.toBase64("colour=red&blue,size=large".getBytes(StandardCharsets.UTF_8)),
                CacheKeys.params(params),
                "Parameters must be encoded as name=value pairs, sorted by name, "
                        + "separated by commas, with repeated values separated by '&'");
    }

    @Test
    public void anEmptyParameterSetEncodesToSomethingHarmless() {
        assertEquals("", CacheKeys.params(new HashMap<>()),
                "No parameters means no parameter segment. This used to throw a "
                        + "StringIndexOutOfBoundsException when every entry was skipped.");
    }
}
