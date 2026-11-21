package com.wikantik.pressroom.ui.restapi;

/** The limit/offset guard every list endpoint of {@code /api/v1} shares. */
public final class Paging {

    /** The text openapi.json gives each member of the one page shape ({@code EntryPage} and the rest). */
    public static final String ITEMS_DESCRIPTION = "This page of the list.";
    public static final String OFFSET_DESCRIPTION = "How many items before this page were skipped: the offset asked "
            + "for.";
    public static final String LIMIT_DESCRIPTION = "The page size asked for, after the cap of 200.";
    public static final String HAS_MORE_DESCRIPTION = "True while items remain beyond this page: page until it is "
            + "false, not until a short page arrives.";

    /** The largest page any list route answers; a bigger {@code limit} is capped to it. */
    public static final int MAX_LIMIT = 200;

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
