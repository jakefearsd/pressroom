package org.apache.roller.weblogger.ui.restapi;

/** The limit/offset guard every list endpoint of {@code /api/v1} shares. */
public final class Paging {

    private Paging() {
    }

    /**
     * Refuses (400) a page request that would reach a query as an exception
     * or as "no limit", naming the field that is wrong: limit first when both are.
     */
    public static void requireRange(int limit, int offset) {
        String message = "limit must be at least 1 and offset must not be negative.";
        if (limit < 1) {
            throw ApiException.invalidField("limit", message);
        }
        if (offset < 0) {
            throw ApiException.invalidField("offset", message);
        }
    }
}
