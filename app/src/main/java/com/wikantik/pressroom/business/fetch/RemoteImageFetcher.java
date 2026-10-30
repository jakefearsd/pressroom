package com.wikantik.pressroom.business.fetch;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.Socket;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.SchemePortResolver;
import org.apache.hc.client5.http.SystemDefaultDnsResolver;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.DefaultHttpClientConnectionOperator;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.client5.http.io.HttpClientConnectionOperator;
import org.apache.hc.client5.http.ssl.TlsSocketStrategy;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.http.HttpHeaders;
import org.apache.hc.core5.http.HttpStatus;
import org.apache.hc.core5.http.URIScheme;
import org.apache.hc.core5.http.config.RegistryBuilder;
import org.apache.hc.core5.http.io.SocketConfig;
import org.apache.hc.core5.io.Closer;
import org.apache.hc.core5.io.CloseMode;
import org.apache.hc.core5.net.InetAddressUtils;
import org.apache.hc.core5.util.Timeout;
import org.springframework.stereotype.Component;

/**
 * Fetches one image from a public URL for {@code media import}. Pressroom runs
 * inside a home network, so the fetcher must never become a way to reach it:
 * every address it connects to is checked (the client's own DnsResolver
 * refuses a host when ANY of its addresses is not public, and the connection
 * then goes to an address that passed), IP-literal hosts are checked before
 * the request, redirects are followed here and re-checked, and no proxy is
 * used. There is no property, profile or flag that loosens this. Only the
 * package-private constructor takes another {@link Policy}, for tests that
 * need a local server.
 */
@Component
public class RemoteImageFetcher implements AutoCloseable {

    /** What a fetch may do. {@link #PRODUCTION} is the only policy outside tests. */
    record Policy(Predicate<InetAddress> addresses, boolean anyPort, Duration connectTimeout,
                  Duration deadline, int maxRedirects) {
        static final Policy PRODUCTION = new Policy(PublicAddresses::isPublic, false,
                Duration.ofSeconds(5), Duration.ofSeconds(30), 3);
    }

    static final String NOT_PUBLIC = "That address is not on the public internet.";
    static final String NOT_AN_IMAGE = "Not a JPEG, PNG, GIF or WebP image.";
    static final String TOO_SLOW = "The image server took too long.";
    private static final String NOT_A_URL = "That is not a valid http or https URL.";
    private static final int MAX_NAME = 100;
    private static final Pattern EXTENSION = Pattern.compile("\\.[A-Za-z0-9]{1,5}$");
    private static final Pattern UNSAFE = Pattern.compile("[^A-Za-z0-9._-]+");
    private static final Pattern LOOSE_ENDS = Pattern.compile("^[-.]+|[-.]+$");

    private final Policy policy;
    private final CloseableHttpClient client;
    private final ScheduledExecutorService deadlines;

    public RemoteImageFetcher() {
        this(Policy.PRODUCTION);
    }

