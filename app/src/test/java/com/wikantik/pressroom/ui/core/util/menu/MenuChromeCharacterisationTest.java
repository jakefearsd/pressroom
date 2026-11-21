package com.wikantik.pressroom.ui.core.util.menu;

import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.apache.velocity.VelocityContext;
import org.apache.velocity.app.VelocityEngine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.wikantik.pressroom.business.UserManager;
import com.wikantik.pressroom.pojos.GlobalPermission;
import com.wikantik.pressroom.pojos.PressroomPermission;
import com.wikantik.pressroom.pojos.User;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.pojos.WeblogTheme;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Characterisation tests (written against the code as it stood before Task 6
 * of the JSP-rules-and-view-models spec, and passing on it): the structure
 * {@code buildMenu} produces for an author, an {@code edit_draft} member, an
 * admin, the site space and a custom-theme weblog, and the exact bytes
 * {@code weblog.vm}'s {@code showAuthorMenu} / {@code showAdminMenu} macros
 * render from them through the real Velocity engine. Velocity resolves
 * getters and prints an unresolved reference as literal text, so a getter
 * rename or a record would show up here and nowhere else.
 */
class MenuChromeCharacterisationTest {

    private static final Path WEBLOG_VM = Path.of("src/main/webapp/WEB-INF/velocity/weblog.vm");

    private final Set<String> globalActions = new HashSet<>();
    private final Set<String> weblogActions = new HashSet<>();
    private final Set<String> properties = Set.of("themes.customtheme.allowed", "groupblogging.enabled");
    private User user;
    private Weblog weblog;
    private UserManager userManager;

    @BeforeEach
    void setUp() throws Exception {
        user = new User();
        user.setUserName("jane");
        weblog = new Weblog();
        weblog.setHandle("myblog");
        weblog.setEditorTheme("journal");
        userManager = mock(UserManager.class);
        when(userManager.checkPermission(any(), eq(user))).thenAnswer(invocation -> {
            PressroomPermission requested = invocation.getArgument(0);
            Set<String> held = requested instanceof GlobalPermission ? globalActions : weblogActions;
            return held.containsAll(requested.getActionsAsList());
        });
    }

    private void author() {
        globalActions.addAll(List.of("login", "weblog"));
        weblogActions.addAll(List.of("edit_draft", "post", "admin"));
    }

    private Menu editor(String currentAction) throws Exception {
        return MenuHelper.buildMenu("editor", MenuHelper.getParsedMenu("editor"), currentAction, user, weblog,
                userManager, properties::contains);
    }

    private Menu admin() throws Exception {
        globalActions.addAll(List.of("login", "admin"));
        return MenuHelper.buildMenu("admin", MenuHelper.getParsedMenu("admin"), "noAction", user, weblog,
                userManager, properties::contains);
    }

    @Test
    void theAuthorMenuStructure() throws Exception {
        author();
        golden("author", dump(editor("categories")));
    }

    @Test
    void anEditDraftMemberMenuStructure() throws Exception {
        globalActions.add("login");
        weblogActions.add("edit_draft");
        golden("edit_draft", dump(editor(null)));
    }

    @Test
    void theAdminMenuStructure() throws Exception {
        golden("admin", dump(admin()));
    }

    @Test
    void theSiteSpaceMenuStructure() throws Exception {
        author();
        weblog.setSiteSpace(true);
        golden("site_space", dump(editor("pages")));
    }

    @Test
    void aCustomThemeWeblogMenuStructure() throws Exception {
        author();
        weblog.setEditorTheme(WeblogTheme.CUSTOM);
        golden("custom", dump(editor("templates")));
    }

    @Test
    void theAuthorMacroOutputIsByteForByteWhatItWas() throws Exception {
        author();
        golden("author_vertical", render("showAuthorMenu(true)", "authorMenu", editor(null)));
        golden("author_horizontal", render("showAuthorMenu(false)", "authorMenu", editor(null)));
    }

    @Test
    void theAdminMacroOutputIsByteForByteWhatItWas() throws Exception {
        Menu menu = admin();
        golden("admin_vertical", render("showAdminMenu(true)", "adminMenu", menu));
        golden("admin_horizontal", render("showAdminMenu(false)", "adminMenu", menu));
    }

    private static String render(String call, String which, Menu menu) throws Exception {
        VelocityContext context = new VelocityContext();
        context.put("menuModel", new MenuModelStub(which, menu));
        context.put("text", new TextStub());
        context.put("url", new UrlStub());
        VelocityEngine engine = new VelocityEngine();
        engine.init();
        StringWriter out = new StringWriter();
        engine.evaluate(context, out, "menu-macros",
                Files.readString(WEBLOG_VM, StandardCharsets.UTF_8) + "\n#" + call + "\n");
        String html = out.toString();
        assertFalse(html.contains("$menu") || html.contains("$item") || html.contains("$authorMenu")
                || html.contains("$adminMenu"), "a Velocity reference leaked: " + html);
        return html;
    }

    private static String dump(Menu menu) {
        StringBuilder out = new StringBuilder();
        for (MenuTab tab : menu.getTabs()) {
            out.append(tab.getKey()).append('|').append(tab.getAction()).append('|')
                    .append(tab.isSelected() ? "S" : "-").append(" {");
            List<String> items = new ArrayList<>();
            for (MenuTabItem item : tab.getItems()) {
                items.add(item.getKey() + '|' + item.getAction() + '|' + (item.isSelected() ? "S" : "-"));
            }
            out.append(String.join("; ", items)).append("}\n");
        }
        return out.toString();
    }

    /** {@code $menuModel}: the two getters the macros call. */
    public static final class MenuModelStub {
        private final String which;
        private final Menu menu;

        MenuModelStub(String which, Menu menu) {
            this.which = which;
            this.menu = menu;
        }

        public Menu getAuthorMenu() {
            return "authorMenu".equals(which) ? menu : null;
        }

        public Menu getAdminMenu() {
            return "adminMenu".equals(which) ? menu : null;
        }
    }

    /** {@code $text.get(key)}. */
    public static final class TextStub {
        public String get(String key) {
            return "T(" + key + ")";
        }
    }

    /** {@code $url.action(name, namespace)}. */
    public static final class UrlStub {
        public String action(String name, String namespace) {
            return namespace + "/" + name + ".rol";
        }
    }

    /**
     * Compares with the bytes captured from the pre-change code, kept in
     * {@code src/test/resources/menu-chrome/}. {@code -Dgolden.write=true}
     * (re)captures them; never used after the capture on the old code.
     */
    private static void golden(String name, String actual) throws Exception {
        Path file = Path.of("src/test/resources/menu-chrome/" + name + ".txt");
        if (Boolean.getBoolean("golden.write")) {
            Files.createDirectories(file.getParent());
            Files.writeString(file, actual, StandardCharsets.UTF_8);
        }
        assertEquals(Files.readString(file, StandardCharsets.UTF_8), actual, name);
    }
}
