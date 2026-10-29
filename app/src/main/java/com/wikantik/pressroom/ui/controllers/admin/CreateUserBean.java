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

package com.wikantik.pressroom.ui.controllers.admin;

import java.util.ArrayList;
import java.util.List;

import org.apache.commons.lang3.StringUtils;
import com.wikantik.pressroom.pojos.User;


/**
 * Bean used by CreateUser action.
 */
public class CreateUserBean {

    private String id = null;
    private String userName = null;
    private String password = null;
    private String screenName = null;
    private String fullName = null;
    private String emailAddress = null;
    private String locale = null;
    private String timeZone = null;
    private Boolean enabled = Boolean.FALSE;
    private boolean administrator = false;
    /** The editor role, offered only for an SSO-bound account; set by the controller, never by copyFrom. */
    private boolean writer = false;
    /** "Email a set-password link": offered, and the default, only while mail can deliver one. */
    public static final String PASSWORD_LINK = "link";
    /** "Set a password now". */
    public static final String PASSWORD_NOW = "now";
    /** {@link #PASSWORD_LINK} or {@link #PASSWORD_NOW}; create only. */
    private String passwordMode;
    private List<String> list = new ArrayList<>();

    public List<String> getList() {
        return list;
    }

    public void setList(List<String> list) {
        this.list = list;
    }

    // CPD-OFF -- A pojo and its form bean necessarily name the same fields, and
    // the getters/copy methods that move values between them are that list
    // written out twice. Removing the duplication means introducing a mapper,
    // which is a design change rather than a cleanup: the copy methods are also
    // where per-field rules live (EntryBean escapes the title on the way in and
    // unescapes it on the way out -- see CLAUDE.md, Templates), and a generic
    // mapper is exactly where such a rule gets lost.
    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getUserName() {
        return userName;
    }

    public void setUserName(String userName) {
        this.userName = userName;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getScreenName() {
        return screenName;
    }

    public void setScreenName(String screenName) {
        this.screenName = screenName;
    }

    public String getFullName() {
        return fullName;
    }

    public void setFullName(String fullName) {
        this.fullName = fullName;
    }

    public String getEmailAddress() {
        return emailAddress;
    }

    public void setEmailAddress(String emailAddress) {
        this.emailAddress = emailAddress;
    }

    public String getLocale() {
        return locale;
    }

    public void setLocale(String locale) {
        this.locale = locale;
    }

    public String getTimeZone() {
        return timeZone;
    }

    public void setTimeZone(String timeZone) {
        this.timeZone = timeZone;
    }


    public Boolean getEnabled() {
    // CPD-ON
        return enabled;
    }

    public void setEnabled(Boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isAdministrator() {
        return administrator;
    }

    public void setAdministrator(boolean administrator) {
        this.administrator = administrator;
    }

    public boolean isWriter() {
        return writer;
    }

    public void setWriter(boolean writer) {
        this.writer = writer;
    }

    public String getPasswordMode() {
        return passwordMode;
    }

    public void setPasswordMode(String passwordMode) {
        this.passwordMode = passwordMode;
    }

    public void copyTo(User dataHolder) {

        dataHolder.setScreenName(this.screenName);
        dataHolder.setFullName(this.fullName);
        dataHolder.setEmailAddress(StringUtils.trim(this.emailAddress));
        dataHolder.setLocale(this.locale);
        dataHolder.setTimeZone(this.timeZone);
        dataHolder.setEnabled(this.enabled);
    }


    public void copyFrom(User dataHolder) {

        this.id = dataHolder.getId();
        this.userName = dataHolder.getUserName();
        this.password = dataHolder.getPassword();
        this.screenName = dataHolder.getScreenName();
        this.fullName = dataHolder.getFullName();
        this.emailAddress = dataHolder.getEmailAddress();
        this.locale = dataHolder.getLocale();
        this.timeZone = dataHolder.getTimeZone();
        this.enabled = dataHolder.getEnabled();
        // The administrator flag is not part of the profile: it is a permission
        // check, and the controller that owns the user manager sets it (see
        // UserEditController). The bean is pure -- the data binder instantiates
        // it, so it has nowhere to get a manager from.
    }

}
