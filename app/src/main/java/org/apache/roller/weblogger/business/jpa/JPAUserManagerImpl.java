
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
package org.apache.roller.weblogger.business.jpa;

import java.sql.Timestamp;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.roller.weblogger.WebloggerException;
import org.apache.roller.weblogger.business.UserManager;
import org.apache.roller.weblogger.business.Weblogger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import jakarta.persistence.NoResultException;
import jakarta.persistence.Query;
import jakarta.persistence.TypedQuery;
import org.apache.roller.weblogger.config.WebloggerConfig;
import org.apache.roller.weblogger.pojos.GlobalPermission;
import org.apache.roller.weblogger.pojos.RollerPermission;
import org.apache.roller.weblogger.pojos.User;
import org.apache.roller.weblogger.pojos.UserRole;
import org.apache.roller.weblogger.pojos.Weblog;
import org.apache.roller.weblogger.pojos.WeblogPermission;


public class JPAUserManagerImpl implements UserManager {
    private static final Logger log = LoggerFactory.getLogger(JPAUserManagerImpl.class);

    private final Weblogger roller;
    private final JPAPersistenceStrategy strategy;
    
    // cached mapping of userNames -> userIds
    private final Map<String, String> userNameToIdMap = Collections.synchronizedMap(new HashMap<>());
    

    public JPAUserManagerImpl(Weblogger roller, JPAPersistenceStrategy strat) {
        log.debug("Instantiating JPA User Manager");
        this.roller = roller;
        this.strategy = strat;
    }


    @Override
    public void release() {}
    
    
    //--------------------------------------------------------------- user CRUD
 
    @Override
    public void saveUser(User user) throws WebloggerException {
        this.strategy.store(user);
    }

    
    @Override
    public void removeUser(User user) throws WebloggerException {
        String userName = user.getUserName();

        // remove permissions, maintaining both sides of relationship
        List<WeblogPermission> perms = getWeblogPermissions(user);
        for (WeblogPermission perm : perms) {
            this.strategy.remove(perm);
        }

        // roller_user_token rows FK the user with no cascade
        Query removeTokens = strategy.getNamedUpdate("UserToken.removeByUser");
        removeTokens.setParameter(1, user);
        removeTokens.executeUpdate();

        // roller_api_token rows FK the user with no cascade, same reason
        Query removeApiTokens = strategy.getNamedUpdate("ApiToken.removeByUser");
        removeApiTokens.setParameter(1, user);
        removeApiTokens.executeUpdate();

        // reader comments by the user, and the owners' replies to them; the
        // database would cascade, but not the shared cache
        roller.getCommentManager().removeCommentsByAuthor(user);

        this.strategy.remove(user);

        // remove entry from cache mapping
        this.userNameToIdMap.remove(userName);
    }

    
    @Override
    public void addUser(User newUser) throws WebloggerException {

        if (newUser == null) {
            throw new WebloggerException("cannot add null user");
        }
        
        boolean adminUser = false;
        List<User> existingUsers = this.getUsers(Boolean.TRUE, null, null, 0, 1);
        boolean firstUserAdmin = WebloggerConfig.getBooleanProperty("users.firstUserAdmin");
        if (existingUsers.isEmpty() && firstUserAdmin) {
            // Make first user an admin
            adminUser = true;

            newUser.setEnabled(Boolean.TRUE);
        }

        if (getUserByUserName(newUser.getUserName()) != null ||
                getUserByUserName(newUser.getUserName().toLowerCase(Locale.ROOT)) != null) {
            throw new WebloggerException("error.add.user.userNameInUse");
        }

        this.strategy.store(newUser);

        grantRole("editor", newUser);
        if (adminUser) {
            grantRole("admin", newUser);
        }
    }

    @Override
    public User getUser(String id) throws WebloggerException {
        return (User)this.strategy.load(User.class, id);
    }

    //------------------------------------------------------------ user queries

    @Override
    public User getUserByUserName(String userName) throws WebloggerException {
        return getUserByUserName(userName, Boolean.TRUE);
    }



