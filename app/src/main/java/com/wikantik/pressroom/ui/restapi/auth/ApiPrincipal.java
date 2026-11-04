package com.wikantik.pressroom.ui.restapi.auth;

import com.wikantik.pressroom.pojos.ApiToken;
import com.wikantik.pressroom.ui.controllers.WeblogPinnedCredential;

/**
 * The token ceiling attached to an authenticated API request. Carried as the
 * Authentication's details; the principal itself stays a plain user-name
 * String so PressroomHandlerInterceptor resolves the User unchanged, and
 * reads the pin ({@code scopeWeblog}) through {@link WeblogPinnedCredential}.
 */
public record ApiPrincipal(String userName, String scopeWeblog, ApiToken.Role scopeRole, String tokenId)
        implements WeblogPinnedCredential { }
