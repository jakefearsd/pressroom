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

package org.apache.roller.weblogger.pojos;

import java.io.Serializable;
import java.util.Date;
import org.apache.commons.lang3.builder.EqualsBuilder;
import org.apache.commons.lang3.builder.HashCodeBuilder;
import org.apache.roller.weblogger.ui.core.RollerContext;
import org.apache.roller.weblogger.util.HTMLSanitizer;
import org.springframework.security.crypto.password.PasswordEncoder;


/**
 * User bean.
 */
public class User implements Serializable {
    
    private static final long serialVersionUID = -6354583200913127874L;
    
    private String  id = EntityIds.newId();
    private String  userName;
    private String  password;
    private String  screenName;
    private String  fullName;
    private String  emailAddress;
    private String  ssoIssuer;
    private String  ssoSubject;
    private Date    dateCreated;
    private String  locale;
    private String  timeZone;
    private Boolean enabled = Boolean.TRUE;
    private Kind    kind = Kind.PERSON;
    private String  notes;
    private Date    lastLogin;

    public User() {
    }
    
    public User( String id, String userName,
            String password, String fullName,
            String emailAddress,
            String locale, String timeZone,
            Date dateCreated,
            Boolean isEnabled) {

        this.userName = userName;
        this.password = password;
        this.emailAddress = emailAddress;
        this.dateCreated = (Date)dateCreated.clone();
        this.enabled = isEnabled;
        setFullName(fullName);
        setLocale(locale);
        setTimeZone(timeZone);
    }

    /**
     * Id of the User.
     */
    public String getId() {
        return this.id;
    }
    
    public void setId( String id ) {
        this.id = id;
    }
    
    
    /**
     * User name of the user.
     */
    public String getUserName() {
        return this.userName;
    }
    
    public void setUserName( String userName ) {
        this.userName =  HTMLSanitizer.conditionallySanitizeText(userName);
    }
    
    /**
     * Get password.
     * If password encryption is enabled, will return encrypted password.
     */
    public String getPassword() {
        return this.password;
    }
    
    /** Passphrase stored on an SSO-bound account; matches no password the encoder can produce. */
    public static final String SSO_PASSWORD_MARKER = "{bcrypt}!sso-bound";

    /**
     * What an account is (V033). A person signs in; an agent never does -- it
     * has no password and works only through API tokens a site admin mints
     * for it (spec 2026-10-13, Decisions).
     */
    public enum Kind { PERSON, AGENT }

    /** The domain of the address an agent created with no email is given; never deliverable (RFC 2606). */
    public static final String AGENT_EMAIL_DOMAIN = "agents.invalid";

    /** roller_user.notes is varchar(1000). */
    public static final int NOTES_MAX_LENGTH = 1000;

    /** True when this account signs in through an OpenID Connect identity rather than a password. */
    public boolean isSsoBound() {
        return ssoSubject != null;
    }

    public String getSsoIssuer() {
        return ssoIssuer;
    }

    public void setSsoIssuer(String ssoIssuer) {
        this.ssoIssuer = ssoIssuer;
    }

    public String getSsoSubject() {
        return ssoSubject;
    }

    public void setSsoSubject(String ssoSubject) {
        this.ssoSubject = ssoSubject;
    }

    /**
     * Set password.
     * If password encryption is turned on, then pass in an encrypted password.
     */
    public void setPassword( String password ) {
        this.password = password;
    }
    
    /**
     * Reset this user's password, handles encryption if configured.
     *
     * @param newPassword The new password to be set.
     */
    public void resetPassword(String newPassword) {
        if (isAgent()) {
            throw new IllegalStateException("An agent account has no password.");
        }
        if (isSsoBound()) {
            throw new IllegalStateException("An account that signs in with single sign-on has no password.");
        }
        PasswordEncoder encoder = RollerContext.getPasswordEncoder();
        setPassword(encoder.encode(newPassword));
    }

    /**
     * Screen name of the user.
     */
    public String getScreenName() {
        return this.screenName;
    }
    
    public void setScreenName( String screenName ) {
        this.screenName =  HTMLSanitizer.conditionallySanitizeText(screenName);
    }
    
    /**
     * Full name of the user.
     */
    public String getFullName() {
        return this.fullName;
    }
    
    public void setFullName( String fullName ) {
        this.fullName =  HTMLSanitizer.conditionallySanitizeText(fullName);
    }

    /**
     * E-mail address of the user.
     */
    public String getEmailAddress() {
        return this.emailAddress;
    }
    
    public void setEmailAddress( String emailAddress ) {
        this.emailAddress =  HTMLSanitizer.conditionallySanitizeText(emailAddress);
    }
    
    
    /**
     * The date the user was created.
     */
    public Date getDateCreated() {
        if (dateCreated == null) {
            return null;
        } else {
            return (Date)dateCreated.clone();
        }
    }
    
    public void setDateCreated(final Date date) {
        if (date != null) {
            dateCreated = (Date)date.clone();
        } else {
            dateCreated = null;
        }
    }

    /**
     * Locale of the user, must be valid Java locale.
     */
    public String getLocale() {
        return this.locale;
    }
    
    public void setLocale(String locale) {
        this.locale = locale;
    }

    /**
     * Timezone of the user, must be valid Java timezone.
     */
    public String getTimeZone() {
        return this.timeZone;
    }
    
    public void setTimeZone(String timeZone) {
        this.timeZone = timeZone;
    }
    
    
    /**
     * Is this user account enabled?  Disabled accounts cannot login.
     */
    public Boolean getEnabled() {
        return this.enabled;
    }
    
    public void setEnabled(Boolean enabled) {
        this.enabled = enabled;
    }
    
    /** Whether this account is a person or an agent; never null. */
    public Kind getKind() {
        return kind;
    }

    public void setKind(Kind kind) {
        this.kind = kind;
    }

    /** True for an agent account: no password, no sign-in, API tokens only. */
    public boolean isAgent() {
        return kind == Kind.AGENT;
    }

    /** An agent's purpose, shown only in the admin UI; null for a person. */
    public String getNotes() {
        return notes;
    }

    public void setNotes(String notes) {
        this.notes = notes;
    }

    /** The last successful interactive sign-in (form, remember-me or SSO), or null. */
    public Date getLastLogin() {
        return lastLogin == null ? null : (Date) lastLogin.clone();
    }

    public void setLastLogin(Date lastLogin) {
        this.lastLogin = lastLogin == null ? null : (Date) lastLogin.clone();
    }

    //------------------------------------------------------- Good citizenship
    
    @Override
    public String toString() {
        StringBuilder buf = new StringBuilder();
        buf.append("{");
        buf.append(getId());
        buf.append(", ").append(getUserName());
        buf.append(", ").append(getFullName());
        buf.append(", ").append(getEmailAddress());
        buf.append(", ").append(getDateCreated());
        buf.append(", ").append(getEnabled());
        buf.append("}");
        return buf.toString();
    }
    
    @Override
    public boolean equals(Object other) {
        if (other == this) {
            return true;
        }
        if (!(other instanceof User)) {
            return false;
        }
        User o = (User)other;
        return new EqualsBuilder().append(getUserName(), o.getUserName()).isEquals();
    }
    
    @Override
    public int hashCode() {
        return new HashCodeBuilder().append(getUserName()).toHashCode();
    }

}