    @Override
    public User getUserByUserName(String userName, Boolean enabled)
            throws WebloggerException {

        if (userName==null) {
            throw new WebloggerException("userName cannot be null");
        }
        
        // check cache first
        // NOTE: if we ever allow changing usernames then this needs updating
        if(this.userNameToIdMap.containsKey(userName)) {

            User user = this.getUser(
                    this.userNameToIdMap.get(userName));
            if (user != null) {
                // only return the user if the enabled status matches
                if(enabled == null || enabled.equals(user.getEnabled())) {
                    log.debug("userNameToIdMap CACHE HIT - {}", userName);
                    return user;
                }
            } else {
                // mapping hit with lookup miss?  mapping must be old, remove it
                this.userNameToIdMap.remove(userName);
            }
        }

        // cache failed, do lookup
        TypedQuery<User> query;
        Object[] params;
        if (enabled != null) {
            query = strategy.getNamedQuery(
                    "User.getByUserName&Enabled", User.class);
            params = new Object[] {userName, enabled};
        } else {
            query = strategy.getNamedQuery(
                    "User.getByUserName", User.class);
            params = new Object[] {userName};
        }
        bindParams(query, params);
        User user;
        try {
            user = query.getSingleResult();
        } catch (NoResultException e) {
            user = null;
        }

        // add mapping to cache
        if(user != null) {
            log.debug("userNameToIdMap CACHE MISS - {}", userName);
            this.userNameToIdMap.put(user.getUserName(), user.getId());
        }

        return user;
    }

    @Override
    public User getUserByEmail(String emailAddress) throws WebloggerException {
        if (emailAddress == null) {
            throw new WebloggerException("emailAddress cannot be null");
        }
        TypedQuery<User> query = strategy.getNamedQuery("User.getByEmailAddress&Enabled", User.class);
        query.setParameter(1, emailAddress);
        query.setParameter(2, Boolean.TRUE);
        // emailaddress has no unique constraint at the database level, so
        // more than one enabled account could share an address; the query is
        // ordered deterministically and this caps it to one so a duplicate
        // cannot throw NonUniqueResultException.
        query.setMaxResults(1);
        try {
            return query.getSingleResult();
        } catch (NoResultException e) {
            return null;
        }
    }

    @Override
    public User getUserBySsoIdentity(String issuer, String subject) throws WebloggerException {
        if (issuer == null || subject == null) {
            throw new WebloggerException("issuer and subject cannot be null");
        }
        TypedQuery<User> query = strategy.getNamedQuery("User.getBySsoIdentity", User.class);
        query.setParameter(1, issuer);
        query.setParameter(2, subject);
        try {
            return query.getSingleResult();
        } catch (NoResultException e) {
            return null;
        }
    }

    @Override
    public void addReader(User newUser) throws WebloggerException {
        if (newUser == null || !newUser.isSsoBound()) {
            throw new WebloggerException("addReader needs an account bound to an SSO identity");
        }
        // Deliberately NOT addUser: that grants "editor" to every new account and
        // "admin" to the first one when users.firstUserAdmin is set. A reader holds
        // exactly one role, whatever the table looks like (reader sign-in spec, criterion 15).
        if (getUserByUserName(newUser.getUserName(), null) != null) {
            throw new WebloggerException("error.add.user.userNameInUse");
        }
        this.strategy.store(newUser);
        grantRole("reader", newUser);
    }

    @Override
    public List<User> getUsers(Boolean enabled, Date startDate, Date endDate,
            int offset, int length)
            throws WebloggerException {

        // Each filter is applied only when given: a null date is no bound at
        // all, as UserManager documents. This used to default a null endDate
        // to "now" with a strict "<", which hid any account stamped in the
        // same millisecond as the listing -- and every caller passes null.
        List<String> predicates = new ArrayList<>();
        List<Object> params = new ArrayList<>();
        if (enabled != null) {
            params.add(enabled);
            predicates.add("u.enabled = ?" + params.size());
        }
        if (endDate != null) {
            params.add(new Timestamp(endDate.getTime()));
            predicates.add("u.dateCreated < ?" + params.size());
        }
        if (startDate != null) {
            params.add(new Timestamp(startDate.getTime()));
            predicates.add("u.dateCreated > ?" + params.size());
        }
        String where = predicates.isEmpty() ? "" : " WHERE " + String.join(" AND ", predicates);

        // The id tiebreak keeps same-timestamp accounts in one defined order,
        // or adjacent pages can repeat or skip a row.
        TypedQuery<User> query = strategy.getDynamicQuery(
                "SELECT u FROM User u" + where + " ORDER BY u.dateCreated DESC, u.id", User.class);
        bindParams(query, params.toArray());
        if (offset != 0) {
            query.setFirstResult(offset);
        }
        if (length != -1) {
            query.setMaxResults(length);
        }
        return query.getResultList();
    }

