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

package com.wikantik.pressroom.ui.core.security;

import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.business.Weblogger;
import com.wikantik.pressroom.business.WebloggerProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationListener;
import org.springframework.security.authentication.event.InteractiveAuthenticationSuccessEvent;

/**
 * Stamps {@code last_login} on every interactive sign-in (spec 2026-10-13,
 * section 2). Form login, remember-me and SSO each publish
 * {@link InteractiveAuthenticationSuccessEvent} from their filter; HTTP Basic
 * on {@code /api/**} does not, so an API call is not a sign-in.
 *
 * <p>A display value, not a decision: a store that cannot record it is
 * logged and the sign-in proceeds. It runs inside the security chain, where a
 * form or SSO sign-in answers the request without ever reaching
 * {@code PersistenceSessionFilter}, so it releases the persistence session
 * itself, as {@code SsoSuccessHandler} does.
 */
public class LastLoginRecorder implements ApplicationListener<InteractiveAuthenticationSuccessEvent> {

    private static final Logger log = LoggerFactory.getLogger(LastLoginRecorder.class);

    private final WebloggerProvider webloggerProvider;

    /** @param webloggerProvider asked per event: a sign-in can arrive before the tier is up */
    public LastLoginRecorder(WebloggerProvider webloggerProvider) {
        this.webloggerProvider = webloggerProvider;
    }

    @Override
    public void onApplicationEvent(InteractiveAuthenticationSuccessEvent event) {
        if (!webloggerProvider.isBootstrapped()) {
            return;
        }
        Weblogger weblogger = webloggerProvider.getWeblogger();
        String userName = event.getAuthentication().getName();
        try {
            weblogger.getUserManager().recordLogin(userName);
            weblogger.flush();
        } catch (WebloggerException e) {
            log.warn("Could not record the sign-in of {}", userName, e);
        } finally {
            weblogger.release();
        }
    }
}
