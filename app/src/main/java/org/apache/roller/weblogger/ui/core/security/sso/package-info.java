/**
 * Reader sign-in. Readers sign in with OpenID Connect through Spring Security's
 * {@code oauth2Login()}; Wikantik's binding rules are ported (see
 * {@code docs/superpowers/specs/2026-10-11-reader-sign-in-design.md}). Everything
 * that ends a request inside the security chain releases the persistence session.
 */
package org.apache.roller.weblogger.ui.core.security.sso;
