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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs the shipped bin/db/install-fresh.sh, copied beside a stub migrate.sh,
 * with a stub {@code psql} first on PATH that records its argv and its stdin
 * separately and reports that neither the role nor the database exists yet.
 */
class InstallFreshScriptTest {

    private static final Path INSTALL = Paths.get("../bin/db/install-fresh.sh").toAbsolutePath();

    /** A quote and a statement terminator: the shapes that broke or injected into the old SQL. */
    private static final String PASSWORD = "it's\"; DROP ROLE postgres; --";

    @TempDir Path dir;
    private Path script;
    private final Map<String, String> env = new HashMap<>();

    @BeforeEach
    void layOut() throws Exception {
        Path db = Files.createDirectories(dir.resolve("db"));
        script = Files.copy(INSTALL, db.resolve("install-fresh.sh"));
        ScriptRun.stub(db, "migrate.sh", "echo migrated >> \"$STUB_LOG\"\n");
        ScriptRun.stub(dir.resolve("bin"), "psql", """
                printf 'ARGV %s\\n' "$*" >> "$STUB_LOG"
                if [[ "$*" != *" -c "* ]]; then
                  printf 'STDIN ' >> "$STUB_LOG"; cat >> "$STUB_LOG"
                fi
                exit 0
                """);
        env.put("STUB_LOG", dir.resolve("calls.log").toString());
        env.put("DB_APP_PASSWORD", PASSWORD);
        env.put("DB_NAME", "pressroom");
        env.put("DB_APP_USER", "pressroom");
    }

    /**
     * The password used to be pasted into {@code -c "CREATE ROLE ...
     * PASSWORD '...'"}: on psql's argv (any local user's {@code ps}), and a
     * quote in it broke the statement or ran whatever followed. It now
     * reaches psql only through its environment ({@code \getenv}) and enters
     * the SQL as a literal {@code format('%L')} builds.
     */
    @Test
    void thePasswordIsNeverOnACommandLineNorInTheScriptPsqlReads() throws Exception {
        ScriptRun.Result r = ScriptRun.run(dir, dir.resolve("bin"), env, script);

        assertEquals(0, r.exit(), r.out());
        String calls = ScriptRun.log(dir.resolve("calls.log"));
        assertFalse(calls.contains("it's"), "the password reached psql's argv or stdin: " + calls);
        assertFalse(calls.contains("DROP ROLE"), calls);
        assertTrue(calls.contains("\\getenv pw DB_APP_PASSWORD"), calls);
        assertTrue(calls.contains("format('CREATE ROLE %I LOGIN PASSWORD %L', :'role', :'pw')"), calls);
        assertTrue(calls.contains("migrated"), calls);
    }

    /** Names reach the SQL as psql variables, quoted by %I or :'...', never spliced in. */
    @Test
    void namesAreQuotedRatherThanSpliced() throws Exception {
        env.put("DB_NAME", "blog\"; DROP DATABASE x; --");

        ScriptRun.Result r = ScriptRun.run(dir, dir.resolve("bin"), env, script);

        assertEquals(0, r.exit(), r.out());
        String sql = ScriptRun.log(dir.resolve("calls.log")).lines()
                .filter(l -> !l.startsWith("ARGV ")).reduce("", (a, b) -> a + b + "\n");
        assertFalse(sql.contains("DROP DATABASE"), "a name was spliced into the SQL text: " + sql);
        assertTrue(sql.contains("format('CREATE DATABASE %I OWNER %I', :'db', :'role')"), sql);
        assertTrue(sql.contains("format('GRANT ALL PRIVILEGES ON DATABASE %I TO %I', :'db', :'role')"), sql);
    }
}
