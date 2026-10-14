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
package org.apache.roller.weblogger.ui.controllers.admin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.roller.weblogger.WebloggerException;
import org.apache.roller.weblogger.business.Weblogger;
import org.apache.roller.weblogger.pojos.ApiToken;
import org.apache.roller.weblogger.pojos.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** One row of Admin → API tokens: a token, and the username of whoever issued it. */
public final class TokenRow {

    private static final Logger log = LoggerFactory.getLogger(TokenRow.class);

    private final ApiToken token;
    private final String issuedBy;

    TokenRow(ApiToken token, String issuedBy) {
        this.token = token;
        this.issuedBy = issuedBy;
    }

    public ApiToken getToken() {
        return token;
    }

    /** The issuer's username; the stored id if that account is gone; null before V033. */
    public String getIssuedBy() {
        return issuedBy;
    }

    /** Neither revoked nor expired. */
    public boolean isLive() {
        return token.isUsable();
    }

    /**
     * Every token on the site, newest first, only live ones unless
     * {@code includeInactive}. A display path: a store that cannot list shows none.
     */
    static List<TokenRow> load(Weblogger weblogger, boolean includeInactive) {
        try {
            Map<String, String> issuers = new HashMap<>();
            List<TokenRow> rows = new ArrayList<>();
            for (ApiToken token : weblogger.getApiTokenManager().getAllTokens(includeInactive)) {
                rows.add(new TokenRow(token, issuerName(weblogger, issuers, token.getIssuedBy())));
            }
            return rows;
        } catch (WebloggerException e) {
            log.error("Could not list the API tokens", e);
            return List.of();
        }
    }

    private static String issuerName(Weblogger weblogger, Map<String, String> issuers, String userId)
            throws WebloggerException {
        if (userId == null) {
            return null;
        }
        String name = issuers.get(userId);
        if (name == null) {
            User issuer = weblogger.getUserManager().getUser(userId);
            name = issuer == null ? userId : issuer.getUserName();
            issuers.put(userId, name);
        }
        return name;
    }
}
