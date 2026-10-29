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

package com.wikantik.pressroom.pojos; 

import java.io.Serializable;
import java.security.Permission;
import java.util.List;
import org.apache.commons.lang3.builder.EqualsBuilder;
import org.apache.commons.lang3.builder.HashCodeBuilder;


/**
 * Permission for one specific weblog
 * @author Dave Johnson
 */
public class WeblogPermission extends ObjectPermission implements Serializable {

    private static final long serialVersionUID = 1L;

    public static final String EDIT_DRAFT = "edit_draft";
    public static final String POST = "post";
    public static final String ADMIN = "admin";
    public static final List<String> ALL_ACTIONS = List.of(EDIT_DRAFT, POST, ADMIN);

    public WeblogPermission() {
        // required by JPA
    }

    public WeblogPermission(Weblog weblog, User user, String actions) {
        super("WeblogPermission user: " + user.getUserName());
        setActions(actions);
        objectType = "Weblog";
        objectId = weblog.getHandle();
        userName = user.getUserName();
    }
    
    public WeblogPermission(Weblog weblog, User user, List<String> actions) {
        super("WeblogPermission user: " + user.getUserName());
        setActionsAsList(actions); 
        objectType = "Weblog";
        objectId = weblog.getHandle();
        userName = user.getUserName();
    }
    
    public WeblogPermission(Weblog weblog, List<String> actions) {
        super("WeblogPermission user: N/A");
        setActionsAsList(actions); 
        objectType = "Weblog";
        objectId = weblog.getHandle();
    }
    
    @Override
    public boolean implies(Permission perm) {
        if (getActionsAsList().isEmpty()) {
            // Grants nothing, so implies nothing. Without this guard the ladder
            // below falls through every branch to "return true" and a permission
            // row with an empty or NULL actions column would imply every action,
            // weblog administration included. GlobalPermission.implies() has the
            // same guard for the same reason.
            return false;
        }
        if (perm instanceof WeblogPermission) {
            WeblogPermission rperm = (WeblogPermission)perm;

            if (hasAction(ADMIN)) {
                // admin implies all other permissions
                return true;
            } else if (hasAction(POST)) {
                // Best we've got is POST, so make sure perm doesn't specify ADMIN
                for (String action : rperm.getActionsAsList()) {
                    if (ADMIN.equals(action)) {
                        return false;
                    }
                }
            } else if (hasAction(EDIT_DRAFT)) {
                // Best we've got is EDIT_DRAFT, so make sure perm doesn't specify anything else
                for (String action : rperm.getActionsAsList()) {
                    if (POST.equals(action)) {
                        return false;
                    }
                    if (ADMIN.equals(action)) {
                        return false;
                    }
                }
            }
            return true;
        }
        return false;
    }
    
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("WeblogPermission: ");
        for (String action : getActionsAsList()) {
            sb.append(" ").append(action).append(" ");
        }
        return sb.toString();
    }

    @Override
    public boolean equals(Object other) {
        if (other == this) {
            return true;
        }
        if (!(other instanceof WeblogPermission)) {
            return false;
        }
        WeblogPermission o = (WeblogPermission)other;
        return new EqualsBuilder()
                .append(getUserName(), o.getUserName())
                .append(getObjectId(), o.getObjectId())
                .append(getActions(), o.getActions())
                .isEquals();
    }

    @Override
    public int hashCode() {
        return new HashCodeBuilder()
                .append(getUserName())
                .append(getObjectId())
                .append(getActions())
                .toHashCode();
    }
}
