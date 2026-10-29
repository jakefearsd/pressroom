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

package com.wikantik.pressroom.business;

import java.util.Date;
import java.util.List;
import java.util.Map;
import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.pojos.PressroomPermission;
import com.wikantik.pressroom.pojos.User;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.pojos.WeblogPermission;


/**
 * Interface to user, role and permissions management.
 */
public interface UserManager {
    
    //--------------------------------------------------------------- user CRUD    
    
    /**
     * Add a new user.
     * 
     * This method is used to provide supplemental data to new user accounts,
     * such as adding the proper roles for the user.  This method should see if
     * the new user is the first user and give that user the admin role if so.
     *
     * @param newUser User object to be added.
     * @throws WebloggerException If there is a problem.
     */
    void addUser(User newUser) throws WebloggerException;
    
    
    /**
     * Save a user.
     *
     * @param user User to be saved.
     * @throws WebloggerException If there is a problem.
     */
    void saveUser(User user) throws WebloggerException;
    
    
    /**
     * Remove a user.
     *
     * @param user User to be removed.
     * @throws WebloggerException If there is a problem.
     */
    void removeUser(User user) throws WebloggerException;
    
    
    /**
     * Get count of enabled users
     */    
    long getUserCount() throws WebloggerException;
    
    
    //------------------------------------------------------------ user queries

    /**
     * Retrieve a user by its internal identifier id.
     *
     * @param id the id of the user to retrieve.
     * @return the user object with specified id or null if not found
     * @throws WebloggerException
     */
    User getUser(String id) throws WebloggerException;

    /**
     * Lookup a user by UserName.
     * 
     * This lookup is restricted to 'enabled' users by default.  So this method
     * will return null if the user is found but is not enabled.
     * 
     * @param userName User Name of user to lookup.
     * @return The user, or null if not found or not enabled.
     * @throws WebloggerException If there is a problem.
     */
    User getUserByUserName(String userName) throws WebloggerException;
    
    /**
     * Lookup a user by UserName with the given enabled status.
     * 
     * @param userName User Name of user to lookup.
     * @param enabled True if user is enabled, false otherwise.
     * @return The user, or null if not found or of the proper enabled status.
     * @throws WebloggerException If there is a problem.
     */
    User getUserByUserName(String userName, Boolean enabled)
        throws WebloggerException;

    /**
     * Lookup an enabled user by email address.
     *
     * <p>Restricted to enabled users, the same as {@link #getUserByUserName(String)}
     * -- a disabled account must not be reachable through the forgot-password
     * identifier lookup.
     *
     * <p>The match is case-sensitive, the same as {@link #getUserByUserName(String)}
     * -- callers that want a case-insensitive identifier lookup (e.g. an
     * email typed with different capitalisation) must normalise the case
     * themselves before calling.
     *
     * <p>{@code emailaddress} carries no uniqueness constraint at the
     * database level, so more than one enabled account can in principle share
     * an address; this returns at most one, chosen deterministically (lowest
     * username) rather than whichever the database happens to return first.
     *
     * @param emailAddress email address of the user to lookup.
     * @return The enabled local (non-SSO) account with this email address, or null if not found.
     * @throws WebloggerException If there is a problem.
     */
    User getUserByEmail(String emailAddress) throws WebloggerException;

    /**
     * Lookup the account bound to an OpenID Connect identity, enabled or not
     * (a disabled account must still be found, or a sign-in would create a
     * replacement for it).
     *
     * @param issuer The provider's issuer URL.
     * @param subject The provider's stable subject id for the person.
     * @return The bound user, or null if none.
     * @throws WebloggerException If either argument is null or on a store problem.
     */
    User getUserBySsoIdentity(String issuer, String subject) throws WebloggerException;

    /**
     * Store a new reader account (one already bound to an SSO identity) and
     * grant it only the {@code reader} role. Unlike addUser this never grants
     * "editor", and never "admin" to a first user.
     *
     * @param newUser A user whose {@link User#isSsoBound()} is true.
     * @throws WebloggerException If the user is not SSO-bound, the username is taken, or on a store problem.
     */
    void addReader(User newUser) throws WebloggerException;

    /**
     * Stores a new agent account (spec 2026-10-13, section 1): kind AGENT,
     * enabled, no password, and the {@code editor} role -- plus {@code admin}
     * when {@code siteAdmin}. A blank email becomes
     * {@code <userName>@agents.invalid}.
     *
     * @throws WebloggerException {@code error.add.user.userNameInUse} when the
     *         username is taken; also when the agent carries a password
     */
    void addAgent(User agent, boolean siteAdmin) throws WebloggerException;

