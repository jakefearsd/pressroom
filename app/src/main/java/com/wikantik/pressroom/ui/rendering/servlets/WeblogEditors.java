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
package com.wikantik.pressroom.ui.rendering.servlets;

import com.wikantik.pressroom.business.UserManager;
import com.wikantik.pressroom.pojos.GlobalPermission;
import com.wikantik.pressroom.pojos.User;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.pojos.WeblogPermission;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

/**
 * Whether the request comes from an editor of a weblog, for the raw rendering
 * servlets that sit outside PressroomHandlerInterceptor and so check for
 * themselves: private media (MediaResourceServlet) and the authoring preview
 * of unpublished drafts (PreviewServlet; code review, 2026-11-03).
 */
final class WeblogEditors {

    private static final Logger log = LoggerFactory.getLogger(WeblogEditors.class);

    private WeblogEditors() {
    }

    /**
     * True for a signed-in user holding at least EDIT_DRAFT on the weblog and
     * the global {@code login} action. Anonymous requests and users of other
     * weblogs are false; any lookup failure fails closed.
     *
     * <p>The global check matters because a weblog permission ignores global
     * roles: a reader once given "Can write" and then demoted keeps its weblog
     * membership but holds {@code reader} only, which grants no action.
     */
    static boolean requesterMayEdit(Weblog weblog, UserManager umgr) {
        try {
            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
            if (authentication == null || !authentication.isAuthenticated()
                    || authentication instanceof AnonymousAuthenticationToken) {
                return false;
            }
            User user = umgr.getUserByUserName(authentication.getName());
            return user != null
                    && umgr.checkPermission(new GlobalPermission(List.of(GlobalPermission.LOGIN)), user)
                    && umgr.checkPermission(new WeblogPermission(weblog, List.of(WeblogPermission.EDIT_DRAFT)), user);
        } catch (Exception e) {
            log.debug("could not resolve an editor session for weblog {}", weblog.getHandle(), e);
            return false;
        }
    }
}
