package com.wikantik.pressroom.ui.restapi.auth;

import com.wikantik.pressroom.pojos.ApiToken;

/**
 * The token ceiling attached to an authenticated API request. Carried as the
 * Authentication's details; the principal itself stays a plain user-name
 * String so PressroomHandlerInterceptor resolves the User unchanged.
 */
public record ApiPrincipal(String userName, String scopeWeblog, ApiToken.Role scopeRole, String tokenId) { }