    RemoteImageFetcher(Policy policy) {
        this.policy = policy;
        // No useSystemProperties(), here or on the connection manager: that is what would pick up a JVM proxy.
        this.client = HttpClients.custom()
                .setConnectionManager(connections(policy))
                .disableRedirectHandling()
                .disableAutomaticRetries()
                .disableContentCompression()
                .disableCookieManagement()
                .disableAuthCaching()
                .setUserAgent("Pressroom/" + version())
                .build();
        this.deadlines = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "pressroom-image-fetch-deadline");
            t.setDaemon(true);
            return t;
        });
    }

    /** Connections that resolve through the policy's check and open direct sockets; the client owns them. */
    private static PoolingHttpClientConnectionManager connections(Policy policy) {
        return new DirectConnectionsBuilder()
                .setDnsResolver(new CheckingResolver(SystemDefaultDnsResolver.INSTANCE, policy.addresses()))
                .setDefaultConnectionConfig(ConnectionConfig.custom()
                        .setConnectTimeout(Timeout.of(policy.connectTimeout()))
                        .setSocketTimeout(Timeout.of(policy.deadline()))
                        .build())
                // Also bounds the TLS handshake, which runs before the socket timeout above applies.
                .setDefaultSocketConfig(SocketConfig.custom().setSoTimeout(Timeout.of(policy.deadline())).build())
                .build();
    }

    public FetchedImage fetch(String rawUrl, long maxBytes, String preferredName) throws FetchRefusal {
        URI original = parse(rawUrl);
        long deadline = System.nanoTime() + policy.deadline().toNanos();
        URI uri = original;
        for (int redirects = 0; ; redirects++) {
            checkUrl(uri);
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                throw new FetchRefusal(TOO_SLOW);
            }
            Answer answer = exchange(uri, remaining, maxBytes);
            if (answer.location() == null) {
                ImageKind kind = ImageKind.sniff(answer.body()).orElseThrow(() -> new FetchRefusal(NOT_AN_IMAGE));
                return new FetchedImage(fileName(preferredName, original, kind), kind, answer.body());
            }
            if (redirects == policy.maxRedirects()) {
                throw new FetchRefusal("The image server redirected more than " + policy.maxRedirects() + " times.");
            }
            uri = resolveRedirect(uri, answer.location());
        }
    }

    /** One request: a redirect's target, or a 200's body read under the cap, all within the deadline. */
    // Not try-with-resources: the response must be closed AFTER get.cancel() (see
    // the finally block), and a resource would close before that block runs.
    @SuppressWarnings("PMD.UseTryWithResources")
    private Answer exchange(URI uri, long remainingNanos, long maxBytes) throws FetchRefusal {
        HttpGet get = new HttpGet(uri);
        get.setHeader(HttpHeaders.ACCEPT, "image/jpeg, image/png, image/gif, image/webp");
        ScheduledFuture<?> alarm = deadlines.schedule(get::cancel, remainingNanos, TimeUnit.NANOSECONDS);
        ClassicHttpResponse response = null;
        try {
            response = client.executeOpen(null, get, null);
            return answer(response, maxBytes);
        } catch (IOException e) {
            throw refusal(e, get.isCancelled());
        } catch (RuntimeException e) {
            // A deadline that passes between leasing a connection and using it
            // releases the endpoint under the client, which then throws an
            // IllegalStateException rather than an IOException.
            if (get.isCancelled()) {
                throw new FetchRefusal(TOO_SLOW, e);
            }
            throw e;
        } finally {
            alarm.cancel(false);
            // Closing a response whose body is unread drains it for connection
            // reuse; an endless body would be read until the deadline. Cancelling
            // first drops the connection instead (a no-op once the body was read
            // to its end, when the connection is already back in the pool).
            get.cancel();
            Closer.closeQuietly(response);
        }
    }

    private static Answer answer(ClassicHttpResponse response, long maxBytes) throws IOException, FetchRefusal {
        int status = response.getCode();
        Header location = response.getFirstHeader(HttpHeaders.LOCATION);
        if (status >= 300 && status < 400 && location != null) {
            return new Answer(location.getValue(), null);
        }
        if (status != HttpStatus.SC_OK) {
            throw new FetchRefusal("The image server answered " + status + ".");
        }
        return new Answer(null, readCapped(response.getEntity(), maxBytes));
    }

    /**
     * The detail for a failed request. The cause chain is walked because the
     * client may wrap what the resolver threw. A timeout of any kind (the
     * socket's, the connect's, or the deadline's cancellation) is "too long".
     */
    private static FetchRefusal refusal(IOException e, boolean cancelled) {
        boolean unknownHost = false;
        boolean slow = cancelled;
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof NotPublicException) {
                return new FetchRefusal(NOT_PUBLIC, e);
            }
            unknownHost |= t instanceof UnknownHostException;
            slow |= t instanceof InterruptedIOException;
        }
        if (slow) {
            return new FetchRefusal(TOO_SLOW, e);
        }
        if (unknownHost) {
            return new FetchRefusal("That host could not be found.", e);
        }
        return new FetchRefusal("The image server could not be reached.", e);
    }

    private static URI parse(String rawUrl) throws FetchRefusal {
        try {
            URI uri = new URI(rawUrl == null ? "" : rawUrl.trim());
            if (!uri.isAbsolute()) {
                throw new FetchRefusal(NOT_A_URL);
            }
            return uri;
        } catch (URISyntaxException e) {
            throw new FetchRefusal(NOT_A_URL, e);
        }
    }

    /** Scheme, host, port and user info for every hop; an IP-literal host is judged here, before any request. */
    @SuppressFBWarnings(
            value = "IMPROPER_UNICODE",
            justification = "Lower-cases a java.net.URI scheme to compare it with http and https: URI "
                    + "parsing admits only ASCII letters, digits, plus, minus and dot in a scheme (RFC "
                    + "3986), so nothing else can fold onto either.")
    private void checkUrl(URI uri) throws FetchRefusal {
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            throw new FetchRefusal("Only http and https URLs are fetched.");
        }
        String host = uri.getHost();
        if (host == null) {
            throw new FetchRefusal(NOT_A_URL);
        }
        if (uri.getRawUserInfo() != null) {
            throw new FetchRefusal("A URL with a user name or password is not fetched.");
        }
        int port = uri.getPort();
        if (!policy.anyPort() && port != -1 && port != 80 && port != 443) {
            throw new FetchRefusal("Only ports 80 and 443 are fetched.");
        }
        // A bracketed host is always an IPv6 literal; getByName does no lookup for a literal.
        boolean bracketed = host.startsWith("[");
        if (bracketed || InetAddressUtils.isIPv4(host)) {
            String bare = bracketed ? host.substring(1, host.length() - 1) : host;
            try {
                if (!policy.addresses().test(InetAddress.getByName(bare))) {
                    throw new FetchRefusal(NOT_PUBLIC);
                }
            } catch (UnknownHostException e) {
                throw new FetchRefusal(NOT_PUBLIC, e);
            }
        }
    }

    private static URI resolveRedirect(URI from, String location) throws FetchRefusal {
        try {
            return from.resolve(new URI(location.trim()));
        } catch (URISyntaxException | IllegalArgumentException e) {
            throw new FetchRefusal("The image server sent an invalid redirect.", e);
        }
    }

    /** Reads at most maxBytes, refusing at the first byte over, whatever Content-Length claimed. */
    private static byte[] readCapped(HttpEntity entity, long maxBytes) throws IOException, FetchRefusal {
        if (entity == null) {
            return new byte[0];
        }
        if (entity.getContentLength() > maxBytes) {
            throw tooLarge(maxBytes);
        }
        // Not closed here: closing the stream first reads the rest of the body,
        // however long. The stream is the response's, and exchange() drops the
        // connection before it closes the response.
        return readUpTo(entity.getContent(), maxBytes);
    }

    private static byte[] readUpTo(InputStream in, long maxBytes) throws IOException, FetchRefusal {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        for (int read = in.read(buffer); read != -1; read = in.read(buffer)) {
            if (out.size() + (long) read > maxBytes) {
                throw tooLarge(maxBytes);
            }
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }

    private static FetchRefusal tooLarge(long maxBytes) {
        return new FetchRefusal("Larger than this site's "
                + String.format(Locale.ROOT, "%.2f", maxBytes / 1048576.0) + " MB per-file limit.");
    }

    /**
     * The caller's name, else the URL's last path segment, else "image": its
     * extension dropped, reduced to letters, digits, dot, dash and underscore,
     * at most 100 of them, no dot or dash at either end; the extension then
     * comes from the bytes.
     */
    static String fileName(String preferred, URI original, ImageKind kind) {
        String base = preferred;
        if (base == null || base.isBlank()) {
            String path = original.getPath() == null ? "" : original.getPath();
            base = path.substring(path.lastIndexOf('/') + 1);
        }
        base = EXTENSION.matcher(base.trim()).replaceFirst("");
        base = UNSAFE.matcher(base).replaceAll("-");
        if (base.length() > MAX_NAME) {
            base = base.substring(0, MAX_NAME);
        }
        base = LOOSE_ENDS.matcher(base).replaceAll("");
        return (base.isEmpty() ? "image" : base) + "." + kind.extension();
    }

    private static String version() {
        Properties props = new Properties();
        try (InputStream in = RemoteImageFetcher.class.getResourceAsStream("/pressroom-version.properties")) {
            if (in != null) {
                props.load(in);
            }
        } catch (IOException e) {
            return "unknown";
        }
        return props.getProperty("ro.version", "unknown");
    }

    @Override
    public void close() {
        deadlines.shutdownNow();
        client.close(CloseMode.IMMEDIATE);
    }

    /**
     * HttpClient's own plain socket is {@code new Socket()}, which the JDK
     * routes through a JVM-wide SOCKS proxy ({@code socksProxyHost}, or the
     * default ProxySelector). The builder's operator hook is the one place to
     * hand the operator a direct socket instead; the proxy argument, which
     * would come from a SocketConfig SOCKS address this fetcher never sets,
     * is ignored.
     */
    private static final class DirectConnectionsBuilder extends PoolingHttpClientConnectionManagerBuilder {
        @Override
        protected HttpClientConnectionOperator createConnectionOperator(
                SchemePortResolver ports, DnsResolver dns, TlsSocketStrategy tls) {
            return new DefaultHttpClientConnectionOperator(DirectConnectionsBuilder::directSocket, ports, dns,
                    RegistryBuilder.<TlsSocketStrategy>create().register(URIScheme.HTTPS.id, tls).build());
        }

        @SuppressFBWarnings(
                value = "UNENCRYPTED_SOCKET",
                justification = "This is the transport under both schemes: https connections are wrapped "
                        + "in TLS by the TlsSocketStrategy registered beside it, and a plain http socket is "
                        + "used only because the author's image URL said http.")
        private static Socket directSocket(Proxy ignored) {
            return new Socket(Proxy.NO_PROXY);
        }
    }

    /** What one request came back with: a redirect's Location, or else a body. */
    private record Answer(String location, byte[] body) {
    }

    /** Resolves through the delegate, then refuses the host if ANY of its addresses fails the policy. */
    static final class CheckingResolver implements DnsResolver {
        private final DnsResolver delegate;
        private final Predicate<InetAddress> allowed;

        CheckingResolver(DnsResolver delegate, Predicate<InetAddress> allowed) {
            this.delegate = delegate;
            this.allowed = allowed;
        }

        @Override
        public InetAddress[] resolve(String host) throws UnknownHostException {
            InetAddress[] addresses = delegate.resolve(host);
            if (addresses == null || addresses.length == 0) {
                throw new UnknownHostException(host);
            }
            for (InetAddress address : addresses) {
                check(host, address);
            }
            return addresses;
        }

        /** The form the client calls; an answer it would turn into an unresolved address is refused too. */
        @Override
        public List<InetSocketAddress> resolve(String host, int port) throws UnknownHostException {
            List<InetSocketAddress> addresses = delegate.resolve(host, port);
            if (addresses == null || addresses.isEmpty()) {
                throw new UnknownHostException(host);
            }
            for (InetSocketAddress address : addresses) {
                if (address.isUnresolved()) {
                    throw new UnknownHostException(host);
                }
                check(host, address.getAddress());
            }
            return addresses;
        }

        private void check(String host, InetAddress address) throws NotPublicException {
            if (!allowed.test(address)) {
                throw new NotPublicException(host);
            }
        }

        @Override
        public String resolveCanonicalHostname(String host) throws UnknownHostException {
            return delegate.resolveCanonicalHostname(host);
        }
    }

    /** A host refused by the policy. An UnknownHostException, so the client unwinds as for a lookup failure. */
    static final class NotPublicException extends UnknownHostException {
        private static final long serialVersionUID = 1L;

        NotPublicException(String host) {
            super(host);
        }
    }
}
