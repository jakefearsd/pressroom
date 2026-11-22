package com.wikantik.pressroom.ui.controllers.admin;

import java.util.List;

import com.wikantik.pressroom.business.BusinessManager;
import com.wikantik.pressroom.business.MockWeblogger;
import com.wikantik.pressroom.pojos.Business;
import com.wikantik.pressroom.pojos.User;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * What the Businesses screen says for each rule its save enforces, message for message and
 * control for control (spec 2026-11-20-api-gap-resources-design, Task 5, "Shared rules first").
 *
 * <p><b>Characterisation tests:</b> written before the form's validation moved out of
 * {@code BusinessesController} into the shared {@code BusinessRules}, and seen to pass against
 * the old inline code; they pin that the screen renders identically after the move. The message
 * source echoes each key with its arguments, so a changed key, argument, argument order or
 * escaping shows up here.
 */
class BusinessesControllerRulesTest {

    private MockWeblogger weblogger;
    private BusinessManager manager;
    private BusinessesController controller;
    private ExtendedModelMap model;

    @BeforeEach
    void setUp() {
        weblogger = MockWeblogger.attached();
        manager = weblogger.businessManager();
        ControllerTestFixture.useWeblogger(weblogger.weblogger());
        controller = ControllerTestFixture.withMessages(new BusinessesController());
        model = new ExtendedModelMap();
    }

    @AfterEach
    void tearDown() {
        weblogger.detach();
        ControllerTestFixture.useDefaultWeblogger();
    }

    private static BusinessBean valid() {
        BusinessBean bean = new BusinessBean();
        bean.setName("Casa Azul");
        bean.setBusinessType("LocalBusiness");
        return bean;
    }

    private String save(BusinessBean bean) {
        User user = new User();
        user.setUserName("admin");
        return controller.save(ControllerTestFixture.requestFor(user), model, new RedirectAttributesModelMap(),
                bean);
    }

    /** The save is refused with exactly {@code errors}, marking exactly {@code fields}. */
    private void assertRefused(BusinessBean bean, List<String> errors, List<String> fields) throws Exception {
        assertEquals(".BusinessEdit", save(bean));
        assertEquals(errors, ControllerTestFixture.errors(model));
        assertEquals(fields, ControllerTestFixture.invalidFields(model));
        verify(manager, never()).saveBusiness(any());
    }

    @Test
    void aMissingNameIsRequired() throws Exception {
        BusinessBean bean = valid();
        bean.setName(null);
        assertRefused(bean, List.of("businesses.error.nameRequired"), List.of("bean_name"));
    }

    @Test
    void aBlankNameIsRequired() throws Exception {
        BusinessBean bean = valid();
        bean.setName("  ");
        assertRefused(bean, List.of("businesses.error.nameRequired"), List.of("bean_name"));
    }

    @Test
    void aNameOver255IsTooLongWithItsLabel() throws Exception {
        BusinessBean bean = valid();
        bean.setName("n".repeat(256));
        assertRefused(bean, List.of("businesses.error.tooLong[businesses.name, 255]"), List.of("bean_name"));
    }

    @Test
    void anUnknownTypeIsRefused() throws Exception {
        BusinessBean bean = valid();
        bean.setBusinessType("Hotel");
        assertRefused(bean, List.of("businesses.error.typeInvalid"), List.of("bean_businessType"));
    }

    @Test
    void eachUrlThatIsNotAPublicHttpUrlIsRefusedWithItsLabel() throws Exception {
        BusinessBean bean = valid();
        bean.setWebsiteUrl("javascript:alert(1)");
        bean.setBookingUrl("http://localhost/book");
        bean.setLogoUrl("logo.png");
        assertRefused(bean, List.of("businesses.error.urlInvalid[businesses.websiteUrl]",
                        "businesses.error.urlInvalid[businesses.bookingUrl]",
                        "businesses.error.urlInvalid[businesses.logoUrl]"),
                List.of("bean_websiteUrl", "bean_bookingUrl", "bean_logoUrl"));
    }

    @Test
    void eachUrlOver255IsTooLongWithItsLabel() throws Exception {
        String longUrl = "https://example.com/" + "p".repeat(240);
        BusinessBean bean = valid();
        bean.setWebsiteUrl(longUrl);
        bean.setBookingUrl(longUrl);
        bean.setLogoUrl(longUrl);
        assertRefused(bean, List.of("businesses.error.tooLong[businesses.websiteUrl, 255]",
                        "businesses.error.tooLong[businesses.bookingUrl, 255]",
                        "businesses.error.tooLong[businesses.logoUrl, 255]"),
                List.of("bean_websiteUrl", "bean_bookingUrl", "bean_logoUrl"));
    }

    @Test
    void aTelephoneOffThePatternIsRefused() throws Exception {
        BusinessBean bean = valid();
        bean.setTelephone("call me");
        assertRefused(bean, List.of("businesses.error.telephoneInvalid"), List.of("bean_telephone"));
    }

    @Test
    void anInvalidEmailIsRefused() throws Exception {
        BusinessBean bean = valid();
        bean.setEmail("nope");
        assertRefused(bean, List.of("businesses.error.emailInvalid"), List.of("bean_email"));
    }

