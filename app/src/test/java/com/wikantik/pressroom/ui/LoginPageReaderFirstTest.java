package com.wikantik.pressroom.ui;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The sign-in page with reader sign-in on (0.18.0, Part D): the provider's
 * button is the first control, the heading names the site, and the password
 * form waits in a collapsed "Author sign-in" section. (The heading is the
 * page title, which {@code LoginControllerTest} pins.) Read as source through
 * {@link LoginJsp} (no unit test can run Jasper); {@code LoginPageIT} drives
 * the same page in a browser, and {@code LoginControllerTest} pins the model.
 */
class LoginPageReaderFirstTest {

    /** A control the keyboard reaches: a link, a button, a summary or a field that is not hidden. */
    private static final Pattern FOCUSABLE = Pattern.compile(
            "<(a|button|summary|select|textarea)\\b[^>]*>|<input\\b(?![^>]*type=\"hidden\")[^>]*>");

    private static String page() {
        return LoginJsp.rendered(true);
    }

    @Test
    void theProvidersButtonIsTheFirstControl() {
        Matcher first = FOCUSABLE.matcher(page());
        assertTrue(first.find(), page());
        assertTrue(first.group().contains("id=\"ssoSignIn\""), "first control: " + first.group());
    }

    @Test
    void theButtonSaysContinueWithTheProviderAndCarriesItsMark() {
        String button = ssoButton();
        assertTrue(button.contains("<spring:message code=\"login.reader.continueWith\" arguments=\"${ssoLabelArgs}\"/>"),
                button);
        assertTrue(button.contains("${ssoMark}"), "the provider's mark (the G only for Google): " + button);
    }

    @Test
    void theButtonIsCountedAsALoginPageSignIn() {
        String button = ssoButton();
        assertTrue(button.contains("data-umami-event=\"sign-in\""), button);
        assertTrue(button.contains("data-umami-event-via=\"login-page\""), button);
    }

    @Test
    void theReturnPathRidesBothWaysIn() {
        String page = page();
        String start = page.substring(page.indexOf("<c:url var=\"ssoStartUrl\""), page.indexOf("</c:url>"));
        assertTrue(start.contains("<c:param name=\"return\" value=\"${returnPath}\"/>"), start);
        assertTrue(ssoButton().contains("href=\"${fn:escapeXml(ssoStartUrl)}\""), ssoButton());
        String form = page.substring(page.indexOf("<form"), page.indexOf("</form>"));
        assertTrue(form.contains("<input type=\"hidden\" name=\"return\" value=\"${fn:escapeXml(returnPath)}\"/>"), form);
    }

    @Test
    void theTaglineFollowsTheHeading() {
        String page = page();
        assertTrue(page.indexOf("code=\"login.reader.tagline\"") >= 0, page);
        assertTrue(page.indexOf("code=\"login.reader.tagline\"") < page.indexOf("id=\"ssoSignIn\""), page);
    }

    @Test
    void thePasswordFormSitsInACollapsedAuthorSection() {
        String page = page();
        int details = page.indexOf("<details id=\"authorSignIn\"");
        assertTrue(details > page.indexOf("id=\"ssoSignIn\""), "the author section follows the button:\n" + page);
        String tag = page.substring(details, page.indexOf('>', page.indexOf("</c:if>", details)) + 1);
        assertTrue(tag.contains("<c:if test=\"${authorSignInOpen}\"> open</c:if>"),
                "open only on an error or ?author: " + tag);
        String section = page.substring(details, page.indexOf("</details>"));
        assertTrue(section.contains("<summary><spring:message code=\"login.reader.authorSignIn\"/></summary>"), section);
        assertTrue(section.contains("id=\"loginForm\""), "the form is inside the section");
        assertTrue(section.contains("loginPage.forgotPassword"), "and so is the authors' lost-password link");
        assertEquals(List.of(), LoginJsp.tokens(section).stream().filter("<h2"::equals).toList(),
                "the summary names the section; no second Sign in heading inside it");
    }

    /** The third way it opens: this browser last signed in with a password. */
    @Test
    void aBrowserThatLastUsedAPasswordFindsTheSectionOpen() {
        String script = page().substring(page().indexOf("<script>"));
        assertTrue(script.contains("localStorage.getItem(\"pressroom.authorSignIn\") === \"1\""), script);
        assertTrue(script.contains("localStorage.setItem(\"pressroom.authorSignIn\", \"1\")"), script);
        int read = script.indexOf("localStorage.getItem");
        int focus = script.indexOf(".focus()");
        assertTrue(read >= 0 && read < focus, "the section opens before the focus is placed");
        assertTrue(script.contains("if (document.getElementById(\"authorSignIn\").open) {"),
                "a closed section's fields take no focus:\n" + script);
    }

    private static String ssoButton() {
        String page = page();
        int at = page.indexOf("id=\"ssoSignIn\"");
        assertTrue(at >= 0, page);
        int start = page.lastIndexOf("<a ", at);
        return page.substring(start, page.indexOf("</a>", at) + 4);
    }
}
