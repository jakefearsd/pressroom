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

package com.wikantik.pressroom.util;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Test regex utils.
 *
 * <p>These run over rendered entry text to keep addresses away from harvesters:
 * a mailto: link is hex-escaped so browsers still follow it, and a plain-text
 * address is rewritten into something only a human reads. Both transformations
 * are visible on the published page, so the exact output is a contract.
 */
public class RegexUtilTest  {


    @Test
    public void testEncodingEmail() {
        // test mailto: escaping
        String test = "test <a href='mailto:this@email.com'>email</a> string";
        String expect = "test <a href='mailto:%74%68%69%73%40%65%6d%61%69%6c%2e%63%6f%6d'>email</a> string";
        String result = RegexUtil.encodeEmail(test) ;
        //System.out.println(result);
        assertEquals(expect, result);
    }

    @Test
    public void testObfuscateEmail() {
        // test "plaintext" escaping
        String test = "this@email.com";
        String expect = "this-AT-email-DOT-com";
        String result = RegexUtil.encodeEmail(test);
        assertEquals(expect, result);
    }

    @Test
    public void testHexEmail() {
        // test hex & obfuscate together
        String test = "test <a href='mailto:this@email.com'>this@email.com</a> string, and this@email.com";
        String expect = "test <a href='mailto:%74%68%69%73%40%65%6d%61%69%6c%2e%63%6f%6d'>this-AT-email-DOT-com</a> string, and this-AT-email-DOT-com";
        String result = RegexUtil.encodeEmail(test);
        //System.out.println(result);
        assertEquals(expect, result);
    }

    @Test
    public void textWithoutAnEmailAddressIsUntouched() {
        // The transformation runs over every rendered entry, so it must be a
        // no-op for the overwhelming majority of them.
        String text = "no addresses here, just an @ sign and a dot.";
        assertEquals(text, RegexUtil.encodeEmail(text));
    }

    @Test
    public void obfuscatesEveryAddressInTheTextNotJustTheFirst() {
        assertEquals("one-AT-a-DOT-com and two-AT-b-DOT-org",
                RegexUtil.obfuscateEmail("one@a.com and two@b.org"));
    }

    @Test
    public void obfuscationKeepsTheSubdomainAndOnlyRewritesTheLastDot() {
        // "user@mail.example.com" must stay recognisable to a human reader.
        assertEquals("user-AT-mail.example-DOT-com", RegexUtil.obfuscateEmail("user@mail.example.com"));
    }

    @Test
    public void hexEncodingIsLowercaseAndPercentPrefixedSoBrowsersDecodeIt() {
        // Every byte becomes %xx; anything else and the mailto: link stops
        // working in the browser.
        assertEquals("%61%40%62%2e%63%6f%6d", RegexUtil.encode("a@b.com"));
        assertEquals("", RegexUtil.encode(""));
    }

    @Test
    public void hexEncodingUsesUtf8ForNonAsciiLocalParts() {
        // 'é' is two bytes in UTF-8 (c3 a9); encoding it as a single byte
        // would produce a link to a different address.
        assertEquals("%c3%a9", RegexUtil.encode("é"));
    }

    @Test
    public void getMatchesCollectsEveryOccurrenceOfTheRequestedGroup() {
        Pattern pattern = Pattern.compile("<(\\w+)>");
        List<String> matches = RegexUtil.getMatches(pattern, "<a> text <b> more <c>", 1);

        assertEquals(List.of("a", "b", "c"), matches);
    }

    @Test
    public void getMatchesReturnsAnEmptyListRatherThanNullWhenNothingMatches() {
        // Callers iterate the result directly.
        assertTrue(RegexUtil.getMatches(Pattern.compile("<(\\w+)>"), "nothing here", 1).isEmpty());
    }

    @Test
    public void theMailtoPatternRequiresATopLevelDomain() {
        // Guards against rewriting "mailto:webmaster" style intranet links,
        // which the hex encoding would break.
        assertTrue(RegexUtil.MAILTO_PATTERN.matcher("mailto:a@b.com").find());
        assertTrue(RegexUtil.EMAIL_PATTERN.matcher("a@b.com").find());
        assertEquals("mailto:webmaster", RegexUtil.encodeEmail("mailto:webmaster"));
    }
}
