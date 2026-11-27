package com.wikantik.pressroom.ui;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The sign-in page's source as one SSO state renders it, for the tests that
 * cannot run Jasper (see {@code ErrorPageContentTest}'s class comment for why
 * no unit test compiles a JSP): JSP comments go, and each
 * {@code <c:if test="${ssoEnabled}">} or {@code <c:if test="${not ssoEnabled}">}
 * block is kept or dropped as that state would. Other {@code c:if} blocks are
 * left in place, so their markup still counts.
 */
final class LoginJsp {

    static final Path LOGIN = Path.of("src/main/webapp/WEB-INF/jsps/core/Login.jsp");

    private static final String ON = "<c:if test=\"${ssoEnabled}\">";
    private static final String OFF = "<c:if test=\"${not ssoEnabled}\">";
    private static final String OPEN = "<c:if ";
    private static final String CLOSE = "</c:if>";

    /** An element, or an attribute a test pins: id, name, for, message code, getElementById. */
    private static final Pattern TOKEN = Pattern.compile(
            "<(form|input|button|a|h2|details|summary|label)\\b"
            + "|\\b(id|name|for|code)=[\"']([^\"']+)[\"']"
            + "|getElementById\\([\"']([^\"']+)[\"']\\)");

    private LoginJsp() {
    }

    static String source() {
        try {
            return Files.readString(LOGIN, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The page as rendered with reader sign-in on or off. */
    static String rendered(boolean ssoEnabled) {
        return evaluate(source().replaceAll("(?s)<%--.*?--%>", ""), ssoEnabled);
    }

    private static String evaluate(String text, boolean ssoEnabled) {
        StringBuilder out = new StringBuilder();
        int at = 0;
        while (true) {
            int on = text.indexOf(ON, at);
            int off = text.indexOf(OFF, at);
            int next = on < 0 ? off : off < 0 ? on : Math.min(on, off);
            if (next < 0) {
                out.append(text, at, text.length());
                return out.toString();
            }
            boolean keep = (next == on) == ssoEnabled;
            String tag = next == on ? ON : OFF;
            int bodyStart = next + tag.length();
            int end = matchingClose(text, bodyStart);
            out.append(text, at, next);
            if (keep) {
                out.append(evaluate(text.substring(bodyStart, end), ssoEnabled));
            }
            at = end + CLOSE.length();
        }
    }

    private static int matchingClose(String text, int from) {
        int depth = 1;
        int at = from;
        while (true) {
            int open = text.indexOf(OPEN, at);
            int close = text.indexOf(CLOSE, at);
            if (close < 0) {
                throw new IllegalStateException("unclosed c:if in " + LOGIN);
            }
            if (open >= 0 && open < close) {
                depth++;
                at = open + OPEN.length();
            } else {
                depth--;
                if (depth == 0) {
                    return close;
                }
                at = close + CLOSE.length();
            }
        }
    }

    /** The elements and pinned attributes in document order, e.g. {@code <form}, {@code id=loginForm}. */
    static List<String> tokens(String page) {
        List<String> tokens = new ArrayList<>();
        Matcher m = TOKEN.matcher(page);
        while (m.find()) {
            if (m.group(1) != null) {
                tokens.add("<" + m.group(1));
            } else if (m.group(2) != null) {
                tokens.add(m.group(2) + "=" + m.group(3));
            } else {
                tokens.add("getElementById=" + m.group(4));
            }
        }
        return tokens;
    }
}
