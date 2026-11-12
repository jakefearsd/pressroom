package com.wikantik.pressroom.business;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.pojos.wrapper.WeblogWrapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 0.9.1 criterion 17: what blog settings says about the reader subscribe
 * form, and that it is decided by the same predicate that renders the form
 * ({@link Weblog#isSubscribeFormShown()}), not by a second copy of the rule.
 */
class SubscribeFormStatusTest {

    private static final String UUID = "3fa85f64-5717-4562-b3fc-2c963f66afa6";

    private static Weblog weblog(String listUuid) {
        Weblog weblog = new Weblog();
        weblog.setNewsletterListUuid(listUuid);
        return weblog;
    }

    @Test
    void offWithoutAListWhateverTheSiteHas() {
        assertEquals(SubscribeFormStatus.OFF_NO_LIST, SubscribeFormStatus.of(weblog(null), true));
        assertEquals(SubscribeFormStatus.OFF_NO_LIST, SubscribeFormStatus.of(weblog(""), true));
        assertEquals(SubscribeFormStatus.OFF_NO_LIST, SubscribeFormStatus.of(weblog(null), false));
    }

    @Test
    void offWhenTheSiteHasNoNewsletterService() {
        assertEquals(SubscribeFormStatus.OFF_NOT_CONFIGURED, SubscribeFormStatus.of(weblog(UUID), false));
    }

    @Test
    void onWithAListAndAService() {
        assertEquals(SubscribeFormStatus.ON, SubscribeFormStatus.of(weblog(UUID), true));
    }

    @Test
    void eachStatusNamesItsMessage() {
        assertEquals("on", SubscribeFormStatus.ON.getKey());
        assertEquals("offNoList", SubscribeFormStatus.OFF_NO_LIST.getKey());
        assertEquals("offNotConfigured", SubscribeFormStatus.OFF_NOT_CONFIGURED.getKey());
    }

    @Test
    void theFormIsShownExactlyWhenTheBlogHasAList() {
        assertFalse(weblog(null).isSubscribeFormShown());
        assertFalse(weblog("").isSubscribeFormShown());
        assertTrue(weblog(UUID).isSubscribeFormShown());
        assertTrue(WeblogWrapper.wrap(weblog(UUID), null, null).isSubscribeFormShown());
        assertFalse(WeblogWrapper.wrap(weblog(null), null, null).isSubscribeFormShown());
    }

    /** The site half is the base URL alone, the condition NewsletterController answers 503 on. */
    @Test
    void theServiceIsConfiguredExactlyWhenAClientBuiltFromTheSameUrlIs() {
        for (String baseUrl : new String[] {null, "", "   ", "http://listmonk:9000", " http://listmonk:9000/ "}) {
            assertEquals(!new ListmonkClient(baseUrl, null).isUnconfigured(),
                    ListmonkClient.isBaseUrlConfigured(baseUrl), "base url '" + baseUrl + "'");
        }
    }

    /** The macro asks the predicate; it carries no copy of the rule. */
    @Test
    void theSubscribeFormMacroAsksThePredicate() throws IOException {
        String vm = Files.readString(Path.of("src/main/webapp/WEB-INF/velocity/weblog.vm"), StandardCharsets.UTF_8);
        Matcher macro = Pattern.compile("(?s)#macro\\(showSubscribeForm \\$weblog \\$prompt\\)\n(.*?)\n#end\n#end")
                .matcher(vm);
        assertTrue(macro.find(), "showSubscribeForm macro not found");
        assertTrue(macro.group(1).startsWith("#if($weblog.subscribeFormShown)\n"), macro.group(1));
    }
}
