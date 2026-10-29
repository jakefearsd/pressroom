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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs the shipped bin/deploy-host.sh from a scratch checkout (its own
 * pom.xml and deploy/upgrade.sh) with `scp`, `ssh` and `curl` as stubs that
 * record their arguments.
 */
class DeployHostScriptTest {

    @TempDir Path dir;
    private Path checkout;
    private final Map<String, String> env = new HashMap<>();

    @BeforeEach
    void layOut() throws Exception {
        checkout = dir.resolve("checkout");
        Files.createDirectories(checkout.resolve("bin"));
        Files.createDirectories(checkout.resolve("deploy"));
        Files.copy(Paths.get("../bin/deploy-host.sh"), checkout.resolve("bin/deploy-host.sh"));
        Files.writeString(checkout.resolve("deploy/upgrade.sh"), "# upgrade\n");
        Files.writeString(checkout.resolve("pom.xml"), """
                <project>
                    <groupId>com.wikantik.pressroom</groupId>
                    <version>9.8.7</version>
                    <dependency><version>1.0.0</version></dependency>
                </project>
                """);

        Path bin = dir.resolve("stubs");
        ScriptRun.stub(bin, "scp", "echo \"scp $*\" >> \"$STUB_LOG\"\n");
        ScriptRun.stub(bin, "ssh", "echo \"ssh $*\" >> \"$STUB_LOG\"\nexit \"${STUB_SSH_RC:-0}\"\n");
        ScriptRun.stub(bin, "curl", "echo \"curl $*\" >> \"$STUB_LOG\"\nprintf %s \"${STUB_HTTP:-200}\"\n");
        env.put("STUB_LOG", dir.resolve("calls.log").toString());
    }

    private ScriptRun.Result deploy(String... args) throws Exception {
        return ScriptRun.run(dir, dir.resolve("stubs"), env, checkout.resolve("bin/deploy-host.sh"), args);
    }

    private String calls() throws Exception {
        return ScriptRun.log(dir.resolve("calls.log"));
    }

    @Test
    void copiesTheUpgradeScriptAndRunsItThereAtTheCheckoutsVersion() throws Exception {
        ScriptRun.Result r = deploy("docker1");

        assertEquals(0, r.exit(), r.out());
        assertTrue(calls().contains("scp -q " + checkout.resolve("deploy/upgrade.sh") + " docker1:roller/upgrade.sh"),
                calls());
        assertTrue(calls().contains("ssh docker1 cd roller && bash upgrade.sh 9.8.7\n"), calls());
        assertTrue(calls().indexOf("scp") < calls().indexOf("ssh"), calls());
    }

    @Test
    void passesAVersionADirectoryAndTheUpgradeFlagsThrough() throws Exception {
        ScriptRun.Result r = deploy("docker1", "0.3.0", "--dir", "/srv/roller", "--no-backup", "--prune");

        assertEquals(0, r.exit(), r.out());
        assertTrue(calls().contains("docker1:/srv/roller/upgrade.sh"), calls());
        assertTrue(calls().contains("ssh docker1 cd /srv/roller && bash upgrade.sh 0.3.0 --no-backup --prune\n"),
                calls());
    }

    @Test
    void aFailedUpgradeOnTheHostFailsTheDeploy() throws Exception {
        env.put("STUB_SSH_RC", "1");

        ScriptRun.Result r = deploy("docker1", "--url", "https://blog.example.com");

        assertNotEquals(0, r.exit(), r.out());
        assertFalse(calls().contains("curl"), "no site check after a failed upgrade: " + calls());
    }

    @Test
    void theSiteCheckPassesOnA200() throws Exception {
        ScriptRun.Result r = deploy("docker1", "--url", "https://blog.example.com");

        assertEquals(0, r.exit(), r.out());
        assertTrue(calls().contains("https://blog.example.com"), calls());
    }

    /** The app can be healthy inside its container while the public route to it is broken. */
    @Test
    void aSiteThatDoesNotAnswer200FailsTheDeploy() throws Exception {
        env.put("STUB_HTTP", "502");

        ScriptRun.Result r = deploy("docker1", "--url", "https://blog.example.com");

        assertNotEquals(0, r.exit(), r.out());
        assertTrue(r.out().contains("502"), r.out());
    }

    @Test
    void noHostIsAUsageError() throws Exception {
        ScriptRun.Result r = deploy();

        assertEquals(2, r.exit(), r.out());
        assertTrue(r.out().contains("Usage"), r.out());
        assertEquals("", calls());
    }

    @Test
    void aVersionThatIsNotXyzIsRefused() throws Exception {
        ScriptRun.Result r = deploy("docker1", "latest");

        assertEquals(2, r.exit(), r.out());
        assertEquals("", calls());
    }
}
