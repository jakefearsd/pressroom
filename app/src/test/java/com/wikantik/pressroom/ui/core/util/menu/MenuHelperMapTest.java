package com.wikantik.pressroom.ui.core.util.menu;

import java.lang.reflect.Field;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** The parsed menus are read-only once the class is initialised. */
class MenuHelperMapTest {

    @Test
    void theParsedMenuMapCannotBeWrittenTo() throws Exception {
        Field field = MenuHelper.class.getDeclaredField("menus");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, ParsedMenu> menus = (Map<String, ParsedMenu>) field.get(null);
        assertNotNull(menus.get("editor"));
        assertNotNull(menus.get("admin"));
        assertThrows(UnsupportedOperationException.class, () -> menus.put("x", menus.get("admin")));
    }
}
