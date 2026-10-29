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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs a shipped shell script with stub commands first on PATH, the shape
 * {@link DeployScriptTest} uses, shared by the release and upgrade script
 * tests. A stub is a bash body; it sees the caller's environment, so a test
 * steers it with variables and reads what it recorded from a log file.
 */
final class ScriptRun {

    record Result(int exit, String out) {}

    private ScriptRun() {}

    static void stub(Path bin, String name, String body) throws IOException {
        Files.createDirectories(bin);
        Path stub = bin.resolve(name);
        Files.writeString(stub, "#!/bin/bash\n" + body);
        assertTrue(stub.toFile().setExecutable(true), "could not make the " + name + " stub executable");
    }

    static Result run(Path workDir, Path bin, Map<String, String> env, Path script, String... args)
            throws IOException, InterruptedException {
        List<String> command = new ArrayList<>(List.of("bash", script.toString()));
        command.addAll(List.of(args));
        ProcessBuilder pb = new ProcessBuilder(command).directory(workDir.toFile()).redirectErrorStream(true);
        pb.environment().put("PATH", bin + ":" + System.getenv("PATH"));
        pb.environment().putAll(env);
        Process p = pb.start();
        p.getOutputStream().close();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(p.waitFor(60, TimeUnit.SECONDS), script.getFileName() + " hung");
        return new Result(p.exitValue(), out);
    }

    /** The stub log, or empty if no stub wrote one. */
    static String log(Path file) throws IOException {
        return Files.exists(file) ? Files.readString(file) : "";
    }
}
