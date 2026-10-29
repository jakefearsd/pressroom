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
package com.wikantik.pressroom.ui.controllers.editor;

import java.util.List;

import com.wikantik.pressroom.pojos.GlobalPermission;
import com.wikantik.pressroom.pojos.WeblogPermission;
import com.wikantik.pressroom.ui.controllers.BaseController;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the declarative metadata every editor controller exposes.
 *
 * <p>None of this is decorative. {@code requiredWeblogPermissionActions} is the
 * authorization contract the {@code PressroomHandlerInterceptor} enforces before
 * the handler is ever entered — a controller that silently drops back to the
 * inherited default changes who can reach it. {@code getDesiredMenu} and
 * {@code getActionName} decide which navigation is rendered and highlighted, so
 * a null there produces a page with no menu at all. These are one-line methods
 * that no behavioural test touches, which is exactly why they can rot unnoticed.
 */
class ControllerMetadataTest {

    @Test
    void entryEditingRequiresOnlyDraftPermissionSoContributorsCanWrite() {
        // EDIT_DRAFT rather than POST: a contributor must be able to write and
        // submit for review. The publish path checks POST separately.
        assertEquals(List.of(WeblogPermission.EDIT_DRAFT),
                new EntryEditController().requiredWeblogPermissionActions());
        assertEquals(List.of(WeblogPermission.EDIT_DRAFT),
                new EntryRemoveController().requiredWeblogPermissionActions());
    }

    @Test
    void categoryAndMediaManagementRequirePostPermission() {
        // Categories and media are blog-wide structure, not a single draft, so
        // they sit above the contributor level.
        assertEquals(List.of(WeblogPermission.POST),
                new CategoryEditController().requiredWeblogPermissionActions());
        assertEquals(List.of(WeblogPermission.POST),
                new CategoryRemoveController().requiredWeblogPermissionActions());
        assertEquals(List.of(WeblogPermission.POST),
                new MediaFileAddController().requiredWeblogPermissionActions());
        assertEquals(List.of(WeblogPermission.POST),
                new MediaFileViewController().requiredWeblogPermissionActions());
        assertEquals(List.of(WeblogPermission.POST),
                new MediaFileEditController().requiredWeblogPermissionActions());
    }

    @Test
    void pageManagementRequiresPostPermission() {
        // Static pages are blog-wide structure like categories and media, not
        // a single draft, so they sit at the same POST level rather than
        // falling back to the ADMIN default.
        assertEquals(List.of(WeblogPermission.POST),
                new PageEditController().requiredWeblogPermissionActions());
        assertEquals(List.of(WeblogPermission.POST),
                new PagesController().requiredWeblogPermissionActions());
    }

    @Test
    void settingsMembershipAndTemplatesFallBackToWeblogAdmin() {
        // These do not override the inherited default, and must not: changing a
        // blog's configuration, its members or its templates is an owner's job.
        assertEquals(List.of(WeblogPermission.ADMIN),
                new WeblogConfigController().requiredWeblogPermissionActions());
        assertEquals(List.of(WeblogPermission.ADMIN),
                new MembersController().requiredWeblogPermissionActions());
        assertEquals(List.of(WeblogPermission.ADMIN),
                new TemplateEditController().requiredWeblogPermissionActions());
        assertEquals(List.of(WeblogPermission.ADMIN),
                new StylesheetEditController().requiredWeblogPermissionActions());
    }

    @Test
    void everyEditorControllerRequiresALoggedInUserAndTheLoginPermission() {
        for (BaseController controller : allControllers()) {
            assertTrue(controller.isUserRequired(),
                    controller.getClass().getSimpleName() + " must require an authenticated user");
            assertEquals(List.of(GlobalPermission.LOGIN),
                    controller.requiredGlobalPermissionActions(),
                    controller.getClass().getSimpleName() + " must require the LOGIN permission");
        }
    }

    @Test
    void everyEditorControllerRendersUnderTheEditorMenu() {
        // A null menu id makes MenuHelper return no menu at all, leaving the
        // page with no navigation.
        for (BaseController controller : allControllers()) {
            assertEquals("editor", controller.getDesiredMenu(),
                    controller.getClass().getSimpleName() + " must render the editor menu");
        }
    }

    @Test
    void controllersThatHighlightAMenuItemDeclareTheirActionName() {
        // The action name is matched against the menu definition to decide
        // which tab is shown as current.
        assertEquals("members", new MembersController().getActionName());
        assertEquals("categoryRemove", new CategoryRemoveController().getActionName());
        assertEquals("weblogConfig", new WeblogConfigController().getActionName());
        assertEquals("templateEdit", new TemplateEditController().getActionName());
        assertEquals("stylesheetEdit", new StylesheetEditController().getActionName());
        assertEquals("mediaFileAdd", new MediaFileAddController().getActionName());
        assertEquals("mediaFileView", new MediaFileViewController().getActionName());
        assertEquals("mediaFileEdit", new MediaFileEditController().getActionName());
    }

