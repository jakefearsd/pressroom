package com.wikantik.pressroom.business;

import com.wikantik.pressroom.WebloggerException;

/**
 * A site page's slug is also a first path segment, so it may not equal a blog
 * handle or one of the application's protected paths. Carries the editor's
 * message key and the name that clashes, so the controller can say which.
 */
public class SiteSlugClashException extends WebloggerException {

    private static final long serialVersionUID = 1L;

    private final String messageKey;
    private final String argument;

    public SiteSlugClashException(String messageKey, String argument) {
        super("page slug clashes (" + messageKey + "): " + argument);
        this.messageKey = messageKey;
        this.argument = argument;
    }

    /** The ApplicationResources key naming the clash. */
    public String getMessageKey() {
        return messageKey;
    }

    /** The blog handle or protected path the slug collides with, as stored. */
    public String getArgument() {
        return argument;
    }
}