    @Test
    void aValidEmailOver255IsTooLongWithItsLabel() throws Exception {
        BusinessBean bean = valid();
        bean.setEmail(overlongEmail());
        assertRefused(bean, List.of("businesses.error.tooLong[businesses.email, 255]"), List.of("bean_email"));
    }

    @Test
    void anAreaServedOver255IsTooLongWithItsLabel() throws Exception {
        BusinessBean bean = valid();
        bean.setAreaServed("a".repeat(256));
        assertRefused(bean, List.of("businesses.error.tooLong[businesses.areaServed, 255]"),
                List.of("bean_areaServed"));
    }

    @Test
    void moreThanTenSameAsLinksAreRefusedWithTheCap() throws Exception {
        StringBuilder lines = new StringBuilder();
        for (int i = 0; i < 11; i++) {
            lines.append("https://p").append(i).append(".example.com\n");
        }
        BusinessBean bean = valid();
        bean.setSameAs(lines.toString());
        assertRefused(bean, List.of("businesses.error.sameAsTooMany[10]"), List.of("bean_sameAs"));
    }

    @Test
    void eachBadSameAsLineIsRefusedEchoedEscaped() throws Exception {
        String overlong = "https://example.com/" + "q".repeat(240);
        BusinessBean bean = valid();
        bean.setSameAs("https://ok.example.com\njavascript:<b>x</b>\n" + overlong);
        assertRefused(bean, List.of("businesses.error.sameAsInvalid[javascript:&lt;b&gt;x&lt;/b&gt;]",
                        "businesses.error.sameAsInvalid[" + overlong + "]"),
                List.of("bean_sameAs"));
    }

    /** Every rule a request breaks is reported together, in the form's order. */
    @Test
    void everyBrokenRuleIsReportedTogetherInFormOrder() throws Exception {
        StringBuilder lines = new StringBuilder();
        for (int i = 0; i < 11; i++) {
            lines.append("ftp://p").append(i).append(".example.com\n");
        }
        BusinessBean bean = new BusinessBean();
        bean.setName("");
        bean.setBusinessType("Hotel");
        bean.setWebsiteUrl("nope");
        bean.setBookingUrl("nope");
        bean.setLogoUrl("nope");
        bean.setTelephone("x");
        bean.setEmail("nope");
        bean.setAreaServed("a".repeat(256));
        bean.setSameAs(lines.toString());

        save(bean);

        List<String> expected = new java.util.ArrayList<>(List.of("businesses.error.nameRequired",
                "businesses.error.typeInvalid", "businesses.error.urlInvalid[businesses.websiteUrl]",
                "businesses.error.urlInvalid[businesses.bookingUrl]", "businesses.error.urlInvalid[businesses.logoUrl]",
                "businesses.error.telephoneInvalid", "businesses.error.emailInvalid",
                "businesses.error.tooLong[businesses.areaServed, 255]", "businesses.error.sameAsTooMany[10]"));
        for (int i = 0; i < 11; i++) {
            expected.add("businesses.error.sameAsInvalid[ftp://p" + i + ".example.com]");
        }
        assertEquals(expected, ControllerTestFixture.errors(model));
        assertEquals(List.of("bean_name", "bean_businessType", "bean_websiteUrl", "bean_bookingUrl", "bean_logoUrl",
                "bean_telephone", "bean_email", "bean_areaServed", "bean_sameAs"),
                ControllerTestFixture.invalidFields(model));
        verify(manager, never()).saveBusiness(any());
    }

    /** A passing save stores each value trimmed, blanks as null, and the sameAs lines joined by a newline. */
    @Test
    void aPassingSaveStoresTrimmedValuesAndBlanksAsNull() throws Exception {
        BusinessBean bean = new BusinessBean();
        bean.setName("  Casa  ");
        bean.setBusinessType("ProfessionalService");
        bean.setWebsiteUrl(" https://casa.example.com ");
        bean.setBookingUrl(" ");
        bean.setTelephone(" +1 555 0100 ");
        bean.setEmail(" hi@casa.example.com ");
        bean.setLogoUrl("");
        bean.setSameAs("\r\n https://a.example.com \n\n https://b.example.com\r\n");
        bean.setAreaServed("  Azores ");
        bean.setDescription("  Rooms.  ");

        assertEquals("redirect:/pressroom-ui/admin/businesses.rol", save(bean));

        ArgumentCaptor<Business> saved = ArgumentCaptor.forClass(Business.class);
        verify(manager).saveBusiness(saved.capture());
        Business b = saved.getValue();
        assertEquals("Casa", b.getName());
        assertEquals(Business.BusinessType.ProfessionalService, b.getBusinessType());
        assertEquals("https://casa.example.com", b.getWebsiteUrl());
        assertNull(b.getBookingUrl());
        assertEquals("+1 555 0100", b.getTelephone());
        assertEquals("hi@casa.example.com", b.getEmail());
        assertNull(b.getLogoUrl());
        assertEquals("https://a.example.com\nhttps://b.example.com", b.getSameAs());
        assertEquals("Azores", b.getAreaServed());
        assertEquals("Rooms.", b.getDescription());
    }

    /** A syntactically valid address longer than the column: 64-character local part, long domain. */
    static String overlongEmail() {
        String label = "d".repeat(60);
        return "l".repeat(64) + "@" + label + "." + label + "." + label + "." + label + ".com";
    }
}
