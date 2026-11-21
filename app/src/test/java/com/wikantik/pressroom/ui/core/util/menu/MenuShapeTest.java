package com.wikantik.pressroom.ui.core.util.menu;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import com.wikantik.pressroom.pojos.User;
import com.wikantik.pressroom.pojos.Weblog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The menu beans are built only by {@link MenuHelper}, still read through getters, and say which surface they serve. */
class MenuShapeTest {

    @Test
    void noMenuClassHasAPublicSetterOrMutator() {
        for (Class<?> type : List.of(Menu.class, MenuTab.class, MenuTabItem.class)) {
            for (Method method : type.getDeclaredMethods()) {
                if (Modifier.isPublic(method.getModifiers())) {
                    assertTrue(method.getName().startsWith("get") || method.getName().startsWith("is"),
                            type.getSimpleName() + "." + method.getName() + " must not be public");
                }
            }
        }
    }

    @Test
    void theGettersVelocityReadsStillExist() throws Exception {
        assertEquals(Set.of("getTabs", "getSurface"), publicNames(Menu.class));
        assertEquals(Set.of("getKey", "getAction", "isSelected", "getItems"), publicNames(MenuTab.class));
        assertEquals(Set.of("getKey", "getAction", "isSelected"), publicNames(MenuTabItem.class));
    }

    @Test
    void theEditorMenuIsAuthoringAndTheAdminMenuIsAdmin() throws Exception {
        User user = new User();
        user.setUserName("jane");
        Weblog weblog = new Weblog();
        weblog.setHandle("myblog");
        var userManager = org.mockito.Mockito.mock(com.wikantik.pressroom.business.UserManager.class);
        Menu editor = MenuHelper.buildMenu("editor", MenuHelper.getParsedMenu("editor"), null, user, weblog,
                userManager, p -> false);
        Menu admin = MenuHelper.buildMenu("admin", MenuHelper.getParsedMenu("admin"), null, user, weblog,
                userManager, p -> false);
        assertEquals(Menu.Surface.AUTHORING, editor.getSurface());
        assertEquals(Menu.Surface.ADMIN, admin.getSurface());
    }

    @Test
    void theTabListCannotBeEditedThroughTheGetter() {
        Menu menu = new Menu(Menu.Surface.ADMIN);
        assertThrows(UnsupportedOperationException.class, () -> menu.getTabs().add(new MenuTab("k")));
    }

    private static Set<String> publicNames(Class<?> type) {
        return java.util.Arrays.stream(type.getDeclaredMethods())
                .filter(m -> Modifier.isPublic(m.getModifiers()))
                .map(Method::getName)
                .collect(java.util.stream.Collectors.toSet());
    }
}
