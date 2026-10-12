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

import java.security.Permission;
import java.util.ArrayList;
import java.util.List;

import org.apache.commons.lang3.builder.EqualsBuilder;
import org.apache.commons.lang3.builder.HashCodeBuilder;
import org.apache.roller.weblogger.WebloggerException;
import org.apache.roller.weblogger.config.WebloggerConfig;
import org.apache.roller.weblogger.util.Utilities;


/**
 * Represents a permission that applies globally to the entire web application.
 */
public class GlobalPermission extends RollerPermission {

    private static final long serialVersionUID = 1L;

    protected String actions;

    /** Allowed to login and edit profile */
    public static final String LOGIN  = "login";
    
    /** Allowed to login and do weblogging */
    public static final String WEBLOG = "weblog";

    /** Allowed to login and do everything, including site-wide admin */
    public static final String ADMIN  = "admin";

    /**
     * Allowed to post reader comments. Implies nothing else: not login, not
     * weblog, not admin.
     */
    public static final String COMMENT = "comment";
    
    /**
     * The global permission implied by a user's roles: the union, without
     * repeats, of the actions {@code role.action.<role>} names for each role.
     *
     * <p>This used to be a constructor, {@code GlobalPermission(User)}, that
     * asked the user manager for the roles itself through the static service
     * locator. Resolving the roles is {@code UserManager}'s job
     * ({@code JPAUserManagerImpl.globalPermissionOf(User)}); what is left here
     * is the pure mapping from roles to actions.
     *
     * @param user  the user the permission is for (names the permission)
     * @param roles the user's roles, as the user manager reports them
     */
    public static GlobalPermission impliedByRoles(User user, List<String> roles) throws WebloggerException {
        List<String> actionsList = new ArrayList<>();
        for (String role : roles) {
            String impliedActions = WebloggerConfig.getProperty("role.action." + role);
            if (impliedActions != null) {
                List<String> toAdds = Utilities.stringToStringList(impliedActions, ",");
                for (String toAdd : toAdds) {
                    if (!actionsList.contains(toAdd)) {
                        actionsList.add(toAdd);
                    }
                }
            }
        }
        return new GlobalPermission(user, actionsList);
    }

    /** 
     * Create global permission with the actions specified by array.
     * @param actions actions to add to permission
     * @throws org.apache.roller.weblogger.WebloggerException
     */
    public GlobalPermission(List<String> actions) throws WebloggerException {
        super("GlobalPermission user: N/A");
        setActionsAsList(actions);
    }
        
    /** 
     * Create global permission for one specific user initialized with the 
     * actions specified by array.
     * @param user User of permission.
     * @throws org.apache.roller.weblogger.WebloggerException
     */
    public GlobalPermission(User user, List<String> actions) throws WebloggerException {
        super("GlobalPermission user: " + user.getUserName());
        setActionsAsList(actions);
    }
        
    @Override
    public boolean implies(Permission perm) {
        if (getActionsAsList().isEmpty()) {
            // new, unsaved user.
            return false;
        }
        if (perm instanceof WeblogPermission) {
            if (hasAction(ADMIN)) {
                // admin implies all other permissions
                return true;                
            } 
        } else if (perm instanceof RollerPermission) {
            if (hasAction(ADMIN)) {
                // admin implies all other permissions
                return true;
            }
            for (String action : ((RollerPermission) perm).getActionsAsList()) {
                if (!impliesAction(action)) {
                    return false; // fail closed, including for unknown actions
                }
            }
            return true;
        }
        return false;
    }
    
    /** Whether this holder (known not to hold ADMIN) has {@code action}: WEBLOG implies LOGIN; COMMENT stands alone. */
    private boolean impliesAction(String action) {
        if (LOGIN.equals(action)) {
            return hasAction(LOGIN) || hasAction(WEBLOG);
        }
        if (WEBLOG.equals(action) || COMMENT.equals(action)) {
            return hasAction(action);
        }
        return false;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("GlobalPermission: ");
        for (String action : getActionsAsList()) { 
            sb.append(" ").append(action).append(" ");
        }
        return sb.toString();
    }

    @Override
    public void setActions(String actions) {
        this.actions = actions;
    }

    @Override
    public String getActions() {
        return actions;
    }

    @Override
    public boolean equals(Object other) {
        if (other == this) {
            return true;
        }
        if (!(other instanceof GlobalPermission)) {
            return false;
        }
        GlobalPermission o = (GlobalPermission) other;
        return new EqualsBuilder()
                .append(getActions(), o.getActions())
                .isEquals();
    }

    @Override
    public int hashCode() {
        return new HashCodeBuilder()
                .append(getActions())
                .toHashCode();
    }

}
