package com.wikantik.pressroom.ui.restapi;

import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * A keyed problem's {@code detail} follows the caller's {@code Accept-Language}
 * (spec 2026-11-20-api-contract-design, Task 1, criterion 6). No translation
 * of an {@code api.problem.*} key ships, so the French and German here come
 * from a test-only bundle placed before {@code ApplicationResources}.
 */
class ProblemLocalisationTest {

    private static final String ENGLISH_500 = "The request could not be completed.";
    private static final String FRENCH_500 = "La requête n'a pas pu aboutir.";

    /** A handler in the restapi package, so ApiExceptionHandler advises it. */
    @RestController
    static class ProbeApi {
        @GetMapping("/v1/l10n/boom")
        Map<String, String> boom() {
            throw new IllegalStateException("x");
        }

        @GetMapping("/v1/l10n/whole")
        Map<String, String> whole(@RequestParam("limit") int limit) {
            return Map.of();
        }

        @GetMapping("/v1/l10n/literal")
        Map<String, String> literal() {
            throw ApiException.notFound("No such entry.");
        }

        @GetMapping("/v1/l10n/fields")
        Map<String, String> fields() {
            FieldErrors errors = new FieldErrors();
            errors.add("limit", ProblemCode.PARAMETER_NOT_WHOLE_NUMBER, "limit");
            errors.add("size", ProblemCode.REQUEST_TOO_LARGE, "1000000");
            errors.throwIfAny();
            return Map.of();
        }
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new ProbeApi())
            .setControllerAdvice(new ApiExceptionHandler(testMessages())).build();

    private static ResourceBundleMessageSource testMessages() {
        ResourceBundleMessageSource source = new ResourceBundleMessageSource();
        // The test bundle sits beside this class; derived, not spelled out, so the
        // qualified-name scan (StringClassReferenceTest) has no path to resolve.
        String testBundle = ProblemLocalisationTest.class.getPackageName().replace('.', '/') + "/ProblemL10nTest";
        source.setBasenames(testBundle, "ApplicationResources");
        source.setDefaultEncoding("UTF-8");
        return source;
    }

    private JsonNode problem(MockHttpServletRequestBuilder request, String acceptLanguage) throws Exception {
        if (acceptLanguage != null) {
            request.header("Accept-Language", acceptLanguage);
        }
        return JSON.readTree(mvc.perform(request).andReturn().getResponse().getContentAsString());
    }

    private String detail(String path, String acceptLanguage) throws Exception {
        return problem(get(path), acceptLanguage).get("detail").asString();
    }

    @Test
    void aRegionalFrenchCallerReadsTheFrenchDetail() throws Exception {
        assertEquals(FRENCH_500, detail("/v1/l10n/boom", "fr-CA,en;q=0.5"));
    }

    @Test
    void aFrenchFieldMessageFillsItsPlaceholder() throws Exception {
        JsonNode body = problem(get("/v1/l10n/whole").param("limit", "x"), "fr");
        String french = "limit a une valeur invalide ; ce doit être un nombre entier.";
        assertEquals(french, body.get("detail").asString());
        assertEquals(french, body.get("errors").get(0).get("message").asString());
        assertEquals("parameter.notWholeNumber", body.get("code").asString());
    }

    /** Several keyed field errors: each message localised, the detail their join; a number is never grouped. */
    @Test
    void aMultiFieldDetailJoinsTheLocalisedMessages() throws Exception {
        JsonNode body = problem(get("/v1/l10n/fields"), "fr");
        assertEquals("limit a une valeur invalide ; ce doit être un nombre entier. "
                + "Le corps de la requête dépasse 1000000 octets.", body.get("detail").asString());
        assertEquals("request.invalidFields", body.get("code").asString());

        JsonNode english = problem(get("/v1/l10n/fields"), null);
        assertEquals("limit has an invalid value; it must be a whole number. "
                + "The request body is over 1000000 bytes.", english.get("detail").asString());
    }

    @Test
    void anUnmatchedLanguageOrAWildcardReadsEnglish() throws Exception {
        assertEquals(ENGLISH_500, detail("/v1/l10n/boom", "xx, *"));
        assertEquals(ENGLISH_500, detail("/v1/l10n/boom", "en-US,fr;q=0.5"));
        assertEquals(ENGLISH_500, detail("/v1/l10n/boom", "fr;q=0, en"));
        assertEquals(ENGLISH_500, detail("/v1/l10n/boom", "not a language range;;q=x"));
    }

    @Test
    void anUnkeyedLiteralDetailStaysEnglishWhateverTheHeaderSays() throws Exception {
        assertEquals("No such entry.", detail("/v1/l10n/literal", "fr"));
    }

    /**
     * The JVM default locale never chooses the language. Tomcat answers
     * {@code getLocale()} with it when the header is absent, and a resource
     * bundle lookup for a locale with no bundle of its own falls back to it;
     * the test bundle has German, so either mistake would show here.
     */
    @Test
    void theJvmDefaultLocaleNeverChoosesTheLanguage() throws Exception {
        Locale before = Locale.getDefault();
        Locale.setDefault(Locale.GERMAN);
        try {
            ResourceBundleMessageSource fresh = testMessages();
            MockMvc german = MockMvcBuilders.standaloneSetup(new ProbeApi())
                    .setControllerAdvice(new ApiExceptionHandler(fresh)).build();
            assertEquals(ENGLISH_500, JSON.readTree(german.perform(get("/v1/l10n/boom"))
                    .andReturn().getResponse().getContentAsString()).get("detail").asString());
            assertEquals(ENGLISH_500, JSON.readTree(german.perform(get("/v1/l10n/boom").header("Accept-Language", "en"))
                    .andReturn().getResponse().getContentAsString()).get("detail").asString());
        } finally {
            Locale.setDefault(before);
        }
    }

    /** A source without the key (only a test can hand one over) renders the English: the text is display. */
    @Test
    void aSourceWithoutTheKeyRendersTheEnglish() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/x");
        request.addHeader("Accept-Language", "fr");
        ApiExceptionHandler bare = new ApiExceptionHandler(new StaticMessageSource());

        assertEquals(ENGLISH_500, bare.handleUnexpected(new IllegalStateException("x"), request).getBody().detail());
    }

    @Test
    void theHeaderIsMatchedAgainstTheShippedBundlesInQOrder() {
        assertEquals(Locale.ROOT, ProblemMessages.localeFor(null));
        assertEquals(Locale.ROOT, ProblemMessages.localeFor(" "));
        assertEquals(Locale.ROOT, ProblemMessages.localeFor("en-GB"));
        assertEquals(Locale.FRENCH, ProblemMessages.localeFor("fr-CA,en;q=0.5"));
        assertEquals(Locale.JAPANESE, ProblemMessages.localeFor("de;q=0.1, ja;q=0.9"));
        assertEquals(Locale.SIMPLIFIED_CHINESE, ProblemMessages.localeFor("zh"));
        assertEquals(Locale.SIMPLIFIED_CHINESE, ProblemMessages.localeFor("zh-CN"));
        assertEquals(Locale.ROOT, ProblemMessages.localeFor("zh-TW"));
        assertEquals(Locale.ROOT, ProblemMessages.localeFor("xx, *"));
        assertEquals(Locale.forLanguageTag("ko"), ProblemMessages.localeFor("xx, ko-KR"));
    }
}
