package com.wikantik.pressroom.ui.rendering;

import com.wikantik.pressroom.TestUtils;
import com.wikantik.pressroom.business.Weblogger;
import com.wikantik.pressroom.business.WebloggerProvider;
import com.wikantik.pressroom.config.WebloggerConfig;
import com.wikantik.pressroom.pojos.User;
import com.wikantik.pressroom.pojos.Weblog;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * An operator can reserve more paths than the shipped list
 * ({@code rendering.weblogMapper.userProtectedUrls}). A reserved word must
 * never be taken for a weblog handle, on the site host or on a custom domain,
 * and a blank setting must reserve nothing.
 */
class WeblogRequestMapperProtectedUrlsTest {

    private User user;
    private Weblog weblog;

    @BeforeEach
    void setUp() throws Exception {
        TestUtils.setupWeblogger();
        user = TestUtils.setupUser("protecteduser");
        weblog = TestUtils.setupWeblog("protectedblog", user);
        TestUtils.endSession(true);
    }

    @AfterEach
    void tearDown() throws Exception {
        TestUtils.teardownWeblog(weblog.getId());
        TestUtils.teardownUser(user.getUserName());
        TestUtils.endSession(true);
        TestUtils.weblogger().getVirtualHostRegistry().invalidate();
    }

    private static WeblogRequestMapper mapperWithUserProtected(String list) {
        Weblogger tier = TestUtils.weblogger();
        WebloggerProvider provider = mock(WebloggerProvider.class);
        when(provider.isBootstrapped()).thenReturn(true);
        when(provider.getWeblogger()).thenReturn(tier);
        try (MockedStatic<WebloggerConfig> config = mockStatic(WebloggerConfig.class, CALLS_REAL_METHODS)) {
            config.when(() -> WebloggerConfig.getProperty("rendering.weblogMapper.userProtectedUrls"))
                    .thenReturn(list);
            return new WeblogRequestMapper(provider, tier);
        }
    }

    private static MockHttpServletRequest get(String uri) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/roller" + uri);
        request.setContextPath("/roller");
        return request;
    }

    @Test
    void aPathTheOperatorReservedIsNotMistakenForTheWeblogOfTheSameName() throws Exception {
        WeblogRequestMapper mapper = mapperWithUserProtected("protectedblog,other");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertFalse(mapper.handleRequest(get("/protectedblog/"), response));
        assertNull(response.getForwardedUrl());
    }

    @Test
    void withNothingReservedTheSameWeblogIsServed() throws Exception {
        for (String blank : new String[] {null, "", "   "}) {
            WeblogRequestMapper mapper = mapperWithUserProtected(blank);
            MockHttpServletResponse response = new MockHttpServletResponse();

            assertTrue(mapper.handleRequest(get("/protectedblog/"), response), String.valueOf(blank));
            assertEquals("/pressroom-ui/rendering/page/protectedblog", response.getForwardedUrl());
        }
    }

    @Test
    void anOperatorReservedPathIsAlsoDeclinedOnACustomDomain() throws Exception {
        Weblog stored = TestUtils.weblogger().getWeblogManager().getWeblogByHandle("protectedblog");
        stored.setCustomDomain("protected.example.com");
        TestUtils.weblogger().getWeblogManager().saveWeblog(stored);
        TestUtils.endSession(true);
        TestUtils.weblogger().getVirtualHostRegistry().invalidate();
        WeblogRequestMapper mapper = mapperWithUserProtected("reservedword");

        MockHttpServletRequest reserved = new MockHttpServletRequest("GET", "/reservedword/x");
        reserved.addHeader("Host", "protected.example.com");
        reserved.setServerName("protected.example.com");
        MockHttpServletResponse declined = new MockHttpServletResponse();
        assertFalse(mapper.handleRequest(reserved, declined));

        MockHttpServletRequest ordinary = new MockHttpServletRequest("GET", "/entry/a-post");
        ordinary.addHeader("Host", "protected.example.com");
        ordinary.setServerName("protected.example.com");
        MockHttpServletResponse served = new MockHttpServletResponse();
        assertTrue(mapper.handleRequest(ordinary, served));
        assertEquals("/pressroom-ui/rendering/page/protectedblog/entry/a-post", served.getForwardedUrl());
    }
}
