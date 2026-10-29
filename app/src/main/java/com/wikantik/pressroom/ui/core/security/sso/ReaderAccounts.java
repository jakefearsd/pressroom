package com.wikantik.pressroom.ui.core.security.sso;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import com.wikantik.pressroom.business.UserManager;
import com.wikantik.pressroom.business.Weblogger;
import com.wikantik.pressroom.business.comments.DisplayNames;
import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.pojos.User;
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
    private static final String FALLBACK_NAME = "Reader";

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

    private SignIn existingAccount(UserManager users, User account, ReaderClaims claims)
            throws ReaderSignInRefused, WebloggerException {
        if (account.isAgent()) {
            // An agent never signs in, through a provider or otherwise
            // (spec 2026-10-13, section 2).
            throw new ReaderSignInRefused("agent account");
        }
        if (!Boolean.TRUE.equals(account.getEnabled())) {
            throw new ReaderSignInRefused("account disabled");
        }
        // The provider owns the email, so it is refreshed on every sign-in; the display name is the
        // reader's to edit and is set once at creation, never overwritten here.
        if (!claims.email().equals(account.getEmailAddress())) {
            account.setEmailAddress(claims.email());
            users.saveUser(account);
            weblogger.flush();
        }
        return new SignIn(account, users.getRoles(account), false);
    }

    @SuppressFBWarnings(
            value = "HARD_CODE_PASSWORD",
            justification = "SSO_PASSWORD_MARKER is a marker in the password-hash column, not a password: "
                    + "a bcrypt-prefixed value no input encodes to, so a reader account cannot sign in "
                    + "with a password at all (ReaderAccountStoreTest pins that).")
    private User newReader(UserManager users, ReaderClaims claims) throws WebloggerException {
        String userName = null;
        // 5 draws is a bound, not an expectation: with 36^10 names a second draw is already a
        // one-in-a-trillion event, so repeated misses mean the random source or the store is broken
        // and failing the sign-in is right. The existence check is a courtesy; the unique index on
        // username (ru_username_uq) decides a true race.
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
        String name = displayName(users, claims, userName);
        User user = new User();
        user.setUserName(userName);
        user.setScreenName(name);
        user.setFullName(name);
        user.setEmailAddress(claims.email());
        user.setPassword(User.SSO_PASSWORD_MARKER);
        user.setDateCreated(new Date());
        user.setEnabled(Boolean.TRUE);
        user.setSsoIssuer(claims.issuer());
        user.setSsoSubject(claims.subject());
        return user;
    }

    /**
     * The new reader's display name, by the account page's rule
     * ({@link DisplayNames}): the provider's name claim, cut to the limit; if
     * it is refused (a weblog member's name, or nothing once stored), the
     * email's local part; then {@value #FALLBACK_NAME}; and if even that is a
     * member's name, the generated username, which no one else holds.
     */
    private static String displayName(UserManager users, ReaderClaims claims, String userName)
            throws WebloggerException {
        String email = claims.email();
        List<String> candidates = List.of(claims.displayName(),
                email.substring(0, Math.max(0, email.lastIndexOf('@'))), FALLBACK_NAME);
        for (String candidate : candidates) {
            String name = DisplayNames.cut(DisplayNames.storedForm(candidate));
            if (DisplayNames.refusal(name, null, users) == null) {
                return name;
            }
        }
        return userName;
    }
}
