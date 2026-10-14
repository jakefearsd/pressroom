/*
* Licensed to the Apache Software Foundation (ASF) under one or more
*  contributor license agreements.  The ASF licenses this file to You
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
package org.apache.roller.weblogger.business;

import org.apache.roller.weblogger.config.RuntimeConfigAttachment;

import org.apache.roller.weblogger.business.plugins.PluginManager;
import org.apache.roller.weblogger.business.shortcodes.ShortcodeExpander;
import org.mockito.Mockito;
import org.apache.roller.weblogger.business.search.IndexManager;
import org.apache.roller.weblogger.business.themes.ThemeManager;
import org.apache.roller.weblogger.business.runnable.ThreadManager;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A fully-mocked {@link Weblogger} facade, built by {@link #create()} or
 * {@link #attached()}, for the unit tests of classes that receive the business
 * tier by constructor, {@code init} or field.
 *
 * <p>There is no global to install it into: the static service locator that
 * once made "install the mock tier" a thing is gone (plan of 2026-08-22,
 * Decision 9), so a test hands {@link #weblogger()} to the class under test
 * explicitly. The one residual static is {@code WebloggerRuntimeConfig}'s
 * attached {@code PropertiesManager} (spec Decision 8, retired by Stage 2):
 * {@link #attached()} points it at this mock's manager for tests whose code
 * under test still reads runtime config through that facade, and
 * {@link #detach()} restores whatever was attached before.
 *
 * <p>Typical use:
 *
 * <pre>{@code
 * private MockWeblogger weblogger;
 *
 * @BeforeEach void setUp() { weblogger = MockWeblogger.attached(); }
 * @AfterEach  void tearDown() { weblogger.detach(); }
 *
 * @Test void findsTheWeblog() throws Exception {
 *     SomeController controller = new SomeController(weblogger.weblogger());
 *     when(weblogger.weblogManager().getWeblogByHandle("blog")).thenReturn(aWeblog);
 *     ...
 * }
 * }</pre>
 *
 * <p>Every manager is a Mockito mock with no stubbed behaviour, so an unstubbed call
 * returns null rather than throwing. Tests stub only what they need.
 */
public final class MockWeblogger {

    private final Weblogger weblogger = mock(Weblogger.class);

    private final UserManager userManager = mock(UserManager.class);
    private final WeblogManager weblogManager = mock(WeblogManager.class);
    private final WeblogEntryManager weblogEntryManager = mock(WeblogEntryManager.class);
    private final PropertiesManager propertiesManager = mock(PropertiesManager.class);
    private final ThreadManager threadManager = mock(ThreadManager.class);
    private final IndexManager indexManager = mock(IndexManager.class);
    private final ThemeManager themeManager = mock(ThemeManager.class);
    private final PluginManager pluginManager = mock(PluginManager.class);
    private final MediaFileManager mediaFileManager = mock(MediaFileManager.class);
    private final FileContentManager fileContentManager = mock(FileContentManager.class);
    private final WeblogPageManager weblogPageManager = mock(WeblogPageManager.class);
    private final WeblogRedirectManager weblogRedirectManager = mock(WeblogRedirectManager.class);
    private final BusinessManager businessManager = mock(BusinessManager.class);
    private final CommentManager commentManager = mock(CommentManager.class);
    private final URLStrategy urlStrategy = mock(URLStrategy.class);
    private final FormSubmissionManager formSubmissionManager = mock(FormSubmissionManager.class);
    private final EventManager eventManager = mock(EventManager.class);
    private final UserTokenManager userTokenManager = mock(UserTokenManager.class);
    private final ApiTokenManager apiTokenManager = mock(ApiTokenManager.class);
    /**
     * A REAL renderer over the mocked collaborators, not a mock: rendering is a
     * pure function of the expander (built over this facade and its media
     * manager mock) and the plugin manager mock (whose empty answer means
     * "no plugins"), so a controller or wrapper test that renders an entry
     * gets real markdown/shortcode output without stubbing anything.
     */
    private final EntryRenderer entryRenderer;

    private int flushCount;

    private MockWeblogger() throws org.apache.roller.weblogger.WebloggerException {
        when(weblogger.getUserManager()).thenReturn(userManager);
        when(weblogger.getWeblogManager()).thenReturn(weblogManager);
        when(weblogger.getWeblogEntryManager()).thenReturn(weblogEntryManager);
        when(weblogger.getPropertiesManager()).thenReturn(propertiesManager);
        when(weblogger.getThreadManager()).thenReturn(threadManager);
        when(weblogger.getIndexManager()).thenReturn(indexManager);
        when(weblogger.getThemeManager()).thenReturn(themeManager);
        when(weblogger.getPluginManager()).thenReturn(pluginManager);
        when(weblogger.getMediaFileManager()).thenReturn(mediaFileManager);
        when(weblogger.getFileContentManager()).thenReturn(fileContentManager);
        when(weblogger.getWeblogPageManager()).thenReturn(weblogPageManager);
        when(weblogger.getWeblogRedirectManager()).thenReturn(weblogRedirectManager);
        when(weblogger.getBusinessManager()).thenReturn(businessManager);
        when(weblogger.getCommentManager()).thenReturn(commentManager);
        when(weblogger.getUrlStrategy()).thenReturn(urlStrategy);
        when(weblogger.getFormSubmissionManager()).thenReturn(formSubmissionManager);
        when(weblogger.getEventManager()).thenReturn(eventManager);
        when(weblogger.getUserTokenManager()).thenReturn(userTokenManager);
        when(weblogger.getApiTokenManager()).thenReturn(apiTokenManager);
        entryRenderer = new EntryRenderer(
                ShortcodeExpander.builtIn(weblogger, mediaFileManager), pluginManager);
        when(weblogger.getEntryRenderer()).thenReturn(entryRenderer);

        // Count commits. A manager call that is never flushed is a change that
        // never reaches the database, and nothing else these tests can observe
        // tells the two apart.
        Mockito.doAnswer(invocation -> {
            flushCount++;
            return null;
        }).when(weblogger).flush();
    }

