package com.wikantik.pressroom.ui.restapi.v1;

import java.util.Set;
import com.wikantik.pressroom.business.URLStrategy;
import com.wikantik.pressroom.config.WebloggerRuntimeConfig;
import com.wikantik.pressroom.pojos.Weblog;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/** The hosts an import treats as the site's own: both addresses, lower case, and never a guess from junk. */
class EntryImportsHostsTest {

    private static Set<String> hosts(String weblogUrl, String siteUrl) {
        Weblog weblog = new Weblog();
        URLStrategy urls = mock(URLStrategy.class);
        when(urls.getWeblogURL(any(), any(), org.mockito.ArgumentMatchers.anyBoolean())).thenReturn(weblogUrl);
        try (MockedStatic<WebloggerRuntimeConfig> runtime = mockStatic(WebloggerRuntimeConfig.class)) {
            runtime.when(WebloggerRuntimeConfig::getAbsoluteContextURL).thenReturn(siteUrl);
            return EntryImports.ownHosts(urls, weblog);
        }
    }

    @Test
    void theWeblogsAndTheSitesHostsAreBothOursInLowerCase() {
        assertEquals(Set.of("blog.example.com", "site.example.org"),
                hosts(" https://Blog.Example.com/myblog/ ", "https://SITE.example.org/pressroom"));
    }

    @Test
    void anAddressThatNamesNoHostOrCannotBeParsedAddsNone() {
        assertEquals(Set.of("blog.example.com"), hosts("https://blog.example.com/", "ht!tp://%zz bad"));
        assertEquals(Set.of(), hosts(null, "/relative/only"));
    }
}