    @Override
    public List<User> getUsersStartingWith(String startsWith, Boolean enabled,
            int offset, int length) throws WebloggerException {
        TypedQuery<User> query;

        if (enabled != null) {
            if (startsWith != null) {
                query = strategy.getNamedQuery(
                        "User.getByEnabled&UserNameOrEmailAddressStartsWith", User.class);
                query.setParameter(1, enabled);
                query.setParameter(2, startsWith + '%');
                query.setParameter(3, startsWith + '%');
            } else {
                query = strategy.getNamedQuery(
                        "User.getByEnabled", User.class);
                query.setParameter(1, enabled);
            }
        } else {
            if (startsWith != null) {
                query = strategy.getNamedQuery(
                        "User.getByUserNameOrEmailAddressStartsWith", User.class);
                query.setParameter(1, startsWith +  '%');
            } else {
                query = strategy.getNamedQuery("User.getAll", User.class);
            }
        }
        if (offset != 0) {
            query.setFirstResult(offset);
        }
        if (length != -1) {
            query.setMaxResults(length);
        }
        return query.getResultList();
    }

    
    @Override
    public Map<String, Long> getUserNameLetterMap() throws WebloggerException {
        String lc = "ABCDEFGHIJKLMNOPQRSTUVWXYZ";
        Map<String, Long> results = new TreeMap<>();
        TypedQuery<Long> query = strategy.getNamedQuery(
                "User.getCountByUserNameLike", Long.class);
        for (int i=0; i<26; i++) {
            char currentChar = lc.charAt(i);
            query.setParameter(1, currentChar + "%");
            List<Long> row = query.getResultList();
            Long count = row.get(0);
            results.put(String.valueOf(currentChar), count);
        }
        return results;
    }

    
    @Override
    public List<User> getUsersByLetter(char letter, int offset, int length)
            throws WebloggerException {
        TypedQuery<User> query = strategy.getNamedQuery(
                "User.getByUserNameOrderByUserName", User.class);
        // Uppercased to match the query's UPPER(userName).
        query.setParameter(1, Character.toUpperCase(letter) + "%");
        if (offset != 0) {
            query.setFirstResult(offset);
        }
        if (length != -1) {
            query.setMaxResults(length);
        }
        return query.getResultList();
    }

    
    /**
     * Get count of users, enabled only
     */
    @Override
    public long getUserCount() throws WebloggerException {
        TypedQuery<Long> q = strategy.getNamedQuery("User.getCountEnabledDistinct", Long.class);
        q.setParameter(1, Boolean.TRUE);
        List<Long> results = q.getResultList();
        return results.get(0);
    }

    //-------------------------------------------------------- permissions CRUD
 
    @Override
    public boolean checkPermission(RollerPermission perm, User user) throws WebloggerException {

        // if permission a weblog permission
        if (perm instanceof WeblogPermission) {
            // if user has specified permission in weblog return true
            WeblogPermission permToCheck = (WeblogPermission)perm;
            try {
                RollerPermission existingPerm = getWeblogPermission(permToCheck.getObjectId(), user);
                if (existingPerm != null && existingPerm.implies(perm)) {
                    return true;
                }
            } catch (WebloggerException ignored) {
            }
        }

        // if Blog Server admin would still have weblog permission above
        GlobalPermission globalPerm = globalPermissionOf(user);
        if (globalPerm.implies(perm)) {
            return true;
        }

        log.debug("PERM CHECK FAILED: user {} does not have {}", user.getUserName(), perm.toString());
        return false;
    }

    
    @Override
    public WeblogPermission getWeblogPermission(Weblog weblog, User user) throws WebloggerException {
        return getWeblogPermission(weblog.getHandle(), user);
    }

    /**
     * The permission row for a user on the weblog named by {@code handle} --
     * what a {@code WeblogPermission}'s {@code objectId} is. {@code checkPermission}
     * uses this form so it never has to resolve the weblog entity just to
     * look the row up again by its handle.
     */
    private WeblogPermission getWeblogPermission(String handle, User user) throws WebloggerException {
        TypedQuery<WeblogPermission> q = strategy.getNamedQuery("WeblogPermission.getByUserName&WeblogId"
                , WeblogPermission.class);
        q.setParameter(1, user.getUserName());
        q.setParameter(2, handle);
        try {
            return q.getSingleResult();
        } catch (NoResultException ignored) {
            return null;
        }
    }

    /**
     * The global permission a user's roles imply. This used to be the
     * {@code GlobalPermission(User)} constructor, which asked this manager for
     * the roles through the static service locator; resolving the roles is
     * this manager's job, the role-to-action mapping stays on the permission.
     */
    public GlobalPermission globalPermissionOf(User user) throws WebloggerException {
        return GlobalPermission.impliedByRoles(user, getRoles(user));
    }

