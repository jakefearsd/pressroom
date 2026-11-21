package com.wikantik.pressroom.business;

import java.util.List;
import com.wikantik.pressroom.WebloggerException;

/**
 * A redirect rule {@link WeblogRedirectManager#saveRedirect} refuses, for a
 * reason a caller can act on: the path's shape, or a rule it would duplicate
 * or chain onto. The message is the English it always was; {@link #reason()}
 * and {@link #args()} are what the API keys it by (spec
 * 2026-11-20-api-contract-design, Task 3), the args the values the message
 * names, in its order.
 */
public class RedirectRefusedException extends WebloggerException {

    private static final long serialVersionUID = 1L;

    /** Why the rule was refused: one meaning each. */
    public enum Reason {
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
