package com.wikantik.pressroom.business;

import com.wikantik.pressroom.pojos.Weblog;

/**
 * What blog settings says about a blog's reader subscribe form, beside the
 * newsletter list field. "On" is not a second copy of the rule that renders
 * the form: it is {@link Weblog#isSubscribeFormShown()}, the predicate the
 * showSubscribeForm macro asks, together with the site having a newsletter
 * service at all ({@link ListmonkClient#isSiteConfigured()}), without which
 * {@code NewsletterController} refuses every sign-up.
 */
public enum SubscribeFormStatus {

    /** The form is on the blog's pages and sign-ups are forwarded. */
    ON("on"),
    /** The blog has no newsletter list, so no form is rendered. */
    OFF_NO_LIST("offNoList"),
    /** The blog has a list, but the site has no newsletter service: the form cannot take a sign-up. */
    OFF_NOT_CONFIGURED("offNotConfigured");

    private final String key;

    SubscribeFormStatus(String key) {
        this.key = key;
    }

    /** The last segment of this status's message key, {@code websiteSettings.subscribeForm.<key>}. */
    public String getKey() {
        return key;
    }

    public static SubscribeFormStatus of(Weblog weblog, boolean newsletterServiceConfigured) {
        if (!weblog.isSubscribeFormShown()) {
            return OFF_NO_LIST;
        }
        return newsletterServiceConfigured ? ON : OFF_NOT_CONFIGURED;
    }
}
