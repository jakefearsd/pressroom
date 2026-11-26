package com.wikantik.pressroom.ui.restapi.v1;

import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.config.WebloggerConfig;
import com.wikantik.pressroom.ui.restapi.dto.AdminDtos;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * What the settings screen needs said by the server, as the JSP said it: whether readers get the subscribe form
 * (and if not, why), and the "saved, but no wildcard certificate" warning for a custom domain outside the
 * operator's certificate zones. Both ride on the weblog view; both are decided by the classes the JSP used
 * ({@code SubscribeFormStatus}, {@code CustomDomainRules}).
 */
class WeblogViewStatusTest {

    private static Weblog weblog(String list, String domain) {
        Weblog weblog = new Weblog();
        weblog.setId("id");
        weblog.setHandle("h");
        weblog.setNewsletterListUuid(list);
        weblog.setCustomDomain(domain);
        return weblog;
    }

    @Test
    void noListMeansTheFormIsOffForWantOfOne() {
        assertEquals("offNoList", AdminDtos.toView(weblog(null, null), null).subscribeForm());
        assertEquals("offNoList", AdminDtos.toView(weblog("", null), null).subscribeForm());
    }

    @Test
    void aListWithoutANewsletterServiceIsOffNotConfigured() {
        try (MockedStatic<WebloggerConfig> cfg = Mockito.mockStatic(WebloggerConfig.class)) {
            cfg.when(() -> WebloggerConfig.getProperty("newsletter.listmonk.baseurl")).thenReturn("");
            assertEquals("offNotConfigured", AdminDtos.toView(weblog("list-1", null), null).subscribeForm());
        }
    }

    @Test
    void aListAndAServiceMeanTheFormIsOn() {
        try (MockedStatic<WebloggerConfig> cfg = Mockito.mockStatic(WebloggerConfig.class)) {
            cfg.when(() -> WebloggerConfig.getProperty("newsletter.listmonk.baseurl")).thenReturn("http://lists.test");
            assertEquals("on", AdminDtos.toView(weblog("list-1", null), null).subscribeForm());
        }
    }

    @Test
    void aCustomDomainOutsideTheCertificateZonesIsFlaggedAndOneInsideIsNot() {
        try (MockedStatic<WebloggerConfig> cfg = Mockito.mockStatic(WebloggerConfig.class)) {
            cfg.when(() -> WebloggerConfig.getProperty("vhost.cert.zones")).thenReturn("blogs.example.com");
            assertEquals(Boolean.TRUE,
                    AdminDtos.toView(weblog(null, "www.other.example"), null).customDomainOutsideCertZone());
            assertNull(AdminDtos.toView(weblog(null, "one.blogs.example.com"), null).customDomainOutsideCertZone());
            assertNull(AdminDtos.toView(weblog(null, null), null).customDomainOutsideCertZone());
        }
    }

    @Test
    void noConfiguredZonesMeansNoWarning() {
        try (MockedStatic<WebloggerConfig> cfg = Mockito.mockStatic(WebloggerConfig.class)) {
            cfg.when(() -> WebloggerConfig.getProperty("vhost.cert.zones")).thenReturn("");
            assertNull(AdminDtos.toView(weblog(null, "www.other.example"), null).customDomainOutsideCertZone());
        }
    }
}
