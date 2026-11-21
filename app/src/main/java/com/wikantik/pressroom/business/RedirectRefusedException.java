package com.wikantik.pressroom.business;

import java.util.List;
import com.wikantik.pressroom.WebloggerException;

/**
 * A redirect rule {@link WeblogRedirectManager#saveRedirect} refuses, for a
 * reason a caller can act on: a missing weblog or path, the path's shape, or
 * a rule it would duplicate or chain onto. Every refusal of the save is one
 * of these; any other exception from it is a failure (the API's opaque 500). The message is the English it always was; {@link #reason()}
 * and {@link #args()} are what the API keys it by (spec
 * 2026-11-20-api-contract-design, Task 3), the args the values the message
 * names, in its order.
 */
public class RedirectRefusedException extends WebloggerException {

    private static final long serialVersionUID = 1L;

    /** Why the rule was refused: one meaning each. */
    public enum Reason {
        /** The rule names no weblog (a caller's mistake: the API always sets one). */
        WEBLOG_REQUIRED,
        /** A path is blank after normalising; the arg is which ({@code source} or {@code target}). */
        PATH_REQUIRED,
        PATH_NOT_RELATIVE, PATH_HAS_QUERY, PATH_HAS_BACKSLASH, PATH_HAS_CONTROL_CHARACTERS,
        TARGET_IS_SOURCE, SOURCE_EXISTS, SOURCE_IS_TARGET, TARGET_REDIRECTED
    }

    private final Reason reason;
    private final String[] args;

    public RedirectRefusedException(String message, Reason reason, String... args) {
        super(message);
        this.reason = reason;
        this.args = args.clone();
    }

    public Reason reason() {
        return reason;
    }

    public List<String> args() {
        return List.of(args);
    }
}
