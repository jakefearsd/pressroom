package com.wikantik.pressroom.ui.controllers.core;

import jakarta.mail.MessagingException;
import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.business.Weblogger;
import com.wikantik.pressroom.pojos.User;
import com.wikantik.pressroom.pojos.UserToken;

/**
 * The admin's "email this person a set-password link" action, in one place for
 * the screen ({@code UserEditController}) and the API ({@code AdminApi}): who
 * may be sent one, and issuing the token and mailing it. The caller words a
 * refusal and decides how a failed send reads; nothing here touches a model or
 * a response.
 */
public final class PasswordLinkService {

    /** The subject the API sends; the screen sends its localised one. */
    public static final String DEFAULT_SUBJECT = "Set your Pressroom password";

    /** Why no link may be sent to an account, in the order the screens judge them. */
    public enum Refusal {
        /** An agent has no password. */
        NOT_A_PERSON,
        /** A single-sign-on account signs in through its provider. */
        SSO_BOUND,
        /** No transport, or no site address to send from. */
        MAIL_NOT_CONFIGURED
    }

    private PasswordLinkService() {
    }

    /** What stops a link being sent to {@code user}, or null when one may be. */
    public static Refusal refusal(User user) {
        if (user.isAgent()) {
            return Refusal.NOT_A_PERSON;
        }
        if (user.isSsoBound()) {
            return Refusal.SSO_BOUND;
        }
        return PasswordLinkMailer.isReady() ? null : Refusal.MAIL_NOT_CONFIGURED;
    }

    /**
     * Issues a {@code PASSWORD_SET} token for {@code user} and mails its link.
     * The token is committed before the mail goes, so a failed send leaves an
     * unused token that expires on its own.
     */
    public static void send(Weblogger weblogger, User user, String subject)
            throws WebloggerException, MessagingException {
        String raw = weblogger.getUserTokenManager().issueToken(user, UserToken.Purpose.PASSWORD_SET);
        weblogger.flush();
        PasswordLinkMailer.sendLink(user, raw, subject);
    }
}