    @Override
    public void grantWeblogPermission(Weblog weblog, User user, List<String> actions) throws WebloggerException {

        // first, see if user already has a permission for the specified object
        TypedQuery<WeblogPermission> q = strategy.getNamedQuery("WeblogPermission.getByUserName&WeblogId",
                WeblogPermission.class);
        q.setParameter(1, user.getUserName());
        q.setParameter(2, weblog.getHandle());
        WeblogPermission existingPerm = null;
        try {
            existingPerm = q.getSingleResult();
        } catch (NoResultException ignored) {}

        // permission already exists, so add any actions specified in perm argument
        if (existingPerm != null) {
            existingPerm.addActions(actions);
            this.strategy.store(existingPerm);
        } else {
            // it's a new permission, so store it
            WeblogPermission perm = new WeblogPermission(weblog, user, actions);
            this.strategy.store(perm);
        }
    }


    @Override
    public void revokeWeblogPermission(Weblog weblog, User user, List<String> actions) throws WebloggerException {

        // get specified permission
        TypedQuery<WeblogPermission> q = strategy.getNamedQuery("WeblogPermission.getByUserName&WeblogId",
                WeblogPermission.class);
        q.setParameter(1, user.getUserName());
        q.setParameter(2, weblog.getHandle());
        WeblogPermission oldperm;
        try {
            oldperm = q.getSingleResult();
        } catch (NoResultException ignored) {
            throw new WebloggerException("ERROR: permission not found");
        }

        // remove actions specified in perm argument
        oldperm.removeActions(actions);

        if (oldperm.isEmpty()) {
            // no actions left in permission so remove it
            this.strategy.remove(oldperm);
        } else {
            // otherwise save it
            this.strategy.store(oldperm);
        }
    }

    
    @Override
    public List<WeblogPermission> getWeblogPermissions(User user) throws WebloggerException {
        TypedQuery<WeblogPermission> q = strategy.getNamedQuery("WeblogPermission.getByUserName",
                WeblogPermission.class);
        q.setParameter(1, user.getUserName());
        return q.getResultList();
    }

    @Override
    public List<WeblogPermission> getWeblogPermissions(Weblog weblog) throws WebloggerException {
        TypedQuery<WeblogPermission> q = strategy.getNamedQuery("WeblogPermission.getByWeblogId",
                WeblogPermission.class);
        q.setParameter(1, weblog.getHandle());
        return q.getResultList();
    }

//-------------------------------------------------------------- role CRUD
 
    
    /**
     * Returns true if user has role specified.
     */
    @Override
    @Deprecated
    public boolean hasRole(String roleName, User user) throws WebloggerException {
        TypedQuery<UserRole> q = strategy.getNamedQuery("UserRole.getByUserNameAndRole", UserRole.class);
        q.setParameter(1, user.getUserName());
        q.setParameter(2, roleName);
        try {
            q.getSingleResult();
        } catch (NoResultException e) {
            return false;
        }
        return true;
    }

    
    /**
     * Get all of user's roles.
     */
    @Override
    @Deprecated
    public List<String> getRoles(User user) throws WebloggerException {
        TypedQuery<UserRole> q = strategy.getNamedQuery("UserRole.getByUserName", UserRole.class);
        q.setParameter(1, user.getUserName());
        List<UserRole> roles = q.getResultList();
        List<String> roleNames = new ArrayList<>();
        if (roles != null) {
            for (UserRole userRole : roles) {
                roleNames.add(userRole.getRole());
            }
        }
        return roleNames;
    }

    /**
     * Grant to user role specified by role name.
     */
    @Override
    public void grantRole(String roleName, User user) throws WebloggerException {
        if (!hasRole(roleName, user)) {
            UserRole role = new UserRole(user.getUserName(), roleName);
            this.strategy.store(role);
        }
    }

    
    @Override
    public void revokeRole(String roleName, User user) throws WebloggerException {
        TypedQuery<UserRole> q = strategy.getNamedQuery("UserRole.getByUserNameAndRole", UserRole.class);
        q.setParameter(1, user.getUserName());
        q.setParameter(2, roleName);
        try {
            UserRole role = q.getSingleResult();
            this.strategy.remove(role);

        } catch (NoResultException e) {
            throw new WebloggerException("ERROR: removing role", e);
        }
    }

    private static void bindParams(TypedQuery<?> query, Object[] params) {
        for (int i = 0; i < params.length; i++) {
            query.setParameter(i + 1, params[i]);
        }
    }
}
