/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  The ASF licenses this file to You
 * under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.  For additional information regarding
 * copyright in this work, please see the NOTICE file in the top level
 * directory of this distribution.
 */
package com.wikantik.pressroom.business.comments;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Optional;
import java.util.function.Supplier;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.business.Weblogger;
import com.wikantik.pressroom.config.GeneratedSecrets;
import com.wikantik.pressroom.pojos.RuntimeConfigProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

/**
 * The token in a one-click unsubscribe link: base64url of
 * {@code userId|purpose|signature}, the signature being the base64url
 * HMAC-SHA256 of {@code userId|purpose} under the site's generated
 * {@link GeneratedSecrets#MAIL_UNSUBSCRIBE_SECRET}.
 *
 * <p>It does not expire and is the same in every mail to one user (spec,
 * Part F): a leaked link can only turn that user's emails of that purpose
 * off, which they can turn back on from the account page. The key is read at
 * each use, so the bean can be built before the tier is up; with no key
 * nothing is issued and nothing verifies.
 */
@Component
public class UnsubscribeTokens {

    /** The purpose of the links in reply-notification mail. */
    public static final String REPLIES = "replies";

    private static final Logger log = LoggerFactory.getLogger("pressroom.comments");

    private static final String ALGORITHM = "HmacSHA256";
    private static final char SEPARATOR = '|';
    private static final int PARTS = 3;

    private final Supplier<String> key;

    /** Signs with the secret the tier keeps in {@code pressroom_properties}. */
    @Autowired
    public UnsubscribeTokens(@Lazy Weblogger weblogger) {
        this(() -> storedKey(weblogger));
    }

    /** Signs with whatever {@code key} answers at each use. */
    UnsubscribeTokens(Supplier<String> key) {
        this.key = key;
    }

    /**
     * The token for this user and purpose.
     *
     * @throws IllegalArgumentException for a blank id or purpose, or one holding the separator
     * @throws IllegalStateException when there is no key to sign with
     */
    public String issue(String userId, String purpose) {
        if (!usable(userId) || !usable(purpose)) {
            throw new IllegalArgumentException("an unsubscribe token needs a plain user id and purpose");
        }
        String payload = userId + SEPARATOR + purpose;
        return encode(payload + SEPARATOR + sign(payload, requireKey()));
    }

    /**
     * The user id the token was issued to, when it is well formed, for this
     * purpose, and signed with the current key; empty otherwise, never an
     * exception. The signatures are compared in constant time.
     */
    public Optional<String> verify(String token, String purpose) {
        String secret = key.get();
        if (token == null || token.isBlank() || secret == null || secret.isBlank()) {
            return Optional.empty();
        }
        String decoded;
        try {
            decoded = new String(Base64.getUrlDecoder().decode(token.trim()), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException malformed) {
            return Optional.empty();
        }
        String[] parts = decoded.split("\\|", -1);
        if (parts.length != PARTS || parts[0].isEmpty() || !parts[1].equals(purpose)) {
            return Optional.empty();
        }
        String expected = sign(parts[0] + SEPARATOR + parts[1], secret);
        boolean signed = MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                parts[2].getBytes(StandardCharsets.UTF_8));
        return signed ? Optional.of(parts[0]) : Optional.empty();
    }

    private String requireKey() {
        String secret = key.get();
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException(GeneratedSecrets.MAIL_UNSUBSCRIBE_SECRET + " is not set");
        }
        return secret;
    }

    private static boolean usable(String part) {
        return part != null && !part.isBlank() && part.indexOf(SEPARATOR) < 0;
    }

    private static String sign(String payload, String secret) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), ALGORITHM));
            byte[] signature = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(signature);
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("JVM lacks " + ALGORITHM, ex);
        }
    }

    private static String encode(String raw) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * The stored secret, or null when it cannot be read. Read through the
     * properties manager, not {@code WebloggerRuntimeConfig.getProperty}, whose
     * debug line prints every value it fetches.
     */
    private static String storedKey(Weblogger weblogger) {
        try {
            RuntimeConfigProperty row = weblogger.getPropertiesManager()
                    .getProperty(GeneratedSecrets.MAIL_UNSUBSCRIBE_SECRET);
            return row == null ? null : row.getValue();
        } catch (WebloggerException ex) {
            log.error("Could not read the unsubscribe secret: {}", ex.getClass().getSimpleName());
            return null;
        }
    }
}
