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
package com.wikantik.pressroom.ui.controllers;

import com.wikantik.pressroom.pojos.ApiToken;
import com.wikantik.pressroom.pojos.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@code pressroom.admin} log (spec 2026-10-13, section 6): one INFO line
 * per change to an account, a weblog membership or an API token, naming the
 * target and, as {@code by=}, the account that made it. The admin UI and the
 * API both write here, through this one class, so a controller keeps its
 * single logger. Never pass a secret: a token is named by
 * {@link #tokenDetails}, which leaves its secret out by construction.
 */
public final class AdminLog {

    /** The logger's name, configured in log4j2.xml. */
    public static final String NAME = "pressroom.admin";

    private static final Logger log = LoggerFactory.getLogger(NAME);

    private AdminLog() {
    }

    /**
     * Writes {@code "<event> <details> by=<user>"}.
     *
     * @param event   a dotted name, e.g. {@code token.minted}
     * @param by      the acting account; null is written as {@code -}
     * @param details the target as {@code key=value} pairs
     */
    public static void record(String event, User by, String details) {
        log.info("{} {} by={}", event, details, by == null ? "-" : by.getUserName());
    }

    /** A token's id, label, owner, role, weblog pin and expiry -- everything but its secret. */
    public static String tokenDetails(ApiToken token) {
        return "id=" + token.getId()
                + " label=" + token.getLabel()
                + " owner=" + (token.getUser() == null ? "-" : token.getUser().getUserName())
                + " role=" + token.getScopeRole()
                + " weblog=" + (token.getScopeWeblog() == null ? "*" : token.getScopeWeblog())
                + " expires=" + (token.getExpiresAt() == null ? "never" : token.getExpiresAt().toInstant());
    }
}