    public URLStrategy getUrlStrategy() {
        return urlStrategy;
    }

    public EntryRenderer getEntryRenderer() {
        return entryRenderer;
    }

    public EntryRenderer entryRenderer() {
        return entryRenderer;
    }

    public URLStrategy urlStrategy() {
        return urlStrategy;
    }

    /** How many times the code under test committed its work. */
    public int flushCount() {
        return flushCount;
    }

    /** A mocked facade and nothing else: no global state is touched. */
    public static MockWeblogger create() {
        try {
            return new MockWeblogger();
        } catch (org.apache.roller.weblogger.WebloggerException e) {
            // Only the Mockito stubbing above declares it; it cannot actually throw.
            throw new IllegalStateException("Could not build the mock business tier", e);
        }
    }

    /**
     * A mocked facade whose {@link PropertiesManager} is also attached to
     * {@code WebloggerRuntimeConfig} (spec Decision 8 of the 2026-08-22 plan:
     * runtime-config reads still go through that one static until Stage 2).
     * Call {@link #detach()} from {@code @AfterEach}; the attachment is
     * process-global, so a test that attaches without detaching leaks its
     * answers into whatever runs next in the same JVM.
     */
    public static MockWeblogger attached() {
        MockWeblogger mocks = create();
        mocks.attachment = mocks.attachRuntimeConfig();
        return mocks;
    }

    private RuntimeConfigAttachment attachment;

    /**
     * Restores whatever {@code WebloggerRuntimeConfig} had attached before
     * {@link #attached()} -- rather than clearing it, because the
     * database-backed tests attach the real tier's manager for the whole JVM
     * and must find it still there. A no-op for a mock built by
     * {@link #create()}.
     */
    public void detach() {
        if (attachment != null) {
            attachment.close();
            attachment = null;
        }
    }

    /**
     * Points {@code WebloggerRuntimeConfig} at this mock tier's properties
     * manager -- for a test whose class under test is injected but whose
     * runtime-config reads still go through the static facade. Returns an
     * {@link AutoCloseable} that restores whatever was attached before; use it
     * in try-with-resources.
     */
    public RuntimeConfigAttachment attachRuntimeConfig() {
        return RuntimeConfigAttachment.of(propertiesManager);
    }

    public Weblogger weblogger() {
        return weblogger;
    }

    // getX() aliases mirror the Weblogger facade, so a test can read either
    // mocks.weblogManager() or mocks.getWeblogManager() without surprise.
    public UserManager getUserManager() {
        return userManager;
    }

    public WeblogManager getWeblogManager() {
        return weblogManager;
    }

    public WeblogEntryManager getWeblogEntryManager() {
        return weblogEntryManager;
    }

    public PropertiesManager getPropertiesManager() {
        return propertiesManager;
    }

    public ThreadManager getThreadManager() {
        return threadManager;
    }

    public IndexManager getIndexManager() {
        return indexManager;
    }

    public ThemeManager getThemeManager() {
        return themeManager;
    }

    public PluginManager getPluginManager() {
        return pluginManager;
    }

    public MediaFileManager getMediaFileManager() {
        return mediaFileManager;
    }

    public FileContentManager getFileContentManager() {
        return fileContentManager;
    }

    public WeblogPageManager getWeblogPageManager() {
        return weblogPageManager;
    }

    public FormSubmissionManager getFormSubmissionManager() {
        return formSubmissionManager;
    }

    public EventManager getEventManager() {
        return eventManager;
    }

    public UserTokenManager getUserTokenManager() {
        return userTokenManager;
    }

    public UserManager userManager() {
        return userManager;
    }

    public WeblogManager weblogManager() {
        return weblogManager;
    }

    public WeblogEntryManager weblogEntryManager() {
        return weblogEntryManager;
    }

    public PropertiesManager propertiesManager() {
        return propertiesManager;
    }

    public ThreadManager threadManager() {
        return threadManager;
    }

    public IndexManager indexManager() {
        return indexManager;
    }

    public ThemeManager themeManager() {
        return themeManager;
    }

    public PluginManager pluginManager() {
        return pluginManager;
    }

    public MediaFileManager mediaFileManager() {
        return mediaFileManager;
    }

    public FileContentManager fileContentManager() {
        return fileContentManager;
    }

    public WeblogPageManager weblogPageManager() {
        return weblogPageManager;
    }

    public WeblogRedirectManager weblogRedirectManager() {
        return weblogRedirectManager;
    }

    public BusinessManager businessManager() {
        return businessManager;
    }

    public CommentManager commentManager() {
        return commentManager;
    }

    public FormSubmissionManager formSubmissionManager() {
        return formSubmissionManager;
    }

    public EventManager eventManager() {
        return eventManager;
    }

    public UserTokenManager userTokenManager() {
        return userTokenManager;
    }

    public ApiTokenManager getApiTokenManager() {
        return apiTokenManager;
    }

    public ApiTokenManager apiTokenManager() {
        return apiTokenManager;
    }
}
