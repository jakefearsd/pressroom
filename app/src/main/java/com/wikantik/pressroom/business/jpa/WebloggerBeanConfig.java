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
package com.wikantik.pressroom.business.jpa;

import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.business.ApiTokenManager;
import com.wikantik.pressroom.business.DatabaseProvider;
import com.wikantik.pressroom.business.EntryHtmlCache;
import com.wikantik.pressroom.business.EntryRenderer;
import com.wikantik.pressroom.business.EventManager;
import com.wikantik.pressroom.business.FileContentManager;
import com.wikantik.pressroom.business.FileContentManagerImpl;
import com.wikantik.pressroom.business.FormSubmissionManager;
import com.wikantik.pressroom.business.MediaFileManager;
import com.wikantik.pressroom.business.MultiWeblogURLStrategy;
import com.wikantik.pressroom.business.PropertiesManager;
import com.wikantik.pressroom.business.URLStrategy;
import com.wikantik.pressroom.business.VirtualHostRegistry;
import com.wikantik.pressroom.business.UserManager;
import com.wikantik.pressroom.business.UserTokenManager;
import com.wikantik.pressroom.business.WeblogEntryManager;
import com.wikantik.pressroom.business.WeblogManager;
import com.wikantik.pressroom.business.WeblogPageManager;
import com.wikantik.pressroom.business.WeblogRedirectManager;
import com.wikantik.pressroom.business.BusinessManager;
import com.wikantik.pressroom.business.CommentManager;
import com.wikantik.pressroom.business.Weblogger;
import com.wikantik.pressroom.business.plugins.PluginManager;
import com.wikantik.pressroom.business.plugins.PluginManagerImpl;
import com.wikantik.pressroom.business.runnable.ThreadManager;
import com.wikantik.pressroom.business.search.IndexManager;
import com.wikantik.pressroom.business.search.lucene.LuceneIndexManager;
import com.wikantik.pressroom.business.shortcodes.ShortcodeExpander;
import com.wikantik.pressroom.business.startup.WebloggerStartup;
import com.wikantik.pressroom.business.themes.ThemeManager;
import com.wikantik.pressroom.business.themes.ThemeManagerImpl;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

/**
 * Wires the JPA-backed business tier as Spring beans: one {@code @Bean}
 * method per manager interface.
 *
 * <p>{@code JPAWebloggerImpl} depends on all the manager interfaces, and five
 * of those managers depend back on {@link Weblogger} itself (to reach sibling
 * managers through the facade). A proxy-based injector can paper over that
 * cycle transparently; Spring's constructor injection needs an explicit out:
 * every {@code Weblogger} parameter below is marked {@code @Lazy}, which
 * makes Spring hand the constructor a lazy-resolving proxy instead of trying
 * to fully construct the {@code Weblogger} bean during those managers'
 * construction. Omitting {@code @Lazy} on any of them reproduces the cycle
 * and fails fast with {@code BeanCurrentlyInCreationException}.
 *
 * <p>The whole configuration is {@code @Lazy} so that simply loading this
 * class (as {@link com.wikantik.pressroom.business.SpringWebloggerProvider}
 * does in its constructor) does not eagerly instantiate anything; construction
 * only happens once {@code getBean(Weblogger.class)} is called from
 * {@code bootstrap()}.
 */
@Configuration
@Lazy
public class WebloggerBeanConfig {

    /**
     * The database provider is prepared once by {@link WebloggerStartup#prepare()}
     * before any Weblogger bootstrap runs; reuse that singleton rather than
     * constructing a second one. Same configuration, one instance instead of
     * two.
     */
    @Bean
    public DatabaseProvider databaseProvider() {
        return WebloggerStartup.getDatabaseProvider();
    }

    @Bean
    public JPAPersistenceStrategy jpaPersistenceStrategy(DatabaseProvider databaseProvider) throws WebloggerException {
        return new JPAPersistenceStrategy(databaseProvider);
    }

    @Bean
    public PropertiesManager propertiesManager(JPAPersistenceStrategy strategy) {
        return new JPAPropertiesManagerImpl(strategy);
    }

    @Bean
    public ThreadManager threadManager(@Lazy Weblogger weblogger, JPAPersistenceStrategy strategy) {
        return new JPAThreadManagerImpl(weblogger, strategy);
    }

    @Bean
    public UserManager userManager(@Lazy Weblogger weblogger, JPAPersistenceStrategy strategy) {
        return new JPAUserManagerImpl(weblogger, strategy);
    }

    @Bean
    public WeblogManager weblogManager(@Lazy Weblogger weblogger, JPAPersistenceStrategy strategy,
                                       VirtualHostRegistry virtualHostRegistry) {
        return new JPAWeblogManagerImpl(weblogger, strategy, virtualHostRegistry);
    }

    /**
     * The hostname-to-handle map. Takes the weblog manager lazily (the manager
     * depends back on the registry to invalidate it after a save) and is
     * therefore built eagerly WITH the manager, so the map is resolvable from
     * the first request rather than from the first save.
     */
    @Bean
    public VirtualHostRegistry virtualHostRegistry(@Lazy WeblogManager weblogManager) {
        return new VirtualHostRegistry(weblogManager);
    }

