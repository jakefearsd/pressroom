package org.apache.roller.weblogger.business.fetch;

/** A fetch that was refused or failed; the message is the detail the API shows the caller. */
public final class FetchRefusal extends Exception {
    private static final long serialVersionUID = 1L;

    public FetchRefusal(String detail) {
        super(detail);
    }

    public FetchRefusal(String detail, Throwable cause) {
        super(detail, cause);
    }
}
