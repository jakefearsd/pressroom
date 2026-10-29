package com.wikantik.pressroom.boot;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The CLI is shell, so this pins the properties that would otherwise only
 * fail in someone's terminal: it must not store a password, and it must
 * refuse to run without the tools it shells out to.
 */
class PressroomApiCliTest {

    private static String cli() throws Exception {
        Path path = Path.of("..", "bin", "pressroom-api");
        if (!Files.exists(path)) {
            path = Path.of("bin", "pressroom-api");
        }
        assertTrue(Files.exists(path), "bin/pressroom-api must exist");
        return Files.readString(path);
    }

    @Test
    void itFailsFastOnErrorsAndUnsetVariables() throws Exception {
        assertTrue(cli().contains("set -euo pipefail"),
                "a partial run against a live blog is worse than no run");
    }

    /**
     * Whole-branch review, Must Fix 6: the original second conjunct tested
     * for {@code >> "$CRED_FILE"}, which the script never uses (it writes
     * with a plain {@code >}, see {@code theCredentialsFileIsCreatedPrivate}
     * below) -- {@code A && false} is always false, so
     * {@code assertFalse(...)} was vacuously true regardless of whether
     * {@code password=} appeared anywhere. Rewritten to actually inspect
     * every line that touches {@code $CRED_FILE} for any mention of the
     * password, which is the property the test's name and the class javadoc
     * both promise.
     */
    @Test
    void thePasswordIsReadSilentlyAndNeverWrittenToTheCredentialsFile() throws Exception {
        String script = cli();
        assertTrue(script.contains("read -rs"), "the password must not echo");
        for (String line : script.split("\n", -1)) {
            if (line.contains("$CRED_FILE")) {
                assertFalse(line.contains("password") || line.contains("$password"),
                        "a line writing to $CRED_FILE must never mention the password: " + line);
            }
        }
    }

