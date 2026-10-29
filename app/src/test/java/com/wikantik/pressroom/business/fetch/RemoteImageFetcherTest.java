package com.wikantik.pressroom.business.fetch;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.ServerSocket;
import java.net.SocketAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.hc.client5.http.DnsResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RemoteImageFetcherTest {

    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 1, 2, 3, 4};
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 9, 9};
    private static final String NOT_PUBLIC = "That address is not on the public internet.";

    private HttpServer server;
    private ExecutorService handlers;
    private RemoteImageFetcher fetcher;
    private final AtomicInteger hits = new AtomicInteger();

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        // A pool, not the dispatcher thread: a stalled handler must not block stop().
        handlers = Executors.newCachedThreadPool();
        server.setExecutor(handlers);
        server.start();
        fetcher = new RemoteImageFetcher(permissive(Duration.ofSeconds(2)));
    }

    @AfterEach
    void stop() {
        fetcher.close();
        server.stop(0);
        handlers.shutdownNow();
    }

    private static RemoteImageFetcher.Policy permissive(Duration deadline) {
        return new RemoteImageFetcher.Policy(a -> true, true, Duration.ofSeconds(2), deadline, 3);
    }

    private String url(String path) {
        return "http://127.0.0.1:" + server.getAddress().getPort() + path;
    }

    private void serve(String path, int status, String contentType, byte[] body) {
        server.createContext(path, exchange -> {
            hits.incrementAndGet();
            exchange.getResponseHeaders().add("Content-Type", contentType);
            exchange.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
    }

    private void redirect(String path, String location) {
        server.createContext(path, exchange -> {
            exchange.getResponseHeaders().add("Location", location);
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
    }

    private static String refusal(RemoteImageFetcher f, String url, long max) {
        return assertThrows(FetchRefusal.class, () -> f.fetch(url, max, null)).getMessage();
    }

    @Test
    void fetchesAJpegNamedFromTheUrlWithTheDetectedExtension() throws Exception {
        serve("/photos/sunset", 200, "image/jpeg", JPEG);
        FetchedImage image = fetcher.fetch(url("/photos/sunset?w=800"), 1024, null);
        assertEquals("sunset.jpg", image.fileName());
        assertEquals(ImageKind.JPEG, image.kind());
        assertArrayEquals(JPEG, image.bytes());
        assertEquals(JPEG.length, image.size());
    }

    @Test
    void theTypeComesFromTheBytesNotTheHeaderAndThePreferredNameWins() throws Exception {
        serve("/x.jpg", 200, "text/plain", PNG);
        FetchedImage image = fetcher.fetch(url("/x.jpg"), 1024, "Harbour at dusk.jpeg");
        assertEquals(ImageKind.PNG, image.kind());
        assertEquals("Harbour-at-dusk.png", image.fileName());
    }

    @Test
    void aUrlWithNoUsableNameIsCalledImage() throws Exception {
        serve("/", 200, "image/png", PNG);
        assertEquals("image.png", fetcher.fetch(url("/"), 1024, "  ").fileName());
        assertEquals("image.png", fetcher.fetch(url("/"), 1024, "...").fileName());
    }

    @Test
    void aNameIsReducedToSafeCharactersAndAHundredOfThem() {
        URI any = URI.create("https://example.com/a");
        assertEquals("photo.jpg", RemoteImageFetcher.fileName(null, URI.create("https://example.com/photo?id=7"),
                ImageKind.JPEG));
        assertEquals("etc-passwd.gif", RemoteImageFetcher.fileName("../etc/passwd", any, ImageKind.GIF));
        assertEquals("hidden.webp", RemoteImageFetcher.fileName(".hidden", any, ImageKind.WEBP));
        assertEquals("x".repeat(100) + ".png", RemoteImageFetcher.fileName("x".repeat(300), any, ImageKind.PNG));
    }

    @Test
    void theRequestNamesPressroomAndAsksForImagesUncompressed() throws Exception {
        AtomicReference<Headers> seen = new AtomicReference<>();
        server.createContext("/who", exchange -> {
            seen.set(exchange.getRequestHeaders());
            exchange.sendResponseHeaders(200, JPEG.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(JPEG);
            }
        });
        fetcher.fetch(url("/who"), 1024, null);
        assertTrue(seen.get().getFirst("User-Agent").matches("Pressroom/\\S+"), seen.get().getFirst("User-Agent"));
        assertTrue(seen.get().getFirst("Accept").startsWith("image/"), seen.get().getFirst("Accept"));
        assertNull(seen.get().getFirst("Accept-Encoding"));
    }

    @Test
    void htmlAnSvgAndAnEmptyBodyAreNotImages() {
        serve("/fake.jpg", 200, "image/jpeg", "<!doctype html><p>hi".getBytes(StandardCharsets.US_ASCII));
        serve("/logo.svg", 200, "image/svg+xml",
                "<svg xmlns=\"http://www.w3.org/2000/svg\"/>".getBytes(StandardCharsets.US_ASCII));
        serve("/empty", 200, "image/png", new byte[0]);
        for (String path : new String[] {"/fake.jpg", "/logo.svg", "/empty"}) {
            assertEquals("Not a JPEG, PNG, GIF or WebP image.", refusal(fetcher, url(path), 1024), path);
        }
    }

    @Test
    void aNon200AnswerIsReported() {
        serve("/gone", 404, "text/plain", "no".getBytes(StandardCharsets.US_ASCII));
        assertEquals("The image server answered 404.", refusal(fetcher, url("/gone"), 1024));
    }

    @Test
    void threeRedirectsAreFollowedAndAFourthIsRefused() throws Exception {
        serve("/final", 200, "image/jpeg", JPEG);
        redirect("/r1", "/final");
        redirect("/r2", "/r1");
        redirect("/r3", "/r2");
        redirect("/r4", "/r3");
        assertEquals(ImageKind.JPEG, fetcher.fetch(url("/r3"), 1024, null).kind());
        assertTrue(refusal(fetcher, url("/r4"), 1024).startsWith("The image server redirected more than 3 times"));
    }

    @Test
    void aRedirectToARefusedAddressIsRefused() {
        RemoteImageFetcher strict = new RemoteImageFetcher(new RemoteImageFetcher.Policy(
                a -> "127.0.0.1".equals(a.getHostAddress()), true, Duration.ofSeconds(2), Duration.ofSeconds(2), 3));
        try {
            redirect("/to-metadata", "http://169.254.169.254/latest/meta-data/");
            assertEquals(NOT_PUBLIC, refusal(strict, url("/to-metadata"), 1024));
        } finally {
            strict.close();
        }
    }

    @Test
    void aRedirectIsCheckedAsANewUrl() {
        redirect("/to-ftp", "ftp://example.com/a.jpg");
        redirect("/to-nohost", "http://127.1/a.jpg");
        redirect("/to-garbage", "http://exa mple.com/");
        serve("/final", 200, "image/jpeg", JPEG);
        redirect("/to-userinfo", "http://user:pw@127.0.0.1:" + server.getAddress().getPort() + "/final");
        assertEquals("A URL with a user name or password is not fetched.",
                refusal(fetcher, url("/to-userinfo"), 1024));
        assertEquals(0, hits.get());
        assertEquals("Only http and https URLs are fetched.", refusal(fetcher, url("/to-ftp"), 1024));
        assertEquals("That is not a valid http or https URL.", refusal(fetcher, url("/to-nohost"), 1024));
        assertEquals("The image server sent an invalid redirect.", refusal(fetcher, url("/to-garbage"), 1024));
    }

    @Test
    void aBodyOverTheLimitIsRefusedWithOrWithoutAContentLength() {
        serve("/big", 200, "image/jpeg", new byte[2048]);
        server.createContext("/chunked", exchange -> {
            exchange.sendResponseHeaders(200, 0); // chunked: no Content-Length
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(JPEG);
                out.write(new byte[4096]);
            }
        });
        assertTrue(refusal(fetcher, url("/big"), 1024).startsWith("Larger than this site's"));
        assertTrue(refusal(fetcher, url("/chunked"), 1024).startsWith("Larger than this site's"));
    }

    @Test
    void aBodyExactlyAtTheLimitIsAccepted() throws Exception {
        serve("/exact", 200, "image/jpeg", JPEG);
        assertArrayEquals(JPEG, fetcher.fetch(url("/exact"), JPEG.length, null).bytes());
        assertTrue(refusal(fetcher, url("/exact"), JPEG.length - 1).startsWith("Larger than this site's"));
    }

    /**
     * Closing an HttpClient response whose body is unread drains the body so
     * the connection can be reused. Over the limit, the fetch must drop the
     * connection instead: an endless body would otherwise be read until the
     * deadline, long after the answer is known.
     */
    @Test
    void anEndlessBodyIsAbandonedAtTheLimitNotDrained() {
        server.createContext("/endless", exchange -> {
            exchange.sendResponseHeaders(200, 0);
            byte[] chunk = new byte[64 * 1024];
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(JPEG);
                while (!Thread.currentThread().isInterrupted()) {
                    out.write(chunk);
                }
            }
        });
        RemoteImageFetcher patient = new RemoteImageFetcher(permissive(Duration.ofSeconds(30)));
        try {
            long started = System.nanoTime();
            assertTrue(refusal(patient, url("/endless"), 1024).startsWith("Larger than this site's"));
            assertTrue(Duration.ofNanos(System.nanoTime() - started).toMillis() < 10_000);
        } finally {
            patient.close();
        }
    }

    @Test
    void aServerThatStopsSendingIsAbandonedAtTheDeadline() {
        server.createContext("/stall", exchange -> {
            exchange.sendResponseHeaders(200, 0);
            OutputStream out = exchange.getResponseBody();
            out.write(JPEG);
            out.flush();
            try {
                Thread.sleep(10_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            exchange.close();
        });
        RemoteImageFetcher quick = new RemoteImageFetcher(new RemoteImageFetcher.Policy(
                a -> true, true, Duration.ofSeconds(1), Duration.ofMillis(500), 3));
        try {
            long started = System.nanoTime();
            assertEquals("The image server took too long.", refusal(quick, url("/stall"), 1 << 20));
            assertTrue(Duration.ofNanos(System.nanoTime() - started).toMillis() < 3000);
        } finally {
            quick.close();
        }
    }

    /**
     * A server that keeps sending, slowly, never trips the socket timeout: each
     * read gets a byte well inside it. Only the whole-fetch deadline stops it.
     */
    @Test
    void aServerThatTricklesIsAbandonedAtTheWholeFetchDeadline() {
        server.createContext("/trickle", exchange -> {
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(JPEG);
                out.flush();
                for (int i = 0; i < 40; i++) { // about 6 s, then a complete image
                    Thread.sleep(150);
                    out.write(0);
                    out.flush();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        RemoteImageFetcher deadlined = new RemoteImageFetcher(permissive(Duration.ofSeconds(1)));
        try {
            long started = System.nanoTime();
            assertEquals("The image server took too long.", refusal(deadlined, url("/trickle"), 1 << 20));
            assertTrue(Duration.ofNanos(System.nanoTime() - started).toMillis() < 4000);
        } finally {
            deadlined.close();
        }
    }

    /**
     * The deadline can pass while the client is still connecting, here while
     * it resolves the host. The client then fails with a runtime exception
     * rather than an IOException; that is "too long" too, never a 500.
     */
    @Test
    void aDeadlineThatPassesWhileConnectingIsTooLong() {
        serve("/slow-lookup", 200, "image/jpeg", JPEG);
        RemoteImageFetcher slowLookup = new RemoteImageFetcher(new RemoteImageFetcher.Policy(a -> {
            pause(Duration.ofMillis(600));
            return true;
        }, true, Duration.ofSeconds(2), Duration.ofMillis(200), 3));
        try {
            // A host name, not a literal: only the client's resolver consults the slow policy.
            assertEquals("The image server took too long.", refusal(slowLookup,
                    "http://localhost:" + server.getAddress().getPort() + "/slow-lookup", 1024));
        } finally {
            slowLookup.close();
        }
    }

    /**
     * A JVM-wide SOCKS proxy (socksProxyHost, or the default ProxySelector)
     * would otherwise carry the connection: the JDK's plain Socket asks the
     * selector about every socket:// connection it makes.
     */
    @Test
    void noJvmWideProxyIsConsulted() throws Exception {
        serve("/direct", 200, "image/jpeg", JPEG);
        String ours = ":" + server.getAddress().getPort();
        List<URI> asked = new CopyOnWriteArrayList<>();
        ProxySelector previous = ProxySelector.getDefault();
        ProxySelector.setDefault(new ProxySelector() {
            @Override
            public List<Proxy> select(URI uri) {
                if (String.valueOf(uri.getAuthority()).endsWith(ours)) {
                    asked.add(uri);
                }
                return previous == null ? List.of(Proxy.NO_PROXY) : previous.select(uri);
            }

            @Override
            public void connectFailed(URI uri, SocketAddress address, IOException e) {
                // nothing to remember
            }
        });
        try {
            assertEquals(ImageKind.JPEG, fetcher.fetch(url("/direct"), 1024, null).kind());
        } finally {
            ProxySelector.setDefault(previous);
        }
        assertEquals(List.of(), asked);
    }

    private static void pause(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    void aSpentDeadlineRefusesBeforeAnyRequest() {
        serve("/never", 200, "image/jpeg", JPEG);
        RemoteImageFetcher spent = new RemoteImageFetcher(permissive(Duration.ZERO));
        try {
            assertEquals("The image server took too long.", refusal(spent, url("/never"), 1024));
        } finally {
            spent.close();
        }
        assertEquals(0, hits.get());
    }

    @Test
    void aServerThatIsNotListeningIsUnreachable() throws Exception {
        int closedPort;
        try (ServerSocket probe = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            closedPort = probe.getLocalPort();
        }
        assertEquals("The image server could not be reached.",
                refusal(fetcher, "http://127.0.0.1:" + closedPort + "/a.jpg", 1024));
    }

    @Test
    void aHostThatDoesNotResolveIsNotFound() {
        // .invalid never resolves (RFC 6761). A long deadline, so a slow resolver
        // is still "not found" rather than "took too long".
        RemoteImageFetcher patient = new RemoteImageFetcher(permissive(Duration.ofSeconds(30)));
        try {
            assertEquals("That host could not be found.",
                    refusal(patient, "http://no-such-host.invalid/a.jpg", 1024));
        } finally {
            patient.close();
        }
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "ftp://example.com/a.jpg          | Only http and https URLs are fetched.",
            "file:///etc/passwd               | Only http and https URLs are fetched.",
            "http://example.com:8080/a.jpg    | Only ports 80 and 443 are fetched.",
            "https://example.com:8443/a.jpg   | Only ports 80 and 443 are fetched.",
            "http://user:pw@example.com/a.jpg | A URL with a user name or password is not fetched.",
            "http://user@example.com/a.jpg    | A URL with a user name or password is not fetched.",
            "not a url                        | That is not a valid http or https URL.",
            "/relative.jpg                    | That is not a valid http or https URL."})
    void theProductionPolicyRefusesTheseUrlsBeforeConnecting(String url, String detail) {
        RemoteImageFetcher production = new RemoteImageFetcher();
        try {
            assertEquals(detail, refusal(production, url, 1024));
        } finally {
            production.close();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://127.0.0.1/", "HTTPS://127.0.0.1/", "http://[::1]/", "http://[::ffff:127.0.0.1]/",
            "http://10.0.0.1/", "http://169.254.169.254/", "http://localhost/", "http://[fe80::1%25nosuchif]/"})
    void theProductionPolicyRefusesInternalAddresses(String url) {
        RemoteImageFetcher production = new RemoteImageFetcher();
        try {
            assertEquals(NOT_PUBLIC, refusal(production, url, 1024));
        } finally {
            production.close();
        }
        assertEquals(0, hits.get());
    }

    @Test
    void aDecimalIpv4LiteralIsRefusedEitherWay() {
        RemoteImageFetcher production = new RemoteImageFetcher();
        try {
            // Java may read 2130706433 as 127.0.0.1 (not public) or as a hostname (not found): refused either way.
            assertThrows(FetchRefusal.class, () -> production.fetch("http://2130706433/", 1024, null));
        } finally {
            production.close();
        }
    }

    /**
     * Records what HttpClient 5.6.4 does with an IP-literal host: it still
     * asks the DnsResolver, so a literal is judged twice, once by the
     * fetcher's own check before the request and once by the resolver. Either
     * answer is safe, because the fetcher's check comes first; if an upgrade
     * changes this, only this record of the client's behaviour is stale.
     */
    @Test
    void theClientAlsoAsksTheResolverAboutAnIpLiteralHost() throws Exception {
        AtomicInteger judged = new AtomicInteger();
        RemoteImageFetcher counting = new RemoteImageFetcher(new RemoteImageFetcher.Policy(a -> {
            judged.incrementAndGet();
            return true;
        }, true, Duration.ofSeconds(2), Duration.ofSeconds(2), 3));
        try {
            serve("/counted", 200, "image/jpeg", JPEG);
            counting.fetch(url("/counted"), 1024, null);
            assertEquals(2, judged.get());
        } finally {
            counting.close();
        }
    }

    @Test
    void aHostIsRefusedWhenAnyOfItsAddressesIsNotPublic() throws Exception {
        DnsResolver checking = new RemoteImageFetcher.CheckingResolver(fixed(
                InetAddress.getByName("93.184.215.14"), InetAddress.getByName("10.0.0.5")), PublicAddresses::isPublic);
        assertThrows(RemoteImageFetcher.NotPublicException.class, () -> checking.resolve("mixed.example"));
        assertThrows(RemoteImageFetcher.NotPublicException.class, () -> checking.resolve("mixed.example", 443));
    }

    @Test
    void aHostWhoseAddressesAreAllPublicResolves() throws Exception {
        InetAddress one = InetAddress.getByName("93.184.215.14");
        InetAddress two = InetAddress.getByName("2606:4700:4700::1111");
        DnsResolver checking = new RemoteImageFetcher.CheckingResolver(fixed(one, two), PublicAddresses::isPublic);
        assertArrayEquals(new InetAddress[] {one, two}, checking.resolve("public.example"));
        assertEquals(List.of(new InetSocketAddress(one, 443), new InetSocketAddress(two, 443)),
                checking.resolve("public.example", 443));
        assertEquals("canonical.public.example", checking.resolveCanonicalHostname("public.example"));
    }

    /** A delegate that answers nothing must not become an unresolved address the client then connects to. */
    @Test
    void aResolverThatAnswersNothingIsNotFound() {
        DnsResolver checking = new RemoteImageFetcher.CheckingResolver(fixed((InetAddress[]) null), a -> true);
        DnsResolver empty = new RemoteImageFetcher.CheckingResolver(fixed(), a -> true);
        assertThrows(UnknownHostException.class, () -> checking.resolve("nothing.example"));
        assertThrows(UnknownHostException.class, () -> checking.resolve("nothing.example", 443));
        assertThrows(UnknownHostException.class, () -> empty.resolve("nothing.example"));
        assertThrows(UnknownHostException.class, () -> empty.resolve("nothing.example", 443));
    }

    private static DnsResolver fixed(InetAddress... addresses) {
        return new DnsResolver() {
            @Override
            public InetAddress[] resolve(String host) {
                return addresses;
            }

            @Override
            public String resolveCanonicalHostname(String host) {
                return "canonical." + host;
            }
        };
    }
}
