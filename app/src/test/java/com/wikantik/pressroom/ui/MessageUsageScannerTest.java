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
package com.wikantik.pressroom.ui;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Properties;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The scanner behind {@link MessageKeyTest} and {@link MessagePlaceholderContractTest}
 * sees the keys the shared rule classes name (spec
 * 2026-11-20-jsp-rules-and-view-models-design, final review I2): a {@code
 * RuleViolation.invalid/conflict} key with its {@code List.of} argument count,
 * and a rule's "key or null" return. Before, both were invisible, so a typo'd
 * key in a rule class printed the raw key on the screen and no ratchet failed.
 */
class MessageUsageScannerTest {

    private static final String RULES = MessageUsageScanner.RULES_PACKAGE + "PlantedRules.java";

    @Test
    void aTypoedRuleViolationKeyIsSeenWithItsArgumentCount() throws IOException {
        List<MessageUsageScanner.Usage> usages = MessageUsageScanner.scanJavaSource("""
                class PlantedRules {
                    static RuleViolation taken(String name) {
                        return RuleViolation.conflict(NAME, "categoryForm.error.dupliateName", List.of(name, 2),
                                ProblemCode.CATEGORY_NAME_IN_USE, name);
                    }
                    static RuleViolation blank() {
                        return RuleViolation.invalid(NAME, "categoryForm.error.invalidNam", List.of(),
                                ProblemCode.FIELD_BLANK, NAME);
                    }
                }
                """, RULES);

        assertEquals(List.of("categoryForm.error.dupliateName=2", "categoryForm.error.invalidNam=0"),
                usages.stream().map(u -> u.key() + "=" + u.argCount()).toList());
        assertMissingFromTheBundle("categoryForm.error.dupliateName");
    }

    @Test
    void aTypoedKeyOrNullReturnOfARuleClassIsSeen() throws IOException {
        String source = """
                class PlantedRules {
                    static String levelProblem(boolean level) {
                        return level ? null : "userWeblogs.error.levl";
                    }
                    static String selfProblem(boolean own) {
                        return own ? "memberPermissions.noSelfModification" : null;
                    }
                }
                """;

        assertEquals(List.of("userWeblogs.error.levl=0", "memberPermissions.noSelfModification=0"),
                MessageUsageScanner.scanJavaSource(source, RULES).stream()
                        .map(u -> u.key() + "=" + u.argCount()).toList());
        assertMissingFromTheBundle("userWeblogs.error.levl");
        // Outside the rule classes the shape is not taken for a key: a dotted literal can be a file name.
        assertTrue(MessageUsageScanner.scanJavaSource(source, "com/wikantik/pressroom/util/Planted.java").isEmpty());
    }

    @Test
    void anArgumentListThatIsNotAListOfLiteralIsUnresolved() {
        List<MessageUsageScanner.Usage> usages = MessageUsageScanner.scanJavaSource(
                "class P { Object x = RuleViolation.invalid(F, \"a.key\", args, ProblemCode.FIELD_BLANK); }", RULES);

        assertEquals(List.of(MessageUsageScanner.UNRESOLVED), usages.stream().map(u -> u.argCount()).toList());
    }

    /** The real rule classes' keys now reach both ratchets. */
    @Test
    void theRuleClassesKeysAreScanned() throws IOException {
        List<String> seen = MessageUsageScanner.scanJavaSources().stream()
                .map(u -> u.key() + "=" + u.argCount()).toList();

        assertTrue(seen.contains("categoryForm.error.duplicateName=1"), "RuleViolation.conflict in CategoryRules");
        assertTrue(seen.contains("personForm.error.passwordTooShort=0"), "key-or-null return in AccountRules");
        assertTrue(seen.contains("userWeblogs.error.level=0"), "key-or-null return in Memberships");
        assertTrue(seen.contains("personForm.error.userNameTooLong=0"), "AccountRules' too-long refusals");
    }

    private static void assertMissingFromTheBundle(String key) throws IOException {
        Properties bundle = new Properties();
        try (InputStream in = MessageUsageScannerTest.class.getResourceAsStream("/ApplicationResources.properties")) {
            assertTrue(in != null, "bundle not on the classpath");
            bundle.load(in);
        }
        assertFalse(bundle.isEmpty(), "bundle is empty");
        // So MessageKeyTest.everyMessageKeyNamedInJavaExists would name it, had it been real source.
        assertFalse(bundle.containsKey(key), key + " is in the bundle; the plant proves nothing");
    }
}
