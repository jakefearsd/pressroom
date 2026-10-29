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
package com.wikantik.pressroom.ui.controllers.admin;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.function.Supplier;

import jakarta.servlet.http.HttpServletRequest;

import java.lang.reflect.Proxy;

import com.wikantik.pressroom.business.Weblogger;
import com.wikantik.pressroom.TestUtils;
import com.wikantik.pressroom.config.WebloggerConfig;
import com.wikantik.pressroom.pojos.User;
import com.wikantik.pressroom.ui.controllers.BaseController;
import com.wikantik.pressroom.ui.core.PressroomContext;
import org.springframework.context.MessageSource;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.ui.Model;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Scaffolding shared by the admin controller tests: a controller that resolves
 * messages, a request that behaves like one the interceptor has already
 * prepared, and readable access to what a controller put in the model.
 *
 * <p>Controllers get their {@code MessageSource} by field injection and their
 * authenticated user from a request attribute, neither of which exists when the
 * class is simply constructed with {@code new}. Everything here is about
 * supplying those two things.
 */
final class ControllerTestFixture {

    /**
     * Message codes look like {@code some.thing.here}. Roller also passes plain
     * English sentences through the same call, and those cannot be looked up in
     * the bundle, so they are left alone.
     */
    private static final String KEY_PATTERN = "^[a-zA-Z][a-zA-Z0-9]*(\\.[a-zA-Z0-9]+)+$";

    private static final Properties BUNDLE = loadBundle();

    /**
     * Stands in for the {@code @Lazy} Spring proxy that production controllers
     * get injected: every call is forwarded to whatever {@link #useWeblogger}
     * last chose -- by default the suite's real tier ({@code TestUtils.weblogger()}),
     * or the {@code MockWeblogger} facade a test hands in -- resolved at call
     * time rather than at field-set time.
     *
     * <p>That laziness matters here because some tests build their controller
     * in a field initialiser, before {@code @BeforeEach} has built the mock,
     * exactly as {@code @Lazy} defers real injection until first use in
     * production. There is no static locator behind this any more: the
     * supplier is the fixture's own, and {@link #useDefaultWeblogger()} in
     * {@code @AfterEach} puts it back.
     */
    private static volatile Supplier<Weblogger> webloggerSupplier = TestUtils::weblogger;

    private static final Weblogger LAZY_WEBLOGGER = (Weblogger) Proxy.newProxyInstance(
            ControllerTestFixture.class.getClassLoader(),
            new Class<?>[]{Weblogger.class},
            (proxy, method, args) -> method.invoke(webloggerSupplier.get(), args));

    private ControllerTestFixture() {
    }

    /** Point every controller wired by {@link #withMessages} at this facade (a mock, usually). */
    static void useWeblogger(Weblogger weblogger) {
        webloggerSupplier = () -> weblogger;
    }

    /** Back to the suite's real tier; call from {@code @AfterEach} after {@link #useWeblogger}. */
    static void useDefaultWeblogger() {
        webloggerSupplier = TestUtils::weblogger;
    }

    /**
     * Gives the controller a message source that returns the code itself, with
     * any arguments appended, so a test can assert exactly which message a
     * controller chose and what it passed in. Also gives it a {@code weblogger}
     * field that resolves lazily against {@link #useWeblogger}'s choice,
     * mirroring the {@code @Lazy} proxy the controller gets in production.
     */
    static <T extends BaseController> T withMessages(T controller) {
        setField(controller, "messageSource", new KeyEchoMessageSource());
        setField(controller, "weblogger", LAZY_WEBLOGGER);
        return controller;
    }

