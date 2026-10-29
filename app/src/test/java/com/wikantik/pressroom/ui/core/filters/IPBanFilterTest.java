/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  The ASF licenses this file to You
 * under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.  For additional information regarding
 * copyright in this work, please see the NOTICE file in the top level
 * directory of this distribution.
 */
package com.wikantik.pressroom.ui.core.filters;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Supplier;

import jakarta.servlet.http.HttpServletResponse;

import com.wikantik.pressroom.util.IPBanList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * {@link IPBanFilter} against a real {@link IPBanList} read from a ban file:
 * a banned client address is answered 404 and never reaches the chain;
 * anyone else passes through untouched.
 *
 * <p>Characterisation tests: written against the existing behaviour and
 * expected to pass immediately.
 *
 * <p>The filter reads the process-wide {@code IPBanList.getInstance()},
 * built once from {@code ipbanlist.file} (unset in tests, so it bans
 * nobody). Each test swaps in an instance over its own ban file through
 * {@code IPBanList.newForTest} and puts the original back afterwards.
 *
 * <p>Note: nothing registers this filter today. {@code ServletRegistrationConfig}
 * mapped it only onto the comment endpoints, and that registration went with
 * the comment subsystem (W1); these tests pin what the class does, not that
 * any request passes through it.
 */
class IPBanFilterTest {

    private static final String BANNED = "203.0.113.66";
    private static final String ALLOWED = "198.51.100.7";

    @TempDir
    Path tempDir;

    private Path banFile;
    private IPBanList originalInstance;

    @BeforeEach
    void installBanList() throws Exception {
        banFile = tempDir.resolve("banned-ips.txt");
        Files.writeString(banFile, BANNED + "\n", StandardCharsets.UTF_8);

        originalInstance = IPBanList.getInstance();
        String path = banFile.toString();
        setInstance(newBanList(() -> path));
    }

    @AfterEach
    void restoreBanList() throws Exception {
        setInstance(originalInstance);
    }

    @Test
    void aBannedAddressIsAnswered404AndNeverReachesTheChain() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        new IPBanFilter().doFilter(requestFrom(BANNED), response, chain);

        assertEquals(HttpServletResponse.SC_NOT_FOUND, response.getStatus());
        assertNull(chain.getRequest(), "a banned client must not reach the rest of the chain");
    }

    @Test
    void anAddressNotOnTheListPassesThroughToTheChainUntouched() throws Exception {
        MockHttpServletRequest request = requestFrom(ALLOWED);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        new IPBanFilter().doFilter(request, response, chain);

        assertSame(request, chain.getRequest(), "an allowed client must reach the chain");
        assertSame(response, chain.getResponse());
        assertEquals(HttpServletResponse.SC_OK, response.getStatus());
    }

    /**
     * The ban file is re-read when it changes on disk, so an address added
     * while the application runs is refused on its next request without a
     * restart.
     */
    @Test
    void anAddressAddedToTheBanFileWhileRunningIsRefusedOnItsNextRequest() throws Exception {
        MockFilterChain firstChain = new MockFilterChain();
        new IPBanFilter().doFilter(requestFrom(ALLOWED), new MockHttpServletResponse(), firstChain);
        assertNotNull(firstChain.getRequest(), "not yet banned: passes through");

        Files.writeString(banFile, BANNED + "\n" + ALLOWED + "\n", StandardCharsets.UTF_8);
        // File timestamps can be coarse; move the mtime visibly forward so
        // the change is detected however fast this runs.
        File file = banFile.toFile();
        file.setLastModified(file.lastModified() + 10_000);

        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain secondChain = new MockFilterChain();
        new IPBanFilter().doFilter(requestFrom(ALLOWED), response, secondChain);

        assertEquals(HttpServletResponse.SC_NOT_FOUND, response.getStatus());
        assertNull(secondChain.getRequest());
    }

    // ---------------------------------------------------------------- helpers

    private static MockHttpServletRequest requestFrom(String remoteAddr) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/myblog/entry/x");
        request.setRemoteAddr(remoteAddr);
        return request;
    }

    /** {@code IPBanList.newForTest} is package-private to {@code util}. */
    private static IPBanList newBanList(Supplier<String> path) throws Exception {
        Method factory = IPBanList.class.getDeclaredMethod("newForTest", Supplier.class);
        factory.setAccessible(true);
        return (IPBanList) factory.invoke(null, path);
    }

    private static void setInstance(IPBanList list) throws Exception {
        Field instance = IPBanList.class.getDeclaredField("instance");
        instance.setAccessible(true);
        instance.set(null, list);
    }
}