    @Bean
    public WeblogEntryManager weblogEntryManager(@Lazy Weblogger weblogger, JPAPersistenceStrategy strategy) {
        return new JPAWeblogEntryManagerImpl(weblogger, strategy);
    }

    @Bean
    public MediaFileManager mediaFileManager(@Lazy Weblogger weblogger, JPAPersistenceStrategy strategy) {
        return new JPAMediaFileManagerImpl(weblogger, strategy);
    }

    @Bean
    public FileContentManager fileContentManager() {
        return new FileContentManagerImpl();
    }

    @Bean
    public WeblogPageManager weblogPageManager(@Lazy Weblogger weblogger, JPAPersistenceStrategy strategy) {
        return new JPAWeblogPageManagerImpl(weblogger, strategy);
    }

    @Bean
    public WeblogRedirectManager weblogRedirectManager(JPAPersistenceStrategy strategy) {
        return new JPAWeblogRedirectManagerImpl(strategy);
    }

    @Bean
    public BusinessManager businessManager(JPAPersistenceStrategy strategy) {
        return new JPABusinessManagerImpl(strategy);
    }

    @Bean
    public CommentManager commentManager(JPAPersistenceStrategy strategy) {
        return new JPACommentManagerImpl(strategy);
    }

    @Bean
    public EventManager eventManager(JPAPersistenceStrategy strategy) {
        return new JPAEventManagerImpl(strategy);
    }

    @Bean
    public FormSubmissionManager formSubmissionManager(JPAPersistenceStrategy strategy) {
        return new JPAFormSubmissionManagerImpl(strategy);
    }

    @Bean
    public UserTokenManager userTokenManager(JPAPersistenceStrategy strategy) {
        return new JPAUserTokenManagerImpl(strategy);
    }

    @Bean
    public ApiTokenManager apiTokenManager(JPAPersistenceStrategy strategy) {
        return new JPAApiTokenManagerImpl(strategy);
    }

    @Bean
    public IndexManager indexManager(@Lazy Weblogger weblogger) {
        return new LuceneIndexManager(weblogger);
    }

    /**
     * The shortcode registry, built once with the tier's own collaborators.
     * The facade is a lazy proxy here (the {@code Weblogger} bean depends on
     * this one through the renderer, so a real reference would be a cycle);
     * the handlers never dereference it at construction.
     */
    @Bean
    public ShortcodeExpander shortcodeExpander(@Lazy Weblogger weblogger, MediaFileManager mediaFileManager) {
        return ShortcodeExpander.builtIn(weblogger, mediaFileManager);
    }

    @Bean
    public PluginManager pluginManager(ShortcodeExpander shortcodeExpander) {
        return new PluginManagerImpl(shortcodeExpander);
    }

    @Bean
    public EntryRenderer entryRenderer(ShortcodeExpander shortcodeExpander, PluginManager pluginManager,
                                       URLStrategy urlStrategy) {
        return new EntryRenderer(shortcodeExpander, pluginManager, urlStrategy, EntryHtmlCache.fromConfig());
    }

    @Bean
    public ThemeManager themeManager(@Lazy Weblogger weblogger) {
        return new ThemeManagerImpl(weblogger);
    }

    @Bean
    public URLStrategy urlStrategy() {
        return new MultiWeblogURLStrategy();
    }

    @Bean(destroyMethod = "shutdown")
    public Weblogger weblogger(
            JPAPersistenceStrategy strategy,
            IndexManager indexManager,
            MediaFileManager mediaFileManager,
            FileContentManager fileContentManager,
            WeblogPageManager weblogPageManager,
            WeblogRedirectManager weblogRedirectManager,
            BusinessManager businessManager,
            CommentManager commentManager,
            EventManager eventManager,
            FormSubmissionManager formSubmissionManager,
            UserTokenManager userTokenManager,
            ApiTokenManager apiTokenManager,
            PluginManager pluginManager,
            PropertiesManager propertiesManager,
            ThemeManager themeManager,
            ThreadManager threadManager,
            UserManager userManager,
            WeblogManager weblogManager,
            WeblogEntryManager weblogEntryManager,
            URLStrategy urlStrategy,
            VirtualHostRegistry virtualHostRegistry,
            EntryRenderer entryRenderer) throws WebloggerException {
        return new JPAWebloggerImpl(
                strategy,
                indexManager,
                mediaFileManager,
                fileContentManager,
                weblogPageManager,
                weblogRedirectManager,
                businessManager,
                commentManager,
                eventManager,
                formSubmissionManager,
                userTokenManager,
                apiTokenManager,
                pluginManager,
                propertiesManager,
                themeManager,
                threadManager,
                userManager,
                weblogManager,
                weblogEntryManager,
                urlStrategy,
                virtualHostRegistry,
                entryRenderer);
    }
}
