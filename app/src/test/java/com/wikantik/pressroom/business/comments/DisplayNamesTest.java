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
package com.wikantik.pressroom.business.comments;

import java.util.List;

import com.wikantik.pressroom.business.UserManager;
import com.wikantik.pressroom.business.comments.DisplayNames.Refusal;
import com.wikantik.pressroom.pojos.User;
import com.wikantik.pressroom.util.HTMLSanitizer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The one display-name rule, shared by the account page and a reader's first
 * sign-in: a name is judged in exactly the form {@code User.setScreenName}
 * stores, it is 1 to 50 code points, and it is not a weblog member's.
 *
 * <p>Most tests run with markup stripping on ({@code weblogAdminsUntrusted},
 * the shipped default), because that is where the stored form differs from
 * what was typed: the stripper decodes entities after removing tags, so one
 * pass can produce new markup that the setter's own pass then strips.
 */
class DisplayNamesTest {

    private static final String OWNER = "Weblog Owner";

    private Boolean sanitizing;
    private UserManager users;

    @BeforeEach
    void setUp() throws Exception {
        sanitizing = HTMLSanitizer.xssEnabled;
        HTMLSanitizer.xssEnabled = Boolean.TRUE;
        users = mock(UserManager.class);
        when(users.getScreenNamesOfWeblogMembers()).thenReturn(List.of(OWNER));
    }

    @AfterEach
    void tearDown() {
        HTMLSanitizer.xssEnabled = sanitizing;
    }

    @Test
    void normalizingDropsControlsAndFormatsAndCollapsesBlanks() {
        assertEquals("a b c", DisplayNames.normalize(" \u0000a\u2028\u2003b\t\n c\u200F "));
        assertEquals("", DisplayNames.normalize(null));
        assertEquals("", DisplayNames.normalize("\u200B\u0007"));
    }

    /** The reviewer's first input: one pass gives a name nobody has; the setter's pass gives the owner's. */
    @Test
    void aNameIsJudgedAsTheSetterStoresIt() throws Exception {
        String stored = DisplayNames.storedForm("<i>Weblog Owner&lt;b&gt;&lt;/b&gt;</i>");

        assertEquals(OWNER, stored);
        assertEquals(stored, asTheSetterStores(stored), "the judged name is exactly what would be stored");
        assertEquals(Refusal.TAKEN, DisplayNames.refusal(stored, null, users));
    }

    /** The reviewer's second input: seven code points after one pass, nothing at all once stored. */
    @Test
    void aNameThatStoresEmptyIsTooShort() throws Exception {
        String stored = DisplayNames.storedForm("<i>&lt;b&gt;&lt;/b&gt;</i>");

        assertEquals("", stored);
        assertEquals(Refusal.LENGTH, DisplayNames.refusal(stored, null, users));
    }

    @Test
    void plainTextIsItsOwnStoredForm() throws Exception {
        assertEquals("Ann < Bob & co", DisplayNames.storedForm("  Ann <  Bob & co "));
        assertEquals("Ann Smith", DisplayNames.storedForm("<b>Ann</b>\u00A0Smith"));
        assertNull(DisplayNames.refusal("Ann Smith", null, users));
    }

    /** Every pass decodes one more level of entities while a '<' remains; a name that never settles is refused. */
    @Test
    void aNameThatNeverSettlesIsRefused() throws Exception {
        String nested = "x < y &" + "amp;".repeat(20) + "lt;";

        assertNull(DisplayNames.storedForm(nested));
        assertEquals(Refusal.UNSETTLED, DisplayNames.refusal(null, null, users));
    }

    @Test
    void withStrippingOffTheTypedNameIsStored() {
        HTMLSanitizer.xssEnabled = Boolean.FALSE;

        assertEquals("<b>Ann</b>", DisplayNames.storedForm(" <b>Ann</b> "));
    }

