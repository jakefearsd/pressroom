package org.apache.roller.weblogger.ui.core.security.sso;

import org.apache.roller.weblogger.business.UserManager;
import org.apache.roller.weblogger.business.Weblogger;
import org.apache.roller.weblogger.WebloggerException;
import org.apache.roller.weblogger.pojos.User;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.Date;
import java.util.List;
import java.util.random.RandomGenerator;

/** Finds or creates the reader account bound to a provider identity. */
@Component
public class ReaderAccounts {

    private static final String ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789";
    private static final String PREFIX = "reader-";
    private static final int SUFFIX_LENGTH = 10;
    private static final int MAX_DRAWS = 5;

    private final Weblogger weblogger;
    private final RandomGenerator random;

    /** @param weblogger the lazily-resolved business tier */
    @Autowired
    public ReaderAccounts(@Lazy Weblogger weblogger) {
        this(weblogger, new SecureRandom());
    }

    ReaderAccounts(Weblogger weblogger, RandomGenerator random) {
        this.weblogger = weblogger;
        this.random = random;
    }

    /** The outcome of a sign-in. */
    public record SignIn(User user, List<String> roles, boolean created) { }

    /**
     * Signs the identity in, creating a reader on first sight.
     *
     * @throws ReaderSignInRefused when the claims are unusable or the account is disabled
     * @throws WebloggerException when the account store fails
     */
    public SignIn signIn(ReaderClaims claims) throws ReaderSignInRefused, WebloggerException {
        claims.requireUsable();
        UserManager users = weblogger.getUserManager();
        User existing = users.getUserBySsoIdentity(claims.issuer(), claims.subject());
        if (existing != null) {
            return existingAccount(users, existing, claims);
        }
        User created = newReader(users, claims);
        try {
            users.addReader(created);
            weblogger.flush();
            return new SignIn(created, List.of("reader"), true);
        } catch (WebloggerException race) {
            // The unique (sso_issuer, sso_subject) index decides a concurrent first
            // sign-in: the loser's flush fails, and the winner's row is the answer.
            weblogger.release();
            User winner = weblogger.getUserManager().getUserBySsoIdentity(claims.issuer(), claims.subject());
            if (winner == null) {
                throw race;
            }
            return existingAccount(weblogger.getUserManager(), winner, claims);
        }
    }

    @SuppressWarnings("deprecation") // UserManager.getRoles is the only role reader
    private SignIn existingAccount(UserManager users, User account, ReaderClaims claims)
            throws ReaderSignInRefused, WebloggerException {
        if (!Boolean.TRUE.equals(account.getEnabled())) {
            throw new ReaderSignInRefused("account disabled");
        }
        if (!claims.email().equals(account.getEmailAddress())) {
            account.setEmailAddress(claims.email());
            users.saveUser(account);
            weblogger.flush();
        }
        return new SignIn(account, users.getRoles(account), false);
    }

    private User newReader(UserManager users, ReaderClaims claims) throws WebloggerException {
        String userName = null;
        for (int draw = 0; draw < MAX_DRAWS && userName == null; draw++) {
            StringBuilder candidate = new StringBuilder(PREFIX);
            for (int i = 0; i < SUFFIX_LENGTH; i++) {
                candidate.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
            }
            if (users.getUserByUserName(candidate.toString(), null) == null) {
                userName = candidate.toString();
            }
        }
        if (userName == null) {
            throw new WebloggerException("no free reader username after " + MAX_DRAWS + " draws");
        }
        User user = new User();
        user.setUserName(userName);
        user.setScreenName(claims.displayName());
        user.setFullName(claims.displayName());
        user.setEmailAddress(claims.email());
        user.setPassword(User.SSO_PASSWORD_MARKER);
        user.setDateCreated(new Date());
        user.setEnabled(Boolean.TRUE);
        user.setSsoIssuer(claims.issuer());
        user.setSsoSubject(claims.subject());
        return user;
    }
}
