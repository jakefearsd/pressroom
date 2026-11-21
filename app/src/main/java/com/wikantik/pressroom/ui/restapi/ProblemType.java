package com.wikantik.pressroom.ui.restapi;

/**
 * A problem's coarse category: its status, its RFC 9457 {@code type} URI and
 * its {@code title}. A {@link ProblemCode} refines one of these; several codes
 * share a type. {@code title} is not localised (spec 2026-11-20-api-contract-design,
 * decision 3): clients may already match on it.
 */
public enum ProblemType {

    INVALID_REQUEST(400, "invalid-request", "Invalid request"),
    UNAUTHORIZED(401, "unauthorized", "Unauthorized"),
    FORBIDDEN(403, "forbidden", "Forbidden"),
    NOT_FOUND(404, "not-found", "Not found"),
    NOT_ACCEPTABLE(406, "not-acceptable", "Not acceptable"),
    CONFLICT(409, "conflict", "Conflict"),
    ENTRY_CHANGED(409, "entry-changed", "Entry changed"),
    PAGE_CHANGED(409, "page-changed", "Page changed"),
    TOO_LARGE(413, "too-large", "Content too large"),
    UNSUPPORTED_MEDIA_TYPE(415, "unsupported-media-type", "Unsupported media type"),
    THROTTLED(429, "throttled", "Too many requests"),
    INTERNAL_ERROR(500, "internal-error", "Internal error"),
    UPSTREAM_FAILURE(502, "upstream-failure", "Upstream failure"),
    BUSY(503, "busy", "Busy");

    private static final String TYPE_BASE = "https://pressroom.invalid/problems/";

    private final int status;
    private final String uri;
    private final String title;

    ProblemType(int status, String slug, String title) {
        this.status = status;
        this.uri = TYPE_BASE + slug;
        this.title = title;
    }

    public int status() {
        return status;
    }

    /** The problem's {@code type} member. */
    public String uri() {
        return uri;
    }

    public String title() {
        return title;
    }
}