    /**
     * Whole-branch review, Must Fix 6: {@code "chmod 600"} occurs twice in
     * the script -- once for the netrc temp file used during login, once for
     * the credentials file this test is named for -- so a plain {@code
     * contains("chmod 600")} stayed green even with the credentials-file
     * chmod deleted entirely, landing the token world-readable. Asserts the
     * chmod appears on the line immediately after the write to
     * {@code $CRED_FILE} instead of merely appearing somewhere in the file.
     */
    @Test
    void theCredentialsFileIsCreatedPrivate() throws Exception {
        String[] lines = cli().split("\n", -1);
        int writeLine = -1;
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].contains("> \"$CRED_FILE\"")) {
                writeLine = i;
                break;
            }
        }
        assertTrue(writeLine >= 0, "must find the line that writes $CRED_FILE");
        assertTrue(lines[writeLine + 1].trim().startsWith("chmod 600 \"$CRED_FILE\""),
                "the line right after writing $CRED_FILE must chmod it 600 -- "
                        + "a token in a world-readable file is a leaked credential: "
                        + lines[writeLine + 1]);
    }

    @Test
    void itChecksForCurlAndJqBeforeDoingAnything() throws Exception {
        String script = cli();
        assertTrue(script.contains("command -v curl"));
        assertTrue(script.contains("command -v jq"));
    }

    /**
     * {@code curl -u user:password} puts the password on THIS PROCESS'S OWN
     * command line -- readable by any other local user via {@code ps -ef} or
     * {@code /proc/<pid>/cmdline} for the life of the request. The mint call
     * must authenticate via a {@code --netrc-file} instead, never {@code -u},
     * under any condition -- there is no fallback to pin against, so this
     * asserts the absence of the leaking form and the presence of the safe
     * one.
     */
    @Test
    void thePasswordNeverReachesAProcessCommandLine() throws Exception {
        String script = cli();
        assertFalse(script.contains("-u \"${user}"),
                "curl -u puts the password on the process command line, visible via ps/proc");
        assertFalse(script.contains("-u \"${user}:${password}\""),
                "curl -u puts the password on the process command line, visible via ps/proc");
        assertTrue(script.contains("--netrc-file"),
                "the mint request must authenticate via a netrc file, not -u");
    }

    /**
     * Regression coverage for round 1's other three fixes -- none of them
     * had a test of their own, so reverting any one would pass every other
     * test in this class undetected. Text assertions are weaker than
     * functional ones, but they are what this file's harness does, and they
     * are strictly better than nothing.
     */
    @Test
    void deleteAndTrashCallsNoLongerDiscardTheErrorBody() throws Exception {
        String script = cli();
        for (String line : script.split("\n", -1)) {
            if (line.contains("-o /dev/null")) {
                assertTrue(line.trim().startsWith("#"),
                        "a live curl invocation must not discard its body with -o /dev/null "
                                + "(a 409's `detail` is exactly what --fail-with-body exists to surface): "
                                + line);
            }
        }
    }

    @Test
    void mediaUploadsStatusCaptureIsGuardedAgainstSetE() throws Exception {
        String script = cli();
        assertTrue(script.contains("if ! status=\"$(api_raw"),
                "media_upload's status capture must be guarded (an `if` condition) so a non-2xx "
                        + "response cannot trip set -e before the body is read, printed and cleaned up");
    }

    @Test
    void mainAcceptsGlobalUrlAndTokenFlags() throws Exception {
        String script = cli();
        assertTrue(script.contains("--url|--token)"),
                "--url/--token must be parsed as global flags before the subcommand, "
                        + "matching the precedence documented in the file header");
    }

    /**
     * The twin of {@link #mediaUploadsStatusCaptureIsGuardedAgainstSetE()}:
     * {@code cmd_auth_login}'s token extraction had the identical unguarded
     * bare-pipe-into-assignment shape (a login failure's problem+json body
     * was captured but never read, because set -e aborted on the pipeline's
     * nonzero exit before `die` -- or even `rm -f` -- ever ran), and it sat
     * a few lines from the pattern this file already knew to guard against.
     */
    @Test
    void loginsResponseCaptureIsGuardedAgainstSetE() throws Exception {
        String script = cli();
        assertTrue(script.contains("if ! login_response=\"$(curl"),
                "cmd_auth_login's response capture must be guarded so a login failure "
                        + "cannot silently abort the script before `die` ever runs");
        for (String line : script.split("\n", -1)) {
            String trimmed = line.trim();
            if (trimmed.startsWith("#")) {
                continue; // explanatory comments are allowed to quote the old, fixed shape
            }
            assertFalse(line.contains("token=$(curl"),
                    "the token must never be extracted via a bare, unguarded curl | jq pipe assignment: "
                            + line);
        }
    }

    /**
     * A caller who forgets the value entirely --
     * {@code pressroom-api --url auth login --weblog h} -- must not have
     * PRESSROOM_API_URL silently set to "auth" with "login" then misread as the
     * top-level command.
     */
    @Test
    void globalUrlAndTokenFlagsRejectAKnownCommandNameAsTheirValue() throws Exception {
        String script = cli();
        assertTrue(script.contains("auth|entries|media|audit|admin)"),
                "a --url/--token flag with no value must not silently swallow the next command name");
    }

    /**
     * Parked item: {@code --dir-id} was found parsed but undocumented and
     * fixed one flag at a time, which does nothing to stop the NEXT one
     * drifting. This closes the class instead of the instance -- every
     * {@code --flag)} (or {@code --flag|--other)}) case-arm label anywhere
     * in the script must appear in SOME usage heredoc (a subcommand's own
     * {@code usage_<group>}, or the abbreviated top-level {@code usage()}),
     * not necessarily the same one. A boundary check (no trailing
     * letter/digit/hyphen) keeps {@code --user} from being satisfied by a
     * documented {@code --user-name} that merely starts with it, and the
     * pattern requires a letter right after the leading {@code --}, so the
     * end-of-options marker ({@code --)} in {@code media_upload}) never
     * counts as a flag needing documentation.
     */
    @Test
    void everyFlagCaseLabelIsDocumentedInSomeUsageHeredoc() throws Exception {
        String script = cli();
        String[] lines = script.split("\n", -1);

        StringBuilder docs = new StringBuilder();
        boolean inHeredoc = false;
        for (String line : lines) {
            if (!inHeredoc && line.trim().equals("cat <<'EOF'")) {
                inHeredoc = true;
                continue;
            }
            if (inHeredoc && line.equals("EOF")) {
                inHeredoc = false;
                continue;
            }
            if (inHeredoc) {
                docs.append(line).append('\n');
            }
        }
        assertTrue(docs.length() > 0, "must find at least one usage heredoc to check against");

        java.util.regex.Pattern caseLabel = java.util.regex.Pattern.compile(
                "^(--[a-zA-Z][a-zA-Z0-9-]*(?:\\|--[a-zA-Z][a-zA-Z0-9-]*)*)\\)");
        java.util.Set<String> flags = new java.util.TreeSet<>();
        for (String line : lines) {
            java.util.regex.Matcher m = caseLabel.matcher(line.trim());
            if (m.find()) {
                for (String flag : m.group(1).split("\\|")) {
                    flags.add(flag);
                }
            }
        }
        assertTrue(flags.size() > 20,
                "sanity check: the extraction pattern found implausibly few flags (" + flags.size()
                        + ") -- it likely stopped matching the script's actual shape");

        java.util.List<String> missing = new java.util.ArrayList<>();
        for (String flag : flags) {
            java.util.regex.Pattern mention = java.util.regex.Pattern.compile(
                    java.util.regex.Pattern.quote(flag) + "(?![a-zA-Z0-9-])");
            if (!mention.matcher(docs).find()) {
                missing.add(flag);
            }
        }
        assertTrue(missing.isEmpty(),
                "flags the script parses but no usage heredoc documents: " + missing
                        + " -- add them to the relevant usage_<group> heredoc (never delete a "
                        + "working flag just to make this pass)");
    }

    /**
     * --password-stdin is what lets ./pressroom token (and CI) log in without a
     * terminal. It must be a real case label, not just a line of usage text.
     */
    @Test
    void passwordStdinReadsThePasswordWithoutPrompting() throws Exception {
        String body = cli();
        int flagAt = body.indexOf("--password-stdin)");
        assertTrue(flagAt > 0,
                "--password-stdin must be a case label in auth login, not just documentation");
        String branch = body.substring(flagAt, Math.min(body.length(), flagAt + 600));
        assertTrue(branch.contains("read -r"),
                "the --password-stdin branch must read the password from stdin");
    }

    /**
     * The non-interactive path must not weaken the guarantee the interactive
     * one already has: the credential reaches curl through a netrc file, never
     * argv, where any local user could read it from /proc.
     */
    @Test
    void theStdinPasswordStillNeverReachesACommandLine() throws Exception {
        // Comment lines are dropped first: the script legitimately *explains*
        // why it avoids `curl -u`, right above the netrc handling that replaces it.
        String code = cli().lines()
                .filter(line -> !line.strip().startsWith("#"))
                .reduce("", (a, b) -> a + "\n" + b);
        assertFalse(code.contains("curl -u "),
                "the password must go through --netrc-file, never curl's -u argument");
        String body = cli();
        assertTrue(body.contains("--netrc-file"),
                "login must keep using a netrc file for the credential");
    }

    /**
     * Preview's HTML stays alone on stdout, so {@code > post.html} still
     * works, and its warnings (inline colors, code blocks with no language)
     * go to stderr where the author sees them. Runs the real script against
     * a stub curl that answers what the API answers.
     */
    @Test
    void entriesPreviewPrintsTheHtmlAndSendsItsWarningsToStderr(@TempDir Path dir) throws Exception {
        Path bin = Files.createDirectories(dir.resolve("bin"));
        Path curl = bin.resolve("curl");
        Files.writeString(curl, "#!/bin/bash\nprintf '%s' "
                + "'{\"html\":\"<p>hi</p>\",\"warnings\":[\"1 code block names no language\"]}'\n");
        assertTrue(curl.toFile().setExecutable(true));
        Path text = Files.writeString(dir.resolve("post.md"), "hi");
        Path script = Path.of("..", "bin", "pressroom-api").toAbsolutePath();
        ProcessBuilder pb = new ProcessBuilder("bash", script.toString(), "entries", "preview",
                "--weblog", "w", "--text-file", text.toString());
        pb.environment().put("PATH", bin + ":" + System.getenv("PATH"));
        pb.environment().put("PRESSROOM_API_URL", "http://pressroom.invalid");
        pb.environment().put("PRESSROOM_API_TOKEN", "t");
        pb.environment().put("PRESSROOM_CREDENTIALS", dir.resolve("none").toString());
        pb.redirectError(dir.resolve("stderr").toFile());
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(p.waitFor(30, TimeUnit.SECONDS), "pressroom-api hung");
        String err = Files.readString(dir.resolve("stderr"));

        assertEquals(0, p.exitValue(), out + err);
        assertEquals("<p>hi</p>\n", out);
        assertEquals("warning: 1 code block names no language\n", err);
    }

    /**
     * The credentials file moved from ~/.roller to ~/.pressroom with no (rebrand:keep)
     * fallback. When only the old file exists the error says to move it, by
     * name of the command, and never prints what the file holds.
     */
    @Test
    void anOldCredentialsFileIsNotReadAndTheErrorSaysToMoveIt(@TempDir Path dir) throws Exception {
        Path home = Files.createDirectories(dir.resolve("home"));
        Files.createDirectories(home.resolve(".roller")); // rebrand:keep
        Files.writeString(home.resolve(".roller/credentials"), // rebrand:keep
                "PRESSROOM_API_URL=http://secret.invalid\nPRESSROOM_API_TOKEN=prs_topsecret\n");
        Path script = Path.of("..", "bin", "pressroom-api").toAbsolutePath();
        ProcessBuilder pb = new ProcessBuilder("bash", script.toString(), "entries", "list", "--weblog", "w");
        pb.environment().put("HOME", home.toString());
        pb.environment().remove("PRESSROOM_API_URL");
        pb.environment().remove("PRESSROOM_API_TOKEN");
        pb.environment().remove("PRESSROOM_CREDENTIALS");
        pb.redirectError(dir.resolve("err").toFile());
        Process p = pb.start();
        p.getInputStream().readAllBytes();
        assertTrue(p.waitFor(30, TimeUnit.SECONDS), "pressroom-api hung");
        String err = Files.readString(dir.resolve("err"));

        assertNotEquals(0, p.exitValue(), err);
        assertTrue(err.contains("mv ~/.roller ~/.pressroom"), err); // rebrand:keep
        assertFalse(err.contains("topsecret") || err.contains("secret.invalid"), err);
    }

    /** What one run of the real script printed and returned. */
    private record Run(int exit, String out, String err) { }

    /**
     * Runs bin/pressroom-api with a stub curl that prints the URL it was given,
     * so a test can see which route a command reaches. {@code credentials}
     * false runs with no URL, no token and no credentials file.
     */
    private static Run run(Path dir, boolean credentials, String... args) throws Exception {
        Path bin = Files.createDirectories(dir.resolve("bin"));
        Path curl = bin.resolve("curl");
        Files.writeString(curl, "#!/bin/bash\nfor a in \"$@\"; do last=\"$a\"; done\n"
                + "printf '{\"url\":\"%s\"}' \"$last\"\n");
        assertTrue(curl.toFile().setExecutable(true));
        Path script = Path.of("..", "bin", "pressroom-api").toAbsolutePath();
        java.util.List<String> command = new java.util.ArrayList<>(java.util.List.of("bash", script.toString()));
        command.addAll(java.util.List.of(args));
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.environment().put("PATH", bin + ":" + System.getenv("PATH"));
        pb.environment().remove("PRESSROOM_API_URL");
        pb.environment().remove("PRESSROOM_API_TOKEN");
        if (credentials) {
            pb.environment().put("PRESSROOM_API_URL", "http://pressroom.invalid");
            pb.environment().put("PRESSROOM_API_TOKEN", "t");
        }
        pb.environment().put("PRESSROOM_CREDENTIALS", dir.resolve("none").toString());
        Path stderr = dir.resolve("stderr-" + System.nanoTime());
        pb.redirectError(stderr.toFile());
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(p.waitFor(30, TimeUnit.SECONDS), "pressroom-api hung");
        return new Run(p.exitValue(), out, Files.readString(stderr));
    }

    /**
     * {@code <group> --help} is a request for help, not a usage error: it
     * prints the group's subcommands and exits 0, and needs no credentials
     * (it used to exit 1, after first demanding a token).
     */
    @Test
    void everyGroupAnswersHelpWithItsUsageAndExitsZero(@TempDir Path dir) throws Exception {
        String[][] cases = {
            {"entries", "entries create"}, {"media", "media upload"}, {"audit", "audit seo"},
            {"admin", "admin rebuild-index"}, {"auth", "auth login"},
        };
        for (String[] c : cases) {
            for (String flag : new String[] {"--help", "-h", "help"}) {
                Run r = run(dir, false, c[0], flag);
                assertEquals(0, r.exit(), c[0] + " " + flag + ": " + r.out() + r.err());
                assertTrue(r.out().contains(c[1]), c[0] + " " + flag + ": " + r.out());
            }
        }
        Run top = run(dir, false, "--help");
        assertEquals(0, top.exit(), top.err());
        assertTrue(top.out().contains("pressroom-api <group> --help"), top.out());
        assertFalse(top.out().contains("'-shaped"), "the garbled help line is gone: " + top.out());
    }

    /** The top-level summary agrees with the group usages: alt text is optional, --publish exists. */
    @Test
    void theTopLevelHelpAgreesWithTheGroupUsages(@TempDir Path dir) throws Exception {
        String top = run(dir, false, "--help").out();
        assertTrue(top.contains("media patch  --weblog H --id ID [--alt-text TEXT]"), top);
        String entries = run(dir, false, "entries", "--help").out();
        for (String flag : new String[] {"--publish", "--meta-title", "--search-description", "--canonical-url",
                "--noindex", "--featured-image-id", "--og-image-id"}) {
            int create = entries.indexOf("entries create");
            int patch = entries.indexOf("entries patch");
            assertTrue(entries.substring(create, patch).contains(flag), "entries create usage lacks " + flag);
        }
    }

    /** A flag given last with no value is a clear refusal, never bash's "$2: unbound variable". */
    @Test
    void aFlagMissingItsValueIsRefusedByName(@TempDir Path dir) throws Exception {
        Run r = run(dir, true, "entries", "list", "--weblog");

        assertEquals(1, r.exit(), r.out() + r.err());
        assertEquals("--weblog needs a value.\n", r.err());
        assertFalse(r.err().contains("unbound variable"), r.err());
    }

    /** {@code admin rebuild-index} is the REST and MCP name; {@code reindex} stays as its alias. */
    @Test
    void rebuildIndexIsTheCommandAndReindexStillWorks(@TempDir Path dir) throws Exception {
        for (String name : new String[] {"rebuild-index", "reindex"}) {
            Run r = run(dir, true, "admin", name, "--weblog", "w");
            assertEquals(0, r.exit(), name + ": " + r.out() + r.err());
            assertTrue(r.out().contains("http://pressroom.invalid/api/v1/admin/weblogs/w/actions/rebuild-index"),
                    name + ": " + r.out());
        }
    }
}
