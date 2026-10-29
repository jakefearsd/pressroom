package org.apache.roller.weblogger.ui.restapiv2;

/**
 * Lives in a package whose name merely starts with the API package's text
 * ({@code ui.restapi} + {@code v2}); RollerHandlerInterceptorApiPackageTest
 * uses it as a bean type that must NOT be classified as an API handler.
 */
public final class SiblingOfRestapi {
    private SiblingOfRestapi() {
    }
}