    /**
     * A request carrying the attributes {@code PressroomHandlerInterceptor} sets
     * before a controller ever runs.
     */
    static HttpServletRequest requestFor(User authenticatedUser) {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getLocale()).thenReturn(Locale.ENGLISH);
        when(request.getAttribute("authenticatedUser")).thenReturn(authenticatedUser);
        return request;
    }

    /** The messages a controller reported as errors, in the order it added them. */
    @SuppressWarnings("unchecked")
    static List<String> errors(Model model) {
        List<String> errors = (List<String>) model.getAttribute("errors");
        return errors == null ? Collections.emptyList() : errors;
    }

    /**
     * The DOM ids of the controls a controller marked invalid, in the order it
     * named them -- {@code BaseController.addFieldError}'s half that
     * {@code addError} does not have.
     */
    @SuppressWarnings("unchecked")
    static List<String> invalidFields(Model model) {
        Collection<String> fields = (Collection<String>) model.getAttribute("invalidFields");
        return fields == null ? Collections.emptyList() : List.copyOf(fields);
    }

    /** The messages a controller reported as successes, in the order it added them. */
    @SuppressWarnings("unchecked")
    static List<String> messages(Model model) {
        List<String> messages = (List<String>) model.getAttribute("messages");
        return messages == null ? Collections.emptyList() : messages;
    }

    /** Errors a controller parked in flash scope to survive a redirect. */
    @SuppressWarnings("unchecked")
    static List<String> flashErrors(RedirectAttributes redirectAttributes) {
        List<String> errors = (List<String>) redirectAttributes.getFlashAttributes().get("errors");
        return errors == null ? Collections.emptyList() : errors;
    }

    /** Messages a controller parked in flash scope to survive a redirect. */
    @SuppressWarnings("unchecked")
    static List<String> flashMessages(RedirectAttributes redirectAttributes) {
        List<String> messages = (List<String>) redirectAttributes.getFlashAttributes().get("messages");
        return messages == null ? Collections.emptyList() : messages;
    }

    /**
     * Installs a real bcrypt encoder, as {@code PressroomContext} normally creates
     * at servlet-context startup. Without it {@code User.resetPassword} throws
     * NPE, so no test could reach the password-handling branches.
     *
     * @return the encoder previously installed, to be handed back to
     *         {@link #restorePasswordEncoder} when the test ends
     */
    static Object installBcryptPasswordEncoder() {
        Object previous = getStaticField(PressroomContext.class, "encoder");
        setStaticField(PressroomContext.class, "encoder",
                new DelegatingPasswordEncoder("bcrypt",
                        Map.of("bcrypt", new BCryptPasswordEncoder())));
        return previous;
    }

    static void restorePasswordEncoder(Object previous) {
        setStaticField(PressroomContext.class, "encoder", previous);
    }

    /**
     * Temporarily changes a static configuration property.
     *
     * <p>{@code WebloggerConfig} reads {@code pressroom.properties} once in a
     * static initializer, so a test that needs a different value -- or none at
     * all -- has no other way in.
     *
     * @return the previous value, for {@link #restoreConfigProperty}
     */
    static String setConfigProperty(String name, String value) {
        Properties config = staticConfig();
        String previous = config.getProperty(name);
        if (value == null) {
            config.remove(name);
        } else {
            config.setProperty(name, value);
        }
        return previous;
    }

    static void restoreConfigProperty(String name, String previous) {
        Properties config = staticConfig();
        if (previous == null) {
            config.remove(name);
        } else {
            config.setProperty(name, previous);
        }
    }

    private static Properties staticConfig() {
        return (Properties) getStaticField(WebloggerConfig.class, "config");
    }

    private static Properties loadBundle() {
        Properties props = new Properties();
        try (var in = ControllerTestFixture.class.getResourceAsStream("/ApplicationResources.properties")) {
            if (in == null) {
                throw new IllegalStateException(
                        "ApplicationResources.properties is not on the test classpath; "
                                + "controller tests cannot check the message codes they assert on.");
            }
            props.load(in);
        } catch (Exception e) {
            throw new IllegalStateException("Could not read ApplicationResources.properties", e);
        }
        return props;
    }

    private static void setField(Object target, String name, Object value) {
        Class<?> type = target.getClass();
        while (type != null) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                field.set(target, value);
                return;
            } catch (NoSuchFieldException e) {
                type = type.getSuperclass();
            } catch (IllegalAccessException e) {
                throw new IllegalStateException("Could not set " + name + " on " + target.getClass(), e);
            }
        }
        throw new IllegalStateException(
                "No field '" + name + "' on " + target.getClass()
                        + " or its supertypes. If BaseController stopped injecting its MessageSource "
                        + "into a field, set it here the way the controller now expects it.");
    }

    private static Object getStaticField(Class<?> type, String name) {
        try {
            Field field = type.getDeclaredField(name);
            field.setAccessible(true);
            return field.get(null);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Could not read " + type.getSimpleName() + "." + name, e);
        }
    }

    private static void setStaticField(Class<?> type, String name, Object value) {
        try {
            Field field = type.getDeclaredField(name);
            field.setAccessible(true);
            field.set(null, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Could not set " + type.getSimpleName() + "." + name, e);
        }
    }

    /**
     * Renders a message as {@code code} or {@code code[arg, arg]} so assertions
     * can pin down both the message a controller chose and the values it
     * substituted into it.
     */
    private static final class KeyEchoMessageSource implements MessageSource {

        @Override
        public String getMessage(String code, Object[] args, String defaultMessage, Locale locale) {
            return render(code, args);
        }

        @Override
        public String getMessage(String code, Object[] args, Locale locale) {
            return render(code, args);
        }

        @Override
        public String getMessage(MessageSourceResolvable resolvable, Locale locale) {
            return render(resolvable.getCodes()[0], resolvable.getArguments());
        }

        private static String render(String code, Object[] args) {
            if (code.matches(KEY_PATTERN) && !BUNDLE.containsKey(code)) {
                throw new AssertionError(
                        "Controller asked for message code '" + code + "', which is not defined in "
                                + "app/src/main/resources/ApplicationResources.properties. Users would see "
                                + "the raw code. Add the code to the bundle, or use one that exists.");
            }
            return (args == null || args.length == 0) ? code : code + Arrays.toString(args);
        }
    }
}
