package com.wikantik.pressroom.ui.core.security.sso;

import java.util.ArrayList;
import java.util.List;

/**
 * An exception's cause chain, for {@code roller.sso} log lines that name what
 * went wrong by class alone. Never a message: a message can carry an email, a
 * provider's error text or configuration.
 */
final class CauseChain {

    /** The most causes followed below the top exception. */
    static final int MAX_CAUSES = 8;

    private CauseChain() {
    }

    /** @return the causes below {@code top}, ending at a repeat or after {@value #MAX_CAUSES} */
    static List<Throwable> below(Throwable top) {
        List<Throwable> chain = new ArrayList<>();
        chain.add(top);
        Throwable cause = top.getCause();
        while (cause != null && !chain.contains(cause) && chain.size() <= MAX_CAUSES) {
            chain.add(cause);
            cause = cause.getCause();
        }
        return chain.subList(1, chain.size());
    }

    /** @return {@code "<-Cause<-RootCause"}: the simple class names below {@code top}, or {@code ""} */
    static String causeNames(Throwable top) {
        StringBuilder names = new StringBuilder();
        for (Throwable cause : below(top)) {
            names.append("<-").append(cause.getClass().getSimpleName());
        }
        return names.toString();
    }
}
