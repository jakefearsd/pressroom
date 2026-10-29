package com.wikantik.pressroom.ui.restapi.mcp;

import java.io.ByteArrayOutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletResponse;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

/**
 * Catches everything the API writes for a forwarded tool call -- status,
 * headers, body, and a sendError or redirect as a plain status -- so the
 * caller can read it back as an {@link ApiReply}. It never reports itself
 * committed, which is what lets the container forward to it.
 *
 * <p>A response of its own, deliberately NOT a wrapper around the MCP
 * response. Tomcat 11 finishes (suspends) the container's response after a
 * forward whose response wraps it ({@code suspendWrappedResponseAfterForward},
 * on by default), so a wrapper would swallow the JSON-RPC answer the SDK
 * transport writes after the tool returns: a 200 with an empty body. Holding
 * no reference to the MCP response also means no method left to a wrapper's
 * delegation (a redirect overload, a trailer, a charset) can reach it.
 * {@code InProcessDispatchContainerTest} pins this in a real Tomcat.
 */
final class CapturedResponse implements HttpServletResponse {

    private static final int BUFFER_SIZE = 8192;

    private final ByteArrayOutputStream body = new ByteArrayOutputStream();
    private final Map<String, List<String>> headers = new LinkedHashMap<>();
    private int status = SC_OK;
    private String contentType;
    private Locale locale = Locale.ROOT;
    private PrintWriter writer;
    private ServletOutputStream stream;

    ApiReply reply() {
        flushBuffer();
        return new ApiReply(status, contentType, body.toString(StandardCharsets.UTF_8));
    }

    @Override
    public void setStatus(int sc) {
        status = sc;
    }

    @Override
    public int getStatus() {
        return status;
    }

    /** As the container does, discards what was written before it; renders no error page after it. */
    @Override
    public void sendError(int sc) {
        resetBuffer();
        status = sc;
    }

    @Override
    public void sendError(int sc, String msg) {
        sendError(sc);
    }

    @Override
    public void sendRedirect(String location, int sc, boolean clearBuffer) {
        if (clearBuffer) {
            resetBuffer();
        }
        status = sc;
        setHeader("Location", location);
    }

    @Override
    public String encodeURL(String url) {
        return url;
    }

    @Override
    public String encodeRedirectURL(String url) {
        return url;
    }

    @Override
    public void setContentType(String type) {
        contentType = type;
    }

    @Override
    public String getContentType() {
        return contentType;
    }

    @Override
    public void setCharacterEncoding(String charset) {
        // always UTF-8: the body is read back as UTF-8
    }

    @Override
    public String getCharacterEncoding() {
        return StandardCharsets.UTF_8.name();
    }

    @Override
    public void setContentLength(int len) {
        // computed from the captured body
    }

    @Override
    public void setContentLengthLong(long len) {
        // computed from the captured body
    }

    @Override
    @SuppressFBWarnings(
            value = "IMPROPER_UNICODE",
            justification = "Header names here are set by Roller's and Spring's own code while an MCP tool "
                    + "call is dispatched internally, never read from a request; the fold matches them as "
                    + "HTTP does.")
    public void setHeader(String name, String value) {
        if (value == null) {
            headers.remove(name.toLowerCase(Locale.ROOT));
            if ("content-type".equalsIgnoreCase(name)) {
                contentType = null;
            }
            return;
        }
        List<String> values = new ArrayList<>();
        values.add(value);
        headers.put(name.toLowerCase(Locale.ROOT), values);
        if ("content-type".equalsIgnoreCase(name)) {
            contentType = value;
        }
    }

    @Override
    @SuppressFBWarnings(
            value = "IMPROPER_UNICODE",
            justification = "Header names here are set by Roller's and Spring's own code while an MCP tool "
                    + "call is dispatched internally, never read from a request; the fold matches them as "
                    + "HTTP does.")
    public void addHeader(String name, String value) {
        if (value == null) {
            return;
        }
        headers.computeIfAbsent(name.toLowerCase(Locale.ROOT), k -> new ArrayList<>()).add(value);
        if ("content-type".equalsIgnoreCase(name)) {
            contentType = value;
        }
    }

    @Override
    public void setIntHeader(String name, int value) {
        setHeader(name, Integer.toString(value));
    }

    @Override
    public void addIntHeader(String name, int value) {
        addHeader(name, Integer.toString(value));
    }

    @Override
    public void setDateHeader(String name, long date) {
        setHeader(name, Long.toString(date));
    }

    @Override
    public void addDateHeader(String name, long date) {
        addHeader(name, Long.toString(date));
    }

    @Override
    public boolean containsHeader(String name) {
        return headers.containsKey(name.toLowerCase(Locale.ROOT));
    }

    @Override
    public String getHeader(String name) {
        List<String> values = headers.get(name.toLowerCase(Locale.ROOT));
        return values == null || values.isEmpty() ? null : values.get(0);
    }

    @Override
    public Collection<String> getHeaders(String name) {
        return List.copyOf(headers.getOrDefault(name.toLowerCase(Locale.ROOT), List.of()));
    }

    @Override
    public Collection<String> getHeaderNames() {
        return List.copyOf(headers.keySet());
    }

    @Override
    public void addCookie(Cookie cookie) {
        // a dispatched API call never sets a cookie on the MCP response
    }

    @Override
    public ServletOutputStream getOutputStream() {
        if (stream == null) {
            stream = new ServletOutputStream() {
                @Override
                public void write(int b) {
                    body.write(b);
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setWriteListener(WriteListener listener) {
                    throw new UnsupportedOperationException("A dispatched API call is written synchronously.");
                }
            };
        }
        return stream;
    }

    @Override
    public PrintWriter getWriter() {
        if (writer == null) {
            writer = new PrintWriter(new OutputStreamWriter(body, StandardCharsets.UTF_8));
        }
        return writer;
    }

    @Override
    public void flushBuffer() {
        if (writer != null) {
            writer.flush();
        }
    }

    @Override
    public boolean isCommitted() {
        return false;
    }

    @Override
    public void reset() {
        resetBuffer();
        headers.clear();
        status = SC_OK;
        contentType = null;
    }

    @Override
    public void resetBuffer() {
        flushBuffer();
        body.reset();
    }

    @Override
    public void setBufferSize(int size) {
        // unbounded: the whole body is captured
    }

    @Override
    public int getBufferSize() {
        return BUFFER_SIZE;
    }

    @Override
    public void setLocale(Locale loc) {
        locale = loc;
    }

    @Override
    public Locale getLocale() {
        return locale;
    }
}
