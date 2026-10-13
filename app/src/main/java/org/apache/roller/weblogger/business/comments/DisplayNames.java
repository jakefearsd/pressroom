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
package org.apache.roller.weblogger.business.comments;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

import org.apache.roller.weblogger.WebloggerException;
import org.apache.roller.weblogger.business.UserManager;
import org.apache.roller.weblogger.pojos.User;

/**
 * The one rule for a commenter's display name (the screen name), shared by
 * the account page and a reader's first sign-in: judged in exactly the form
 * it is stored in, 1 to {@link #MAX_CODE_POINTS} code points, and not the
 * screen name of a weblog member (ruling R8).
 *
 * <p><b>Judged as stored.</b> {@code User.setScreenName} strips markup when
 * {@code weblogAdminsUntrusted} is on, and that stripper decodes entities
 * after removing tags, so it is not idempotent: one pass over
 * {@code <i>Owner&lt;b&gt;&lt;/b&gt;</i>} leaves {@code Owner<b></b>}, which
 * the setter's own pass turns into {@code Owner}. {@link #storedForm} repeats
 * normalising and the setter until the setter keeps the name unchanged, so
 * the name judged is the name stored.
 */
public final class DisplayNames {

    /** The longest display name, in code points. */
    public static final int MAX_CODE_POINTS = 50;

    /** Each pass decodes one level of entities; a real name settles in two or three. */
    private static final int MAX_PASSES = 8;

    private static final Pattern SPACES = Pattern.compile(" {2,}");

    /** Why a name may not be a display name, each with the account page's message. */
    public enum Refusal {
        /** Empty, or longer than {@link #MAX_CODE_POINTS} code points, once stored. */
        LENGTH("account.name.length"),
        /** A weblog member's screen name, case, spacing and compatibility letters aside. */
        TAKEN("account.name.taken"),
        /** Markup stripping never settled on a stored form. */
        UNSETTLED("account.name.invalid");

        private final String messageKey;

        Refusal(String messageKey) {
            this.messageKey = messageKey;
        }

        /** The message key the account page shows. */
        public String messageKey() {
            return messageKey;
        }
    }

    private DisplayNames() {
    }

    /**
     * Control and format characters dropped, every kind of blank made one
     * space, runs of them collapsed, trimmed. Null is "".
     */
    public static String normalize(String raw) {
        if (raw == null) {
            return "";
        }
        StringBuilder kept = new StringBuilder(raw.length());
        raw.codePoints().forEach(cp -> {
            if (Character.isWhitespace(cp) || Character.isSpaceChar(cp)) {
                kept.append(' ');
            } else if (!Character.isISOControl(cp) && Character.getType(cp) != Character.FORMAT) {
                kept.appendCodePoint(cp);
            }
        });
        return SPACES.matcher(kept).replaceAll(" ").strip();
    }

    /**
     * The name exactly as {@code User.setScreenName} would store it: normalised,
     * then normalised and passed through the setter again until the setter
     * keeps it unchanged. Null when that never settles, which is refused.
     */
    public static String storedForm(String raw) {
        String name = normalize(raw);
        for (int pass = 0; pass < MAX_PASSES; pass++) {
            String kept = asTheSetterStores(name);
            if (name.equals(kept)) {
                return name;
            }
            name = normalize(kept);
        }
        return null;
    }

    /**
     * A stored-form name cut to {@link #MAX_CODE_POINTS} code points (and
     * brought back to a stored form), for a name the account holder did not
     * type: a sign-in claim. Null stays null.
     */
    public static String cut(String stored) {
        if (stored == null || stored.codePointCount(0, stored.length()) <= MAX_CODE_POINTS) {
            return stored;
        }
        return storedForm(stored.substring(0, stored.offsetByCodePoints(0, MAX_CODE_POINTS)));
    }

    /**
     * Why {@code stored} (a {@link #storedForm}, null when it did not settle)
     * may not be this account's display name, or null when it may.
     * {@code account} is null for an account not stored yet. A member keeping
     * its own current name matches only itself, which is no impersonation.
     *
     * @throws WebloggerException when the members cannot be read: the caller refuses
     */
    public static Refusal refusal(String stored, User account, UserManager users) throws WebloggerException {
        if (stored == null) {
            return Refusal.UNSETTLED;
        }
        int length = stored.codePointCount(0, stored.length());
        if (length < 1 || length > MAX_CODE_POINTS) {
            return Refusal.LENGTH;
        }
        boolean taken = users.getScreenNamesOfWeblogMembers().stream().anyMatch(m -> sameName(m, stored));
        if (!taken) {
            return null;
        }
        boolean keepingOwn = account != null && sameName(account.getScreenName(), stored)
                && !users.getWeblogPermissions(account).isEmpty();
        return keepingOwn ? null : Refusal.TAKEN;
    }

    private static boolean sameName(String existing, String name) {
        return existing != null && fold(normalize(existing)).equals(fold(name));
    }

    /**
     * The form two names are compared in, as near to Unicode's
     * toNFKC_Casefold as the JDK reaches: compatibility letters made plain
     * (fullwidth and mathematical letters are the letters they draw), full
     * case folding (upper then lower, so Strau&szlig; is strauss), and NFKC
     * again for anything the folding produced. Letters from another script
     * that merely look alike (a Cyrillic a) are not caught: that needs
     * Unicode's confusables data, which the JDK does not carry.
     */
    private static String fold(String name) {
        String plain = Normalizer.normalize(name, Normalizer.Form.NFKC);
        String folded = plain.toUpperCase(Locale.ROOT).toLowerCase(Locale.ROOT);
        return Normalizer.normalize(folded, Normalizer.Form.NFKC);
    }

    private static String asTheSetterStores(String name) {
        User probe = new User();
        probe.setScreenName(name);
        return probe.getScreenName();
    }
}