    @Test
    void fiftyCodePointsIsTheLimit() throws Exception {
        String fifty = "\uD83D\uDE00".repeat(50);
        assertNull(DisplayNames.refusal(fifty, null, users));
        assertEquals(Refusal.LENGTH, DisplayNames.refusal(fifty + "x", null, users));
    }

    @Test
    void cuttingKeepsAtMostFiftyCodePoints() {
        String long60 = "\uD83D\uDE00".repeat(45) + " abcdefghijklmnop";

        assertEquals("\uD83D\uDE00".repeat(45) + " abcd", DisplayNames.cut(DisplayNames.storedForm(long60)));
        assertEquals("Short", DisplayNames.cut("Short"));
        assertNull(DisplayNames.cut(null));
    }

    @Test
    void aMembersNameIsTakenWhateverItsCaseAndSpacing() throws Exception {
        assertEquals(Refusal.TAKEN, DisplayNames.refusal("weblog OWNER", null, users));
        when(users.getScreenNamesOfWeblogMembers()).thenReturn(List.of("  Weblog\u200B  Owner "));
        assertEquals(Refusal.TAKEN, DisplayNames.refusal(OWNER, null, users));
    }

    /**
     * Fullwidth and mathematical-bold letters draw the member's name in the
     * letters it is spelled with, so beside a comment they read as the member.
     * A character-by-character equalsIgnoreCase let both through, and missed
     * full case folding too (Strau\u00DF is STRAUSS).
     */
    @Test
    void aMembersNameIsTakenInCompatibilityLettersAndUnderFullCaseFolding() throws Exception {
        String fullwidth = shifted(OWNER, 0xFF21, 0xFF41);
        String mathematicalBold = shifted("WEBLOG owner", 0x1D400, 0x1D41A);

        assertEquals(Refusal.TAKEN, DisplayNames.refusal(DisplayNames.storedForm(fullwidth), null, users));
        assertEquals(Refusal.TAKEN, DisplayNames.refusal(DisplayNames.storedForm(mathematicalBold), null, users));
        when(users.getScreenNamesOfWeblogMembers()).thenReturn(List.of("Strau\u00DF"));
        assertEquals(Refusal.TAKEN, DisplayNames.refusal("STRAUSS", null, users));
        assertNull(DisplayNames.refusal("Strausberg", null, users), "a different name is still free");
    }

    /** {@code ascii} with A-Z moved to start at {@code upper} and a-z at {@code lower}. */
    private static String shifted(String ascii, int upper, int lower) {
        StringBuilder out = new StringBuilder();
        ascii.codePoints().forEach(c -> out.appendCodePoint(
                c >= 'A' && c <= 'Z' ? upper + c - 'A' : c >= 'a' && c <= 'z' ? lower + c - 'a' : c));
        return out.toString();
    }

    /** A member keeping its own name matches only itself; a reader taking it is still refused. */
    @Test
    void aMemberMayKeepItsOwnName() throws Exception {
        User owner = new User();
        owner.setUserName("owner");
        owner.setScreenName(OWNER);
        when(users.getWeblogPermissions(owner)).thenReturn(List.of(
                new com.wikantik.pressroom.pojos.WeblogPermission()));
        User reader = new User();
        reader.setUserName("reader");
        reader.setScreenName(OWNER);

        assertNull(DisplayNames.refusal(OWNER, owner, users));
        assertEquals(Refusal.TAKEN, DisplayNames.refusal(OWNER, reader, users),
                "a reader who already carries the name is not a member");
    }

    @Test
    void eachRefusalNamesItsMessage() {
        assertEquals("account.name.length", Refusal.LENGTH.messageKey());
        assertEquals("account.name.taken", Refusal.TAKEN.messageKey());
        assertEquals("account.name.invalid", Refusal.UNSETTLED.messageKey());
    }

    private static String asTheSetterStores(String name) {
        User probe = new User();
        probe.setScreenName(name);
        return probe.getScreenName();
    }
}
