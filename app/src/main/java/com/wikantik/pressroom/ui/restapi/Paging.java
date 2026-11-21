package com.wikantik.pressroom.ui.restapi;

/** The limit/offset guard every list endpoint of {@code /api/v1} shares. */
public final class Paging {

    private Paging() {
    }

    /**
     * Refuses (400) a page request that would reach a query as an exception
     * or as "no limit", naming the field that is wrong: limit first when both are.
     */
    public static void requireRange(int limit, int offset) {
        if (limit < 1) {
            throw ApiException.problemOnField("limit", ProblemCode.PAGING_INVALID_RANGE);
        }
        if (offset < 0) {
            throw ApiException.problemOnField("offset", ProblemCode.PAGING_INVALID_RANGE);
        }
    }
}
