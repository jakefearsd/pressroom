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

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Pins the production stack's shape: the deploy host holds
 * docker-compose.prod.yml and .env and nothing else (see
 * docs/superpowers/specs/2026-08-14-container-push-deployment-design.md).
 *
 * <p>Reintroducing a bind mount is the single change that would silently undo
 * that -- the stack would keep working on a developer's machine, which has the
 * checkout, and fail only on a host that does not. Same reasoning as
 * {@link UmamiViewScriptTest} policing deploy/analytics from a unit test.
 */
class ProductionComposeTest {

    private static final Path COMPOSE = Paths.get("../docker-compose.prod.yml");

    private static Map<String, Object> compose;

    @SuppressWarnings("unchecked")
    @BeforeAll
    static void load() throws IOException {
        assertTrue(Files.exists(COMPOSE), "missing " + COMPOSE.toAbsolutePath());
        try (InputStream in = Files.newInputStream(COMPOSE)) {
            compose = (Map<String, Object>) new Yaml().load(in);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> services() {
        return (Map<String, Object>) compose.get("services");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> service(String name) {
        Map<String, Object> svc = (Map<String, Object>) services().get(name);
        assertNotNull(svc, "no '" + name + "' service in " + COMPOSE);
        return svc;
    }

    @Test
    @SuppressWarnings("unchecked")
    void noServiceBindMountsARepositoryPath() {
        // A source is a named volume only if it is declared under the file's
        // top-level `volumes:` key -- anything else is a bind mount, whether it
        // is written in short syntax ("source:target") or long syntax
        // ({type: bind, source: ..., target: ...}), and whether or not the
        // source string happens to look like a path. Keying off the
        // authoritative list here (rather than guessing from the source
        // string's shape) is what catches BOTH a long-syntax bind mount --
        // whose string form is a Map's "key=value" toString with no colon at
        // all, so a colon-split never sees it -- AND a short-syntax relative
        // path missing a leading "./", which still resolves as a bind mount to
        // Compose even though it does not start with "." or "/".
        Map<String, Object> topLevelVolumes = (Map<String, Object>) compose.get("volumes");
        Set<String> namedVolumes = topLevelVolumes == null ? Set.of() : topLevelVolumes.keySet();

        List<String> offenders = new ArrayList<>();
        for (Map.Entry<String, Object> entry : services().entrySet()) {
            Object volumes = ((Map<String, Object>) entry.getValue()).get("volumes");
            if (!(volumes instanceof List<?> list)) {
                continue;
            }
            for (Object volume : list) {
                String source;
                String display;
                if (volume instanceof Map<?, ?> longForm) {
                    Object src = longForm.get("source");
                    source = src == null ? "" : String.valueOf(src);
                    display = String.valueOf(longForm);
                } else {
                    String spec = String.valueOf(volume);
                    source = spec.split(":", 2)[0];
                    display = spec;
                }
                if (!source.isBlank() && !namedVolumes.contains(source)) {
                    offenders.add(entry.getKey() + ": " + display);
                }
            }
        }
        assertTrue(offenders.isEmpty(),
                "the production host has no checkout, so nothing may be bind-mounted from one: "
                        + offenders);
    }

    @Test
    void noServiceBuildsFromSource() {
        for (Map.Entry<String, Object> entry : services().entrySet()) {
            @SuppressWarnings("unchecked")
            Map<String, Object> svc = (Map<String, Object>) entry.getValue();
            assertTrue(svc.get("build") == null,
                    entry.getKey() + " declares build:, but the production host has no build "
                            + "context -- images come from the registry");
        }
    }

    @Test
    void provisionRunsOnceAndGatesTheServicesThatNeedASchema() {
        assertEquals("no", String.valueOf(service("provision").get("restart")),
                "provision is a one-shot job, not a restarting service");

        for (String dependent : List.of("app", "umami", "listmonk")) {
            Object dependsOnRaw = service(dependent).get("depends_on");
            assertNotNull(dependsOnRaw, dependent + " must declare depends_on");
            if (!(dependsOnRaw instanceof Map<?, ?> dependsOn)) {
                fail(dependent + "'s depends_on must be the long map form (with a condition per "
                        + "dependency), not a bare list: " + dependsOnRaw);
                return;
            }
            Object conditionRaw = dependsOn.get("provision");
            assertNotNull(conditionRaw, dependent + " must wait for provision");
            if (!(conditionRaw instanceof Map<?, ?> condition)) {
                fail(dependent + "'s depends_on.provision must be a map carrying a condition, "
                        + "not: " + conditionRaw);
                return;
            }
            assertEquals("service_completed_successfully", condition.get("condition"),
                    dependent + " must start only after provisioning succeeds -- this is what "
                            + "replaced deploy.sh's migrate-then-start ordering");
        }
    }

    @Test
    void onlyCaddyPublishesNonLoopbackPorts() {
        for (Map.Entry<String, Object> entry : services().entrySet()) {
            @SuppressWarnings("unchecked")
            Object ports = ((Map<String, Object>) entry.getValue()).get("ports");
            if (ports == null) {
                continue;
            }
            assertEquals("caddy", entry.getKey(), "only caddy may publish ports");
            if (!(ports instanceof List<?> portList)) {
                fail(entry.getKey() + "'s ports must be a list, not: " + ports);
                return;
            }
            for (Object port : portList) {
                String spec = String.valueOf(port);
                boolean publicPort = spec.endsWith(":80") || spec.endsWith(":443");
                assertTrue(publicPort || loopbackByDefault(spec),
                        "only caddy's 80 and 443 may be reachable off-host by default; " + spec
                                + " is neither those nor loopback-bound unless .env says otherwise");
            }
        }
    }

    /**
     * The off-host ports default to 80 and 443, which is what a public host
     * serving its own domain needs. A host behind a tunnel connector or a
     * reverse proxy on another machine is different: 80 and 443 there may
     * belong to something else, and the connector can reach any port. So the
     * host side of each one is movable from .env, the default unchanged, and
     * named there, or the override exists but nobody can find it.
     */
    @Test
    void everyPublicHostPortIsOverridableFromEnv() throws IOException {
        Pattern overridable = Pattern.compile("\\$\\{([A-Z][A-Z0-9_]*):-(\\d+)}:(\\d+)");
        String envExample = Files.readString(Paths.get("../deploy/.env.example"),
                StandardCharsets.UTF_8);
        List<String> published = new ArrayList<>();
        for (Object port : (List<?>) service("caddy").get("ports")) {
            String spec = String.valueOf(port);
            if (loopbackByDefault(spec)) {
                continue;
            }
            published.add(spec);
            var m = overridable.matcher(spec);
            assertTrue(m.matches(), spec + " hard-codes its host port; write it as "
                    + "${SOME_PORT:-<container port>}:<container port> so a host whose "
                    + "port is taken can move it");
            assertEquals(m.group(3), m.group(2), spec + " must default to its container "
                    + "port, so a .env that never sets " + m.group(1) + " publishes what it did");
            assertTrue(Pattern.compile("(?m)^#?" + m.group(1) + "=").matcher(envExample).find(),
                    m.group(1) + " moves " + spec + " but deploy/.env.example never names it");
        }
        assertEquals(2, published.size(), "expected caddy's 80 and 443; got " + published);
    }

    /**
     * Caddy rewrites X-Forwarded-For and X-Forwarded-Proto but passes an RFC
     * 7239 {@code Forwarded} header through as the visitor wrote it. The app
     * no longer reads it (server.forward-headers-strategy=native; see
     * RemoteIpValveConfigTest), and every proxy to the app also drops it, so
     * no later change on either side can make a visitor's own "for=" or
     * "host=" count again (code review, 2026-11-03).
     */
    @Test
    void everyProxyToTheAppDropsTheForwardedHeader() throws IOException {
        Matcher block = Pattern.compile("reverse_proxy app:8080\\s*\\{([^}]*)}").matcher(caddyDirectives());
        int proxies = 0;
        while (block.find()) {
            proxies++;
            assertTrue(Pattern.compile("(?m)^\\s*header_up -Forwarded\\s*$").matcher(block.group(1)).find(),
                    "a reverse_proxy to the app without header_up -Forwarded:\n" + block.group());
        }
        long bare = Pattern.compile("reverse_proxy app:8080\\s*$", Pattern.MULTILINE).matcher(caddyDirectives())
                .results().count();
        assertEquals(0, bare, "every reverse_proxy to the app needs a block that drops Forwarded");
        assertEquals(2, proxies, "the site and the custom-domain zone");
    }

    /**
     * Behind a proxy on another machine (a tunnel connector such as
     * cloudflared), that machine is Caddy's peer, and an untrusted peer's
     * X-Forwarded-For is replaced with its own address and its
     * X-Forwarded-Proto with "http". Every reader then reaches the app's
     * throttles as one address, and the app believes the request was plain
     * HTTP. Which peer to trust is per host, so it comes from .env, and unset
     * means none: on a public host Caddy is the first hop and must keep
     * discarding whatever a client sends. Never {@code private_ranges}: where
     * Docker's userland proxy carries a connection, an internet client arrives
     * from the bridge gateway, a private address, and could then write its own
     * X-Forwarded-For past every throttle.
     */
    @Test
    @SuppressWarnings("unchecked")
    void caddyTrustsForwardedHeadersOnlyFromProxiesNamedInEnv() throws IOException {
        String directives = Files.readString(Paths.get("../deploy/caddy/Caddyfile"),
                StandardCharsets.UTF_8).replaceAll("(?m)#.*$", "");
        assertTrue(Pattern.compile("(?m)^\\s*trusted_proxies static \\{\\$TRUSTED_PROXIES}\\s*$")
                        .matcher(directives).find(),
                "the Caddyfile's global servers block must take its trusted proxies from "
                        + "TRUSTED_PROXIES");
        assertTrue(Pattern.compile("(?m)^\\s*trusted_proxies_strict\\s*$").matcher(directives).find(),
                "Cloudflare appends the reader's address to any X-Forwarded-For the reader "
                        + "sent, so only a right-to-left read (trusted_proxies_strict) finds it");
        assertFalse(directives.contains("private_ranges"),
                "trusting private_ranges trusts Docker's bridge gateway, which is where an "
                        + "internet client appears when the userland proxy carries it");
        Map<String, Object> env = (Map<String, Object>) service("caddy").get("environment");
        assertEquals("${TRUSTED_PROXIES:-}", env.get("TRUSTED_PROXIES"),
                "caddy must receive TRUSTED_PROXIES, and unset it must trust no proxy");
        String envExample = Files.readString(Paths.get("../deploy/.env.example"),
                StandardCharsets.UTF_8);
        assertTrue(Pattern.compile("(?m)^#?TRUSTED_PROXIES=").matcher(envExample).find(),
                "deploy/.env.example must name TRUSTED_PROXIES, or nobody behind a tunnel "
                        + "finds it");
    }

    /**
     * The loopback console ports exist only for a local run, yet caddy cannot
     * start if a host already has either one bound -- on any address, since a
     * wildcard listener blocks a loopback bind of the same port -- and caddy is
     * the only ingress, so a hard-coded console port takes the blog down with
     * it. Each one's host side must therefore be movable from .env, and named
     * there, or the override exists but nobody can find it.
     */
    @Test
    void everyLoopbackHostPortIsOverridableFromEnv() throws IOException {
        Pattern overridable = Pattern.compile(
                "(?:127\\.0\\.0\\.1|\\$\\{[A-Z][A-Z0-9_]*:-127\\.0\\.0\\.1}):"
                        + "\\$\\{([A-Z][A-Z0-9_]*):-\\d+}:\\d+");
        String envExample = Files.readString(Paths.get("../deploy/.env.example"),
                StandardCharsets.UTF_8);
        List<String> loopback = new ArrayList<>();
        for (Object port : (List<?>) service("caddy").get("ports")) {
            String spec = String.valueOf(port);
            if (!loopbackByDefault(spec)) {
                continue;
            }
            loopback.add(spec);
            var m = overridable.matcher(spec);
            assertTrue(m.matches(), spec + " hard-codes its host port; write it as "
                    + "127.0.0.1:${SOME_PORT:-<default>}:<container port> so a host that "
                    + "already uses <default> can move it");
            assertTrue(Pattern.compile("(?m)^#?" + m.group(1) + "=").matcher(envExample).find(),
                    m.group(1) + " moves " + spec + " but deploy/.env.example never names it");
        }
        assertFalse(loopback.isEmpty(), "expected caddy's loopback console ports; did they move?");
    }

    /**
     * A port whose host address is loopback, written either literally or as a
     * {@code ${VAR:-127.0.0.1}} that .env may point at a LAN address.
     */
    private static boolean loopbackByDefault(String spec) {
        return spec.startsWith("127.0.0.1:")
                || Pattern.compile("^\\$\\{[A-Z][A-Z0-9_]*:-127\\.0\\.0\\.1}:").matcher(spec).find();
    }

    /**
     * The Umami dashboard and its API are internal (spec AC 19). cloudflared
     * runs on another machine and cannot reach docker1's loopback, so the
     * owner publishes the console on the LAN address for the tunnel route
     * to reach; the file's own default stays loopback, so a host that
     * never sets UMAMI_CONSOLE_BIND exposes nothing.
     */
    @Test
    void dashboardBindDefaultsToLoopback() throws IOException {
        assertTrue(((List<?>) service("caddy").get("ports"))
                        .contains("${UMAMI_CONSOLE_BIND:-127.0.0.1}:${UMAMI_CONSOLE_PORT:-8081}:8081"),
                "the dashboard port must be bindable to the LAN from .env and default to loopback: "
                        + service("caddy").get("ports"));
        String envExample = Files.readString(Paths.get("../deploy/.env.example"),
                StandardCharsets.UTF_8);
        assertTrue(Pattern.compile("(?m)^#?UMAMI_CONSOLE_BIND=").matcher(envExample).find(),
                "deploy/.env.example must name UMAMI_CONSOLE_BIND, or nobody finds the LAN bind");
        assertTrue(Pattern.compile("(?ms)^:8081 \\{\\s*reverse_proxy umami:3000\\s*}")
                        .matcher(caddyDirectives()).find(),
                "the dashboard is a fixed :8081 site in the Caddyfile, not a hostname");
    }

    /**
     * The public-hostname mode put Umami's whole dashboard and API on the
     * internet (spec AC 19). It is gone from every file the host receives.
     */
    @Test
    void noUmamiDomainAnywhere() throws IOException {
        List<String> offenders = new ArrayList<>();
        List<Path> shipped = new ArrayList<>(List.of(COMPOSE));
        try (var walk = Files.walk(Paths.get("../deploy"))) {
            walk.filter(Files::isRegularFile).forEach(shipped::add);
        }
        for (Path file : shipped) {
            if (Files.readString(file, StandardCharsets.UTF_8).contains("UMAMI_DOMAIN")) {
                offenders.add(file.toString());
            }
        }
        assertEquals(List.of(), offenders, "UMAMI_DOMAIN is removed; these still name it");
    }

    /**
     * Pressroom registers its blogs through Umami's API over the compose
     * network, as its own non-admin account. The password is generated into
     * .env by deploy.sh; analytics-views creates the account with it and the
     * app signs in with it. The removed JDBC route to Umami's tables is gone.
     */
    @Test
    @SuppressWarnings("unchecked")
    void theAppReachesUmamiThroughItsApiAsThePressroomAccount() throws IOException {
        Map<String, Object> app = (Map<String, Object>) service("app").get("environment");
        assertEquals("${PRESSROOM_ANALYTICS_UMAMI_APIURL-http://umami:3000}",
                app.get("PRESSROOM_ANALYTICS_UMAMI_APIURL"));
        assertEquals("${UMAMI_PRESSROOM_PASSWORD:-}", app.get("PRESSROOM_ANALYTICS_UMAMI_PASSWORD"));
        Map<String, Object> views = (Map<String, Object>) service("analytics-views").get("environment");
        assertEquals("${UMAMI_PRESSROOM_PASSWORD:-}", views.get("UMAMI_PRESSROOM_PASSWORD"),
                "analytics-views creates the account the app signs in as");
        String envExample = Files.readString(Paths.get("../deploy/.env.example"),
                StandardCharsets.UTF_8);
        for (String key : List.of("UMAMI_PRESSROOM_PASSWORD", "PRESSROOM_ANALYTICS_UMAMI_DASHBOARDURL")) {
            assertTrue(Pattern.compile("(?m)^#?" + key + "=").matcher(envExample).find(),
                    "deploy/.env.example must document " + key);
        }
        for (Path file : List.of(COMPOSE, Paths.get("../deploy/.env.example"))) {
            assertFalse(Files.readString(file, StandardCharsets.UTF_8).contains("PRESSROOM_ANALYTICS_JDBC"),
                    file + " still names the removed PRESSROOM_ANALYTICS_JDBC_* settings");
        }
    }

    /**
     * A blank {@code analytics.umami.apiUrl} means analytics is off (spec), so
     * production must be able to reach a blank value. Compose's {@code :-}
     * substitutes the default for an empty variable as well as an unset one,
     * which would make {@code PRESSROOM_ANALYTICS_UMAMI_APIURL=} in .env turn
     * analytics straight back on; a bare {@code -} substitutes only when it is
     * unset. .env.example must name that blank line as the off switch.
     */
    @Test
    @SuppressWarnings("unchecked")
    void aBlankApiUrlInEnvSwitchesAnalyticsOff() throws IOException {
        String apiUrl = String.valueOf(((Map<String, Object>) service("app").get("environment"))
                .get("PRESSROOM_ANALYTICS_UMAMI_APIURL"));
        Matcher m = Pattern.compile("^\\$\\{PRESSROOM_ANALYTICS_UMAMI_APIURL(:?)-([^}]*)}$").matcher(apiUrl);
        assertTrue(m.matches(), "the API URL must come from .env with a default: " + apiUrl);
        assertEquals("", m.group(1),
                "${VAR:-default} turns a blank .env value back into the default; use ${VAR-default}");
        assertEquals("http://umami:3000", m.group(2), "unset, the app reaches Umami over the compose network");
        String envExample = Files.readString(Paths.get("../deploy/.env.example"), StandardCharsets.UTF_8);
        assertTrue(Pattern.compile("(?m)^#\\s*PRESSROOM_ANALYTICS_UMAMI_APIURL=$").matcher(envExample).find(),
                "deploy/.env.example must show the blank PRESSROOM_ANALYTICS_UMAMI_APIURL= off switch");
    }

    /**
     * The nightly Umami retention job ships the way the analytics SQL does:
     * baked into the image (nothing is bind-mounted), the SQL beside the
     * other analytics files at /app, where umami-retention.sh looks for it,
     * and the script beside loop.sh, which calls it after the backup
     * ({@link BackupLoopTest} runs that order). The window comes from .env
     * with no default in the compose file, so the SQL's 730 is the one
     * default.
     */
    @Test
    @SuppressWarnings("unchecked")
    void theUmamiRetentionJobIsBakedAndConfigured() throws IOException {
        String dockerfile = Files.readString(Paths.get("../Dockerfile"), StandardCharsets.UTF_8);
        assertTrue(dockerfile.contains("COPY deploy/analytics/umami-retention.sql /app/umami-retention.sql"),
                "the root Dockerfile must bake the retention SQL at /app/umami-retention.sql");
        assertTrue(dockerfile.contains("COPY deploy/backup/umami-retention.sh /app/backup/umami-retention.sh"),
                "the root Dockerfile must bake the retention script beside loop.sh");
        assertTrue(Pattern.compile("chmod 755[^\\n]*/app/backup/umami-retention\\.sh").matcher(dockerfile).find(),
                "the retention script must be executable");
        String script = Files.readString(Paths.get("../deploy/backup/umami-retention.sh"), StandardCharsets.UTF_8);
        assertTrue(script.contains("umami-retention.sql"), "umami-retention.sh must apply the retention SQL");
        String loop = Files.readString(Paths.get("../deploy/backup/loop.sh"), StandardCharsets.UTF_8);
        assertTrue(loop.contains("\"$(dirname \"$0\")/umami-retention.sh\""),
                "loop.sh must call the retention script relative to itself, as it does backup.sh");

        Map<String, Object> backup = (Map<String, Object>) service("backup").get("environment");
        assertEquals("${UMAMI_RETENTION_DAYS:-}", backup.get("UMAMI_RETENTION_DAYS"));
        String envExample = Files.readString(Paths.get("../deploy/.env.example"), StandardCharsets.UTF_8);
        assertTrue(Pattern.compile("(?m)^#?UMAMI_RETENTION_DAYS=730$").matcher(envExample).find(),
                "deploy/.env.example must document UMAMI_RETENTION_DAYS and its default");
    }

    /** The account step's SQL must be in the image, where analytics-views.sh looks for it. */
    @Test
    void theAccountSqlIsBakedBesideTheViews() throws IOException {
        String dockerfile = Files.readString(Paths.get("../Dockerfile"), StandardCharsets.UTF_8);
        assertTrue(dockerfile.contains(
                        "COPY deploy/analytics/umami-pressroom-account.sql /app/umami-pressroom-account.sql"),
                "the root Dockerfile must bake the account SQL at /app/umami-pressroom-account.sql");
        String script = Files.readString(Paths.get("../deploy/analytics-views.sh"), StandardCharsets.UTF_8);
        assertTrue(script.contains("umami-pressroom-account.sql"),
                "analytics-views.sh must apply the account SQL");
    }

    /**
     * Umami's public face is the tracker and nothing else (spec AC 7): on every
     * site block that serves blogs, {@code /analytics/<script>} and
     * {@code POST /analytics/api/send} reach Umami with the prefix stripped,
     * and every other {@code /analytics/} path answers 404 from Caddy. Before
     * this, {@code /analytics/*} forwarded all of Umami, so its login page and
     * API were public on every blog hostname. Session replay's three paths sit
     * in each block commented out, so switching it on later is visible.
     *
     * <p>The blocks are compared with each other as well as with the expected
     * lines: the custom-domain block once had no analytics routing at all
     * (C1), and two copies that drift apart are how that recurs.
     */
    @Test
    void caddyAllowsOnlyTrackerPaths() throws IOException {
        String raw = Files.readString(Paths.get("../deploy/caddy/Caddyfile"), StandardCharsets.UTF_8);
        List<String> expected = List.of(
                "@tracker path /analytics/{$UMAMI_SCRIPT_NAME:script.js}",
                "handle @tracker {",
                "uri strip_prefix /analytics",
                "reverse_proxy umami:3000",
                "}",
                "@send {",
                "path /analytics/api/send",
                "method POST",
                "}",
                "handle @send {",
                "uri strip_prefix /analytics",
                "reverse_proxy umami:3000",
                "}",
                "handle /analytics/* {",
                "respond 404",
                "}");

        List<String> siteBlocks = new ArrayList<>();
        List<List<String>> allowlists = new ArrayList<>();
        for (String block : topLevelCaddyBlocks(raw)) {
            String directives = stripComments(block);
            if (!directives.contains("reverse_proxy app:8080")) {
                assertFalse(directives.contains("/analytics"), "only blog sites route /analytics");
                continue;
            }
            siteBlocks.add(firstNonBlankLine(directives));
            List<String> analyticsLines = new ArrayList<>();
            boolean inAnalytics = false;
            int depth = 0;
            for (String line : directives.split("\n")) {
                String t = line.strip();
                if (t.isEmpty()) {
                    continue;
                }
                if (!inAnalytics && (t.startsWith("@tracker") || t.startsWith("@send")
                        || t.startsWith("handle @") || t.startsWith("handle /analytics"))) {
                    inAnalytics = true;
                }
                if (inAnalytics) {
                    analyticsLines.add(t);
                    depth += t.endsWith("{") ? 1 : 0;
                    depth -= "}".equals(t) ? 1 : 0;
                    if (depth == 0 && "}".equals(t) && analyticsLines.contains("respond 404")) {
                        inAnalytics = false;
                    }
                }
            }
            allowlists.add(analyticsLines);
            assertEquals(List.of("handle @tracker {", "handle @send {"), umamiProxyParents(directives),
                    "in " + firstNonBlankLine(directives) + ", reverse_proxy umami may appear only "
                            + "inside the tracker and send handles");
            for (String path : List.of("/analytics/recorder.js", "/analytics/api/record",
                    "/analytics/api/websites/*/recorder")) {
                assertTrue(Pattern.compile("(?m)^\\s*#.*" + Pattern.quote(path)).matcher(block).find(),
                        firstNonBlankLine(directives) + " must list replay's " + path + ", commented");
                assertFalse(directives.contains(path), "replay stays off: " + path + " is live");
            }
        }
        assertEquals(2, siteBlocks.size(), "expected the site and custom-domain blocks: " + siteBlocks);
        for (List<String> allowlist : allowlists) {
            assertEquals(expected, allowlist, "the /analytics allowlist of a blog site block");
        }
        assertFalse(caddyDirectives().contains("handle_path /analytics"),
                "handle_path /analytics/* forwards every Umami path; use the allowlist");
    }

    /** The header of the block enclosing each {@code reverse_proxy umami:3000}, in order. */
    private static List<String> umamiProxyParents(String directives) {
        List<String> parents = new ArrayList<>();
        Deque<String> open = new ArrayDeque<>();
        for (String line : directives.split("\n")) {
            String t = line.strip();
            if (t.endsWith("{")) {
                open.push(t);
            } else if ("}".equals(t)) {
                open.poll();
            } else if (t.startsWith("reverse_proxy umami")) {
                parents.add(open.isEmpty() ? "(top level)" : open.peek());
            }
        }
        return parents;
    }

    private static String caddyDirectives() throws IOException {
        return stripComments(Files.readString(Paths.get("../deploy/caddy/Caddyfile"), StandardCharsets.UTF_8));
    }

    private static String stripComments(String text) {
        return text.replaceAll("(?m)#.*$", "");
    }

    /**
     * Docker's default json-file driver never rotates, and every Pressroom logger
     * now writes to stdout: without these options the host's container logs
     * grow until the disk fills. One-shots included -- an exited container
     * keeps its log until it is removed.
     */
    @SuppressWarnings("unchecked")
    @Test
    void everyServiceRotatesItsContainerLog() {
        services().forEach((name, raw) -> {
            Map<String, Object> logging = (Map<String, Object>) ((Map<String, Object>) raw).get("logging");
            assertNotNull(logging, name + " sets no logging options, so its container log never rotates");
            assertEquals("json-file", String.valueOf(logging.get("driver")), name);
            Map<String, Object> options = (Map<String, Object>) logging.get("options");
            assertNotNull(options, name + " has no logging options");
            assertTrue(String.valueOf(options.get("max-size")).matches("\\d+m"),
                    name + " caps each log file in megabytes: " + options);
            assertTrue(String.valueOf(options.get("max-file")).matches("\\d+"),
                    name + " keeps a bounded number of rotated files: " + options);
        });
    }

    @Test
    void everyImageOfOursIsAPressroomImage() {
        List<String> ours = new ArrayList<>();
        services().forEach((name, svc) -> {
            String image = String.valueOf(((Map<?, ?>) svc).get("image"));
            if (image.startsWith("ghcr.io/jakefearsd/")) {
                ours.add(name);
                String expected = "caddy".equals(name) ? "ghcr.io/jakefearsd/pressroom-caddy:"
                        : "ghcr.io/jakefearsd/pressroom:";
                assertTrue(image.startsWith(expected + "${IMAGE_VERSION"), name + ": " + image);
            }
        });
        assertEquals(Set.of("provision", "admin", "app", "caddy", "analytics-views", "backup"), Set.copyOf(ours));
    }

    /** The volume names the cutover copies into; caddy's keep their names, under the new project. */
    @Test
    void theVolumesArePressroomVolumes() {
        assertEquals(Set.of("pressroom-pgdata", "pressroom-mediafiles", "pressroom-search-index",
                        "pressroom-uploads", "pressroom-backups", "caddy-data", "caddy-config"),
                ((Map<?, ?>) compose.get("volumes")).keySet());
        Map<String, Object> pg = service("postgres");
        assertEquals("${POSTGRES_DB:-pressroom}", String.valueOf(((Map<?, ?>) pg.get("environment")).get("POSTGRES_DB")));
        assertEquals("${POSTGRES_USER:-pressroom}",
                String.valueOf(((Map<?, ?>) pg.get("environment")).get("POSTGRES_USER")));
    }

    /** Machine names only: the prose word is Phase 3. */
    @Test
    void theFileNamesNoOldMachineName() throws IOException {
        String text = Files.readString(COMPOSE, StandardCharsets.UTF_8);
        Matcher m = Pattern.compile("(?<![A-Za-z])roller|ROLLER").matcher(text); // rebrand:keep
        List<String> found = new ArrayList<>();
        while (m.find()) {
            int start = text.lastIndexOf('\n', m.start()) + 1;
            int end = text.indexOf('\n', m.start());
            found.add(text.substring(start, end < 0 ? text.length() : end).strip());
        }
        assertEquals(List.of(), found);
    }

    @Test
    void theAppTakesItsConfigurationFromEnvFile() {
        assertEquals(".env", String.valueOf(service("app").get("env_file")),
                "the app's whole configuration arrives as PRESSROOM_* variables from .env");
    }

    @Test
    void analyticsViewsNeverRestarts() {
        // A restarting one-shot that waits up to ANALYTICS_VIEWS_WAIT_SECONDS
        // (default 180s) for website_event to appear would hammer the
        // database indefinitely if that table is never going to show up
        // (Umami misconfigured, or simply not deployed on this stack) --
        // restart: "no" is what makes a timeout here a one-time, logged
        // no-op instead of a permanent background retry storm.
        assertEquals("no", String.valueOf(service("analytics-views").get("restart")),
                "analytics-views must not restart -- see the comment above for why a "
                        + "restarting one-shot here is actively harmful, not just wasteful");
    }

    @Test
    @SuppressWarnings("unchecked")
    void everyEntrypointPathIsActuallyBakedIntoTheImage() throws IOException {
        // C1: deploy/backup/loop.sh called the absolute path /backup.sh, which
        // existed only under the OLD compose file's bind mounts. The image now
        // bakes the pair at /app/backup/{loop,backup}.sh, and nothing caught
        // the drift -- loop.sh's own call site was never touched by the wave
        // that moved the scripts, so no per-task review ever looked at it, and
        // this compose file's shape was pinned thoroughly while the image's
        // internal layout was pinned nowhere at all. That gap is what this
        // test closes: for every service whose entrypoint names an absolute
        // path under /app, the root Dockerfile must actually create that path
        // (a COPY destination or a chmod target) -- reading the Dockerfile as
        // plain text, the same idiom this class already uses for the compose
        // file.
        Path dockerfile = Paths.get("../Dockerfile");
        assertTrue(Files.exists(dockerfile), "missing " + dockerfile.toAbsolutePath());
        String dockerfileText = Files.readString(dockerfile, StandardCharsets.UTF_8);

        List<String> offenders = new ArrayList<>();
        for (Map.Entry<String, Object> entry : services().entrySet()) {
            Map<String, Object> svc = (Map<String, Object>) entry.getValue();
            Object entrypointRaw = svc.get("entrypoint");
            if (entrypointRaw == null) {
                continue;
            }
            String entrypointPath;
            if (entrypointRaw instanceof List<?> list) {
                assertFalse(list.isEmpty(), entry.getKey() + "'s entrypoint list is empty");
                entrypointPath = String.valueOf(list.get(0));
            } else {
                entrypointPath = String.valueOf(entrypointRaw).trim().split("\\s+")[0];
            }
            if (!entrypointPath.startsWith("/app/")) {
                continue;
            }
            if (!dockerfileText.contains(entrypointPath)) {
                offenders.add(entry.getKey() + ": " + entrypointPath);
            }
        }
        assertTrue(offenders.isEmpty(),
                "these services declare an /app/* entrypoint the Dockerfile never creates "
                        + "(no matching COPY destination or chmod target): " + offenders);
    }

    @Test
    void analyticsViewsGateNothing() {
        assertNotNull(services().get("analytics-views"),
                "the analytics views one-shot must exist");

        for (Map.Entry<String, Object> entry : services().entrySet()) {
            @SuppressWarnings("unchecked")
            Object dependsOn = ((Map<String, Object>) entry.getValue()).get("depends_on");
            if (dependsOn instanceof Map<?, ?> conditions) {
                assertFalse(conditions.containsKey("analytics-views"),
                        entry.getKey() + " must not wait on analytics-views: it installs a Grafana "
                                + "dashboard view, and a failure there must never keep the blog down");
            } else if (dependsOn instanceof List<?> list) {
                assertFalse(list.contains("analytics-views"),
                        entry.getKey() + " must not wait on analytics-views");
            }
        }
    }

    @Test
    void caddyDockerfileBuildsWithXcaddyAndNamesADnsProviderModule() throws IOException {
        // Per-weblog custom domains (virtual hosting) need a wildcard TLS
        // certificate, which means DNS-01 -- something stock `caddy:*` cannot
        // do at all, since DNS-01 requires a provider-specific module compiled
        // into the binary. A plain `FROM caddy` builds and runs just fine and
        // fails only at certificate-request time, in production, on a
        // wildcard that never arrives. Reading the Dockerfile as plain text
        // (the same idiom everyEntrypointPathIsActuallyBakedIntoTheImage above
        // uses on the root Dockerfile) is what makes a future revert to a
        // stock image fail the BUILD instead of failing silently later.
        Path dockerfile = Paths.get("../deploy/caddy/Dockerfile");
        assertTrue(Files.exists(dockerfile), "missing " + dockerfile.toAbsolutePath());
        String text = Files.readString(dockerfile, StandardCharsets.UTF_8);

        assertTrue(text.contains("xcaddy build"),
                "the Caddy image must be built with xcaddy -- stock caddy:* has no "
                        + "DNS-01 provider modules compiled in");
        assertTrue(text.matches("(?s).*--with\\s+github\\.com/caddy-dns/\\S+.*"),
                "the xcaddy build must name a github.com/caddy-dns/<provider> module, "
                        + "or DNS-01 has nothing to authenticate against the zone with");
        assertTrue(text.contains("AS builder") && text.contains("COPY --from=builder"),
                "the xcaddy-built binary must be copied into a separate runtime stage -- "
                        + "shipping the builder stage itself would ship Go toolchain and "
                        + "source into production");
    }

    /**
     * C1: the wildcard {@code *.{$VHOST_ZONE}} site block (per-weblog custom
     * domains) proxies to {@code app:8080} the same way the
     * {@code {$SITE_DOMAIN}} block does, but had no {@code handle_path
     * /analytics/*} block in front of it. {@code #showAnalyticsTrackingCode}
     * emits a root-relative {@code /analytics/script.js} and posts its beacon
     * to {@code /analytics/api/send}; on a custom domain those paths reach
     * the app directly (nothing proxies them to Umami), where the mapper is
     * in vhost mode and {@code analytics} is not a protected path, so both
     * requests 404 -- silently, since it is same-origin (no CSP error) and
     * same-server (no proxy error). Reading the Caddyfile as plain text, the
     * same idiom {@link #caddyDockerfileBuildsWithXcaddyAndNamesADnsProviderModule}
     * uses on the sibling Dockerfile: every top-level site block that
     * reverse-proxies to the app must also carry the analytics handle, so the
     * two cannot silently drift apart again.
     */
    @Test
    void everySiteBlockThatProxiesToTheAppAlsoHandlesAnalytics() throws IOException {
        Path caddyfile = Paths.get("../deploy/caddy/Caddyfile");
        assertTrue(Files.exists(caddyfile), "missing " + caddyfile.toAbsolutePath());
        String text = Files.readString(caddyfile, StandardCharsets.UTF_8);

        List<String> offenders = new ArrayList<>();
        for (String block : topLevelCaddyBlocks(text)) {
            String directives = stripComments(block);
            if (directives.contains("reverse_proxy app:8080") && !directives.contains("@tracker path /analytics/")) {
                offenders.add(firstNonBlankLine(directives));
            }
        }
        assertTrue(offenders.isEmpty(),
                "every Caddyfile site block that reverse-proxies to app:8080 must also carry "
                        + "the /analytics tracker allowlist (caddyAllowsOnlyTrackerPaths), or Umami "
                        + "collection silently 404s for every request on that host -- "
                        + "offending block(s) starting near: " + offenders);
    }

    /**
     * Splits a Caddyfile into its top-level {@code { ... }} site blocks by
     * brace depth. Caddy's own {@code {$VAR}} environment-substitution syntax
     * uses braces that are NOT block delimiters (e.g. {@code {$SITE_DOMAIN}},
     * {@code {$VHOST_DNS_API_TOKEN}} nested inside the {@code tls} block), so
     * those are blanked out first -- otherwise they would desync the brace
     * count and either merge blocks together or split one apart.
     */
    private static List<String> topLevelCaddyBlocks(String text) {
        // Blanked to spaces rather than removed, so positions still index the
        // original text: each block comes back verbatim, placeholders and
        // comments included. Comments are blanked for counting too -- a
        // commented-out directive may carry a brace.
        String blanked = blank(blank(text, Pattern.compile("#[^\\n]*")), Pattern.compile("\\{\\$[^}]*}"));

        List<String> blocks = new ArrayList<>();
        int depth = 0;
        int start = 0;
        for (int i = 0; i < blanked.length(); i++) {
            char c = blanked.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    blocks.add(text.substring(start, i + 1));
                    start = i + 1;
                }
            }
        }
        return blocks;
    }

    private static String blank(String text, Pattern pattern) {
        Matcher m = pattern.matcher(text);
        StringBuilder out = new StringBuilder(text);
        while (m.find()) {
            for (int i = m.start(); i < m.end(); i++) {
                out.setCharAt(i, ' ');
            }
        }
        return out.toString();
    }

    private static String firstNonBlankLine(String block) {
        for (String line : block.split("\n")) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty()) {
                return trimmed;
            }
        }
        return block.trim();
    }

    @Test
    @SuppressWarnings("unchecked")
    void theAdminToolIsProfileGatedAndReachesOnlyTheDatabase() {
        Map<String, Object> admin = service("admin");
        assertEquals(List.of("tools"), admin.get("profiles"),
                "admin must never start with `up`: only `docker compose run` may start it");
        assertEquals(service("app").get("image"), admin.get("image"),
                "the tool must ship in the very image the app runs");
        assertEquals(List.of("/app/admin-account.sh"), admin.get("entrypoint"));
        assertEquals("no", admin.get("restart"));
        assertNull(admin.get("ports"), "the admin tool must publish nothing");
        assertNull(admin.get("env_file"),
                "env_file would hand the tool every secret in .env; it needs the database's only");
        assertNull(admin.get("volumes"));
        assertEquals(List.of("internal"), admin.get("networks"));
        Map<String, Object> env = (Map<String, Object>) admin.get("environment");
        assertEquals(Set.of("POSTGRES_DB", "POSTGRES_USER", "POSTGRES_PASSWORD"), env.keySet());
        Map<String, Object> deps = (Map<String, Object>) admin.get("depends_on");
        assertEquals(Map.of("condition", "service_healthy"), deps.get("postgres"));
    }
}
