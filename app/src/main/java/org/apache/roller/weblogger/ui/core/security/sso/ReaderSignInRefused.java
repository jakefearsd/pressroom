package org.apache.roller.weblogger.ui.core.security.sso;

/** A provider sign-in that Roller declines: unusable claims or a disabled account. */
public class ReaderSignInRefused extends Exception {

    private static final long serialVersionUID = 1L;

    private final String reason;

    /** @param reason a short code or phrase; never carries an email address */
    public ReaderSignInRefused(String reason) {
        super(reason);
        this.reason = reason;
    }

    /** @return why the sign-in was refused */
    public String reason() {
        return reason;
    }
}