    /**
     * Stamps the named account's last sign-in with now. Enabled or not, an
     * unknown name does nothing. Begins a transaction; the caller flushes.
     */
    void recordLogin(String userName) throws WebloggerException;

    /**
     * Every account, ordered by username, for the admin Users list.
     *
     * @param kind only this kind, or null for every account
     * @param text kept when it occurs, ignoring case, in the username, screen
     *             name, full name or email; blank for no filter
     */
    List<User> getAccounts(User.Kind kind, String text) throws WebloggerException;


    /**
     * Lookup a group of users.
     * 
     * The lookup may be constrained to users with a certain enabled status,
     * to users created within a certain date range, and the results can be
     * confined to a certain offset & length for paging abilities.
     * 
     * @param enabled True for enabled only, False for disabled only (or null for all)
     * @param startDate Restrict to those created after startDate (or null for all)
     * @param endDate Restrict to those created before endDate (or null for all)
     * @param offset The index of the first result to return.
     * @param length The number of results to return.
     * @return List A list of UserDatUsers which match the criteria.
     * @throws WebloggerException If there is a problem.
     */
    List<User> getUsers(
            Boolean enabled,
            Date    startDate,
            Date    endDate,
            int     offset,
            int     length) throws WebloggerException;

    /**
     * Lookup users whose usernames or email addresses start with a string.
     *
     * @param startsWith String to match userNames and emailAddresses against
     * @param offset     Offset into results (for paging)
     * @param length     Max to return (for paging)
     * @param enabled    True for only enalbed, false for disabled, null for all
     * @return List of (up to length) users that match startsWith string
     */
    List<User> getUsersStartingWith(String startsWith,
            Boolean enabled, int offset, int length) throws WebloggerException;
    
    
    /**
     * Get map with 26 entries, one for each letter A-Z and
     * containing Longs reflecting the number of users whose
     * names start with each letter.
     */
    Map<String, Long> getUserNameLetterMap() throws WebloggerException;
    
    
    /** 
     * Get collection of users whose names begin with specified letter 
     */
    List<User> getUsersByLetter(char letter, int offset, int length)
        throws WebloggerException;
    

    //-------------------------------------------------------- permissions CRUD

    
    /**
     * Return true if user has permission specified.
     */
    boolean checkPermission(PressroomPermission perm, User user)
            throws WebloggerException;
    
    
    /**
     * Grant to user specific actions in a weblog.
     * (will create new permission record if none already exists)
     * @param weblog  Weblog to grant permissions in
     * @param user    User to grant permissions to
     * @param actions Actions to be granted
     */
    void grantWeblogPermission(Weblog weblog, User user, List<String> actions)
            throws WebloggerException;


    /**
     * Revoke from user specific actions in a weblog.
     * (if resulting permission has empty removes permission record)
     * @param weblog  Weblog to grant permissions in
     * @param user    User to grant permissions to
     * @param actions Actions to be granted
     */
    void revokeWeblogPermission(Weblog weblog, User user, List<String> actions)
            throws WebloggerException;

    
    /**
     * Get all of user's weblog permissions.
     */
    List<WeblogPermission> getWeblogPermissions(User user)
            throws WebloggerException;

    /**
     * Get all permissions associated with a weblog.
     */
    List<WeblogPermission> getWeblogPermissions(Weblog weblog)
            throws WebloggerException;

    /**
     * The distinct screen names of every account holding a permission on any
     * weblog (a member of it, pending or not), in one query. The account page
     * refuses a reader's display name that matches one of these.
     */
    List<String> getScreenNamesOfWeblogMembers() throws WebloggerException;


    /**
     * Get user's permission within a weblog or null if none.
     */
    WeblogPermission getWeblogPermission(Weblog weblog, User user)
            throws WebloggerException;


    //--------------------------------------------------------------- role CRUD

    
    /**
     * Grant role to user.
     */
    void grantRole(String roleName, User user) throws WebloggerException;
    
    
    /**
     * Revoke role from user.
     */
    void revokeRole(String roleName, User user) throws WebloggerException;

        
    /**
     * Returns true if user has the named role. This reads the stored role, not
     * what the role implies; for an authorisation decision use
     * {@link #checkPermission}.
     */
    boolean hasRole(String roleName, User user) throws WebloggerException;
    
    
    /**
     * Get all roles associated with user. The only reader of the stored roles,
     * which Spring Security's authorities, the admin API and reader sign-in need;
     * {@link #checkPermission} answers "may they", not "which roles do they hold".
     */
    List<String> getRoles(User user) throws WebloggerException;

    
    /**
     * Release any resources held by manager.
     */
    void release();
}



