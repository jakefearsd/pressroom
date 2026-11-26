package com.wikantik.pressroom.ui.restapi.v1;

import java.math.BigDecimal;
import jakarta.servlet.http.HttpServletRequest;
import com.wikantik.pressroom.business.UserManager;
import com.wikantik.pressroom.business.URLStrategy;
import com.wikantik.pressroom.business.Weblogger;
import com.wikantik.pressroom.pojos.ApiToken;
import com.wikantik.pressroom.pojos.User;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.pojos.WeblogPermission;
import com.wikantik.pressroom.ui.restapi.auth.ApiPrincipal;
import com.wikantik.pressroom.ui.restapi.dto.AdminDtos;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The weblog read is open to every member and to read-role tokens, so the fields only a weblog admin had
 * before (contact email, creator, business, place, booking link) are withheld below that: an Author session
 * and a READ or POST token see null; a weblog admin (a site admin is one by implication) with a session or
 * an ADMIN token sees them. The fields the frame, rail and entry editor read (name, time zone, locale,
 * comments flag, newsletter list, theme, front page, addresses, custom domain) stay for everyone.
 */
class WeblogReadApiRedactionTest {

    private final User user = new User();

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private AdminDtos.WeblogView read(boolean weblogAdmin, ApiToken.Role tokenRole) throws Exception {
        Weblog weblog = new Weblog();
        weblog.setId("w1");
        weblog.setHandle("blog");
        weblog.setName("Blog");
        weblog.setTimeZone("UTC");
        weblog.setLocale("en");
        weblog.setEmailAddress("owner@example.com");
        weblog.setCreatorUserName("owner");
        weblog.setNewsletterListUuid("list-1");
        weblog.setCustomDomain("blog.example.org");
        weblog.setPlaceType("Hotel");
        weblog.setPlaceLocality("Paris");
        weblog.setPlaceRegion("IDF");
        weblog.setPlaceCountry("FR");
        weblog.setPlaceLat(new BigDecimal("1.5"));
        weblog.setPlaceLng(new BigDecimal("2.5"));
        weblog.setBookingUrl("https://book.example.com");

        Weblogger weblogger = mock(Weblogger.class);
        URLStrategy urls = mock(URLStrategy.class);
        when(urls.getWeblogURL(any(Weblog.class), any(), org.mockito.ArgumentMatchers.eq(true)))
                .thenReturn("https://blog.example.com/blog");
        when(weblogger.getUrlStrategy()).thenReturn(urls);
        UserManager users = mock(UserManager.class);
        when(weblogger.getUserManager()).thenReturn(users);
        when(users.checkPermission(any(WeblogPermission.class), any(User.class))).thenReturn(weblogAdmin);

        if (tokenRole != null) {
            UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken("u", null);
            auth.setDetails(new ApiPrincipal("u", null, tokenRole, "t1"));
            SecurityContextHolder.getContext().setAuthentication(auth);
        }
        WeblogReadApi api = new WeblogReadApi();
        api.weblogger = weblogger;
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getAttribute("actionWeblog")).thenReturn(weblog);
        when(request.getAttribute("authenticatedUser")).thenReturn(user);
        return api.get(request);
    }

    private static void assertRedacted(AdminDtos.WeblogView view) {
        assertNull(view.emailAddress());
        assertNull(view.creatorUserName());
        assertNull(view.businessId());
        assertNull(view.placeType());
        assertNull(view.placeLocality());
        assertNull(view.placeRegion());
        assertNull(view.placeCountry());
        assertNull(view.placeLat());
        assertNull(view.placeLng());
        assertNull(view.bookingUrl());
        // what the admin frame, rail and entry editor read stays
        assertEquals("blog", view.handle());
        assertEquals("Blog", view.name());
        assertEquals("UTC", view.timeZone());
        assertEquals("en", view.locale());
        assertEquals("list-1", view.newsletterListUuid());
        assertEquals("blog.example.org", view.customDomain());
        assertNotNull(view.absoluteUrl());
    }

    private static void assertWhole(AdminDtos.WeblogView view) {
        assertEquals("owner@example.com", view.emailAddress());
        assertEquals("owner", view.creatorUserName());
        assertEquals("Paris", view.placeLocality());
        assertEquals("Hotel", view.placeType());
        assertEquals("IDF", view.placeRegion());
        assertEquals("FR", view.placeCountry());
        assertEquals(1.5, view.placeLat());
        assertEquals(2.5, view.placeLng());
        assertEquals("https://book.example.com", view.bookingUrl());
    }

    @Test
    void anAuthorSessionSeesNoAdminOnlyFields() throws Exception {
        assertRedacted(read(false, null));
    }

    @Test
    void aReadOrPostTokenSeesNoAdminOnlyFieldsEvenOfAnAdminMember() throws Exception {
        assertRedacted(read(false, ApiToken.Role.READ));
        assertRedacted(read(true, ApiToken.Role.READ));
        assertRedacted(read(true, ApiToken.Role.POST));
    }

    @Test
    void aWeblogAdminSessionOrAdminTokenSeesEverything() throws Exception {
        assertWhole(read(true, null));
        assertWhole(read(true, ApiToken.Role.ADMIN));
    }
}
