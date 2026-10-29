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

import com.wikantik.pressroom.pojos.User;

/**
 * The New agent form and an agent page's Details form (spec 2026-10-13,
 * section 4). Field names are {@code bean.*}, bound through BaseController's
 * prefix. No password: an agent never signs in.
 */
public class AgentBean {

    private String userName;
    private String name;
    private String emailAddress;
    private String notes;
    private boolean siteAdmin;

    /** The Details form for an existing agent. Its email is set once, at creation. */
    static AgentBean of(User agent, boolean siteAdmin) {
        AgentBean bean = new AgentBean();
        bean.setUserName(agent.getUserName());
        bean.setName(agent.getScreenName());
        bean.setNotes(agent.getNotes());
        bean.setSiteAdmin(siteAdmin);
        return bean;
    }

    public String getUserName() {
        return userName;
    }

    public void setUserName(String userName) {
        this.userName = userName;
    }

    /** Stored as both screen name and full name. */
    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    /** Optional; blank becomes {@code <userName>@agents.invalid}. */
    public String getEmailAddress() {
        return emailAddress;
    }

    public void setEmailAddress(String emailAddress) {
        this.emailAddress = emailAddress;
    }

    public String getNotes() {
        return notes;
    }

    public void setNotes(String notes) {
        this.notes = notes;
    }

    /** The Site administrator switch: the admin role, for the site-wide admin API. */
    public boolean isSiteAdmin() {
        return siteAdmin;
    }

    public void setSiteAdmin(boolean siteAdmin) {
        this.siteAdmin = siteAdmin;
    }
}
