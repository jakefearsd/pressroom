package com.wikantik.pressroom.ui.restapi;

import com.wikantik.pressroom.boot.ServletRegistrationConfig;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ApiMountingTest {

    /**
     * /api/* is a servlet-spec PREFIX mapping, so the container strips the
     * prefix from the Spring MVC lookup path. Controllers are therefore
     * mapped at "/v1/..." and serve "/api/v1/...". This test exists because
     * writing the full path in @RequestMapping is the single most likely
     * mistake in this wave and produces a 404 with no other symptom.
     */
    @Test
    void apiPrefixIsRegisteredOnTheDispatcher() {
        assertTrue(ServletRegistrationConfig.API_URL_PATTERNS.contains("/api/*"),
                "the dispatcher must carry the /api/* prefix mapping");
    }

    @Test
    void metaApiIsMappedRelativeToTheStrippedPrefix() {
        var mapping = com.wikantik.pressroom.ui.restapi.v1.MetaApi.class
                .getAnnotation(org.springframework.web.bind.annotation.RequestMapping.class);
        assertNotNull(mapping, "MetaApi must carry @RequestMapping");
        assertEquals("/v1", mapping.value()[0],
                "must be /v1, not /api/v1 -- the container already stripped /api");
    }
}