    @Test
    void controllersThatOwnAPageDeclareItsTitleKey() {
        assertEquals("memberPermissions.title", new MembersController().getPageTitle());
        assertEquals("categoryDeleteOK.title", new CategoryRemoveController().getPageTitle());
        assertEquals("weblogEdit.deleteEntry", new EntryRemoveController().getPageTitle());
        assertEquals("websiteSettings.title", new WeblogConfigController().getPageTitle());
        assertEquals("pagesForm.title", new TemplateEditController().getPageTitle());
        assertEquals("stylesheetEdit.title", new StylesheetEditController().getPageTitle());
        assertEquals("mediaFileAdd.title", new MediaFileAddController().getPageTitle());
        assertEquals("mediaFileView.title", new MediaFileViewController().getPageTitle());
        assertEquals("mediaFile.edit.title", new MediaFileEditController().getPageTitle());
    }

    private static List<BaseController> allControllers() {
        return List.of(
                new CategoryEditController(),
                new CategoryRemoveController(),
                new EntryEditController(),
                new EntryRemoveController(),
                new MediaFileAddController(),
                new MediaFileEditController(),
                new MediaFileViewController(),
                new MembersController(),
                new StylesheetEditController(),
                new TemplateEditController(),
                new WeblogConfigController());
    }

    /**
     * Every {@code @RequestParam}, {@code @PathVariable} and
     * {@code @RequestHeader} must name the parameter it binds.
     *
     * <p>This is not style. The build does not pass {@code -parameters}, so a
     * bare {@code @RequestParam String id} has no name to bind at runtime and
     * Spring throws {@code IllegalArgumentException} the first time the
     * endpoint is called. Nothing catches it earlier: the class compiles, unit
     * tests that invoke the method directly pass, and the failure only appears
     * when a real request arrives. That is exactly how the Markdown preview
     * endpoint shipped broken.
     */
    @Test
    void everyRequestBoundParameterIsNamedExplicitly() throws Exception {
        List<String> unnamed = new java.util.ArrayList<>();

        for (Class<?> controller : controllerClasses()) {
            for (java.lang.reflect.Method method : controller.getDeclaredMethods()) {
                for (java.lang.reflect.Parameter parameter : method.getParameters()) {
                    String annotationName = boundName(parameter);
                    if (annotationName != null && annotationName.isEmpty()) {
                        unnamed.add(controller.getSimpleName() + "." + method.getName()
                                + "(" + parameter.getType().getSimpleName() + ")");
                    }
                }
            }
        }

        assertTrue(unnamed.isEmpty(),
                "These request-bound parameters do not name what they bind, so they will "
                        + "throw at runtime for want of the -parameters compiler flag:\n  "
                        + String.join("\n  ", unnamed));
    }

    /**
     * The declared name of whatever request-binding annotation this parameter
     * carries, or null when it carries none. Both {@code value} and
     * {@code name} are checked because the annotations treat them as aliases.
     */
    private static String boundName(java.lang.reflect.Parameter parameter) {
        var requestParam = parameter.getAnnotation(
                org.springframework.web.bind.annotation.RequestParam.class);
        if (requestParam != null) {
            return requestParam.value().isEmpty() ? requestParam.name() : requestParam.value();
        }
        var pathVariable = parameter.getAnnotation(
                org.springframework.web.bind.annotation.PathVariable.class);
        if (pathVariable != null) {
            return pathVariable.value().isEmpty() ? pathVariable.name() : pathVariable.value();
        }
        var header = parameter.getAnnotation(
                org.springframework.web.bind.annotation.RequestHeader.class);
        if (header != null) {
            return header.value().isEmpty() ? header.name() : header.value();
        }
        return null;
    }

    /**
     * Every controller class in the application, found on the classpath.
     *
     * <p>Rooted at {@code ui} rather than {@code ui/controllers} so the same
     * walk also covers {@code ui.restapi} -- the JSP admin controllers
     * ({@code *Controller.java}) and the REST API controllers
     * ({@code *Api.java}) are two naming conventions for the same hazard:
     * both bind {@code @PathVariable}/{@code @RequestParam} without the
     * {@code -parameters} compiler flag, so both need this one gate rather
     * than a second, parallel scan that could drift from this one.
     */
    private static List<Class<?>> controllerClasses() throws Exception {
        java.nio.file.Path root = java.nio.file.Paths.get("src/main/java/com/wikantik/pressroom/ui");
        assertTrue(java.nio.file.Files.isDirectory(root), "Expected " + root.toAbsolutePath());

        List<Class<?>> classes = new java.util.ArrayList<>();
        try (var paths = java.nio.file.Files.walk(root)) {
            for (java.nio.file.Path file : paths.filter(java.nio.file.Files::isRegularFile)
                    .filter(f -> f.toString().endsWith("Controller.java")
                            || f.toString().endsWith("Api.java"))
                    .toList()) {
                String relative = root.relativize(file).toString();
                String className = "com.wikantik.pressroom.ui."
                        + relative.substring(0, relative.length() - ".java".length())
                                .replace(java.io.File.separatorChar, '.');
                classes.add(Class.forName(className));
            }
        }
        assertFalse(classes.isEmpty(), "No controllers found to scan");
        return classes;
    }
}
