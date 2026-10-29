package com.wikantik.pressroom.ui.core.security.sso;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class SsoSettingsTest {

    @ParameterizedTest
    @CsvSource(value = {"id,secret,true", "'',secret,false", "id,'',false", "'  ',secret,false", "id,'  ',false"})
    void enabledNeedsBothIdAndSecret(String id, String secret, boolean expected) {
        assertEquals(expected, new SsoSettings(id, secret, SsoSettings.GOOGLE_DISCOVERY, "Google").enabled());
    }

    @Test
    void toStringNeverCarriesTheSecret() {
        String s = new SsoSettings("id", "s3cr3t-value", SsoSettings.GOOGLE_DISCOVERY, "Google").toString();
        assertFalse(s.contains("s3cr3t-value"));
        assertTrue(s.contains("clientSecret=<set>"));
        assertTrue(new SsoSettings("id", "", SsoSettings.GOOGLE_DISCOVERY, "Google")
                .toString().contains("clientSecret=<unset>"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "https://accounts.google.com/.well-known/openid-configuration",
        "https://accounts.google.com/",
        "https://accounts.google.com"})
    void issuerOrDiscoveryUriBothWork(String configured) {
        assertEquals("https://accounts.google.com",
                new SsoSettings("id", "s", configured, "Google").issuer());
    }

    @Test
    void defaultsApplyWhenUnset() {
        SsoSettings s = SsoSettings.fromConfig();
        assertEquals(SsoSettings.GOOGLE_DISCOVERY, s.discoveryUri());
        assertEquals("Google", s.providerLabel());
        assertFalse(s.enabled());
    }

    @Test
    void nullsBecomeEmptyAndValuesAreTrimmed() {
        SsoSettings s = new SsoSettings(null, null, null, null);
        assertEquals("", s.clientId());
        assertEquals("", s.clientSecret());
        assertEquals(SsoSettings.GOOGLE_DISCOVERY, s.discoveryUri());
        assertEquals("Google", s.providerLabel());
        assertEquals("id", new SsoSettings(" id ", "s", " https://x.example/ ", " L ").clientId());
        assertEquals("https://x.example", new SsoSettings("i", "s", " https://x.example/ ", "L").issuer());
    }
}
