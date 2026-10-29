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

package com.wikantik.pressroom.business.startup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/** Spec acceptance criterion 8: the runbook documents the tool that ships. */
class AdminRunbookTest {

    @Test
    void theRunbookDocumentsEverySubcommandAndNoLongerSaysRegister() throws IOException {
        String script = Files.readString(Paths.get("../deploy/admin-account.sh"), StandardCharsets.UTF_8);
        String runbook = Files.readString(Paths.get("../docker_deployment.md"), StandardCharsets.UTF_8);
        int usage = script.indexOf("usage:");
        Matcher m = Pattern.compile("admin-account\\.sh ([a-z-]+)").matcher(
                script.substring(usage, script.indexOf("EOF", usage)));
        Set<String> subcommands = new TreeSet<>();
        while (m.find()) {
            subcommands.add(m.group(1));
        }
        assertEquals(Set.of("create", "reset-password", "status"), subcommands);
        for (String sub : subcommands) {
            assertTrue(runbook.contains("run --rm admin " + sub) || runbook.contains("run --rm -T admin " + sub),
                    "docker_deployment.md never shows `admin " + sub + "`");
        }
        assertFalse(runbook.toLowerCase(Locale.ROOT).contains("register the first user"),
                "self-registration is gone; the runbook must not send anyone looking for it");
        assertTrue(runbook.contains("site admin email") || runbook.contains("site.adminemail"),
                "reset mail needs the From address; the runbook must say so");
    }
}
