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

package com.wikantik.pressroom.ui.controllers.core;

import com.wikantik.pressroom.pojos.User;


/**
 * Bean for managing the form data used by Registration and Edit User Profile.
 */
public class ProfileBean {

    private String id = null;
    private String userName = null;
    private String password = null;
    private String screenName = null;
    private String fullName = null;
    private String emailAddress = null;
    private String locale = null;
    private String timeZone = null;

    private String passwordText = null;
    private String passwordConfirm = null;


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


    public String getPasswordText() {
    // CPD-ON
        return passwordText;
    }

    public void setPasswordText(String passwordText) {
        this.passwordText = passwordText;
    }

    public String getPasswordConfirm() {
        return passwordConfirm;
    }

    public void setPasswordConfirm(String passwordConfirm) {
        this.passwordConfirm = passwordConfirm;
    }


    public void copyTo(User dataHolder) {
        // avoiding password, that is handled separately in class Profile
        dataHolder.setScreenName(this.screenName);
        dataHolder.setFullName(this.fullName);
        dataHolder.setEmailAddress(this.emailAddress);
        dataHolder.setLocale(this.locale);
        dataHolder.setTimeZone(this.timeZone);
    }


    public void copyFrom(User dataHolder) {
        // avoiding password, that is handled separately in class Profile
        this.id = dataHolder.getId();
        this.userName = dataHolder.getUserName();
        this.screenName = dataHolder.getScreenName();
        this.fullName = dataHolder.getFullName();
        this.emailAddress = dataHolder.getEmailAddress();
        this.locale = dataHolder.getLocale();
        this.timeZone = dataHolder.getTimeZone();
    }

}
