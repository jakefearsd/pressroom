package org.apache.roller.weblogger.ui.restapi.auth;

import org.apache.roller.weblogger.pojos.ApiToken;

/**
 * The token ceiling attached to an authenticated API request. Carried as the
 * Authentication's details; the principal itself stays a plain user-name
 * String so RollerHandlerInterceptor resolves the User unchanged.
 */
public record ApiPrincipal(String userName, String scopeWeblog, ApiToken.Role scopeRole, String tokenId) { }
