package com.wikantik.pressroom.ui.controllers;

/**
 * An authentication's details that limit the caller to one weblog: an API
 * token's pin ({@code ApiPrincipal} implements it). The permission check in
 * {@link PressroomHandlerInterceptor} runs before the API's own pin check,
 * so it needs the pin to answer a weblog outside it with the 404 any weblog
 * the caller may not see gets; this interface lets it read the pin without
 * a second import from {@code ui.restapi}.
 */
public interface WeblogPinnedCredential {

    /** The handle of the one weblog this credential may act on, or null when it has no limit. */
    String scopeWeblog();
}
