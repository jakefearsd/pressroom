package com.wikantik.pressroom.config.runtime;

import java.util.Optional;
import com.wikantik.pressroom.business.discovery.DiscoverySettings;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PropertyTypeTest {

    @Test
    void declaredNamesMapAndAnythingElseIsAString() {
        assertEquals(PropertyType.BOOLEAN, PropertyType.of("boolean"));
        assertEquals(PropertyType.INTEGER, PropertyType.of("integer"));
        assertEquals(PropertyType.FLOAT, PropertyType.of("float"));
        assertEquals(PropertyType.STRING, PropertyType.of("string"));
        assertEquals(PropertyType.STRING, PropertyType.of("text"));
    }

    @Test
    void booleanAcceptsTrueAndFalseInAnyCase() {
        assertTrue(PropertyType.BOOLEAN.check("x", "TRUE").isEmpty());
        assertTrue(PropertyType.BOOLEAN.check("x", "false").isEmpty());
        assertEquals(Optional.of(PropertyType.UNPARSED), PropertyType.BOOLEAN.check("x", "yes"));
    }

    @Test
    void integerMustParseAndADiscoverySettingMustBeInRange() {
        assertTrue(PropertyType.INTEGER.check("any.other", "5000").isEmpty());
        assertEquals(Optional.of(PropertyType.UNPARSED), PropertyType.INTEGER.check("any.other", "1.5"));
        String name = DiscoverySettings.SHARE_PERCENT;
        assertTrue(PropertyType.INTEGER.check(name, "10").isEmpty());
        assertEquals(Optional.of(new PropertyType.Range(DiscoverySettings.min(name), DiscoverySettings.max(name))),
                PropertyType.INTEGER.check(name, "101"));
    }

    @Test
    void floatMustParseAndStringsAreFree() {
        assertTrue(PropertyType.FLOAT.check("x", "0.5").isEmpty());
        assertEquals(Optional.of(PropertyType.UNPARSED), PropertyType.FLOAT.check("x", "abc"));
        assertTrue(PropertyType.STRING.check("x", "anything").isEmpty());
    }
}
