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
package com.wikantik.pressroom.business;

import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.business.plugins.PluginManager;
import com.wikantik.pressroom.business.runnable.ThreadManager;
import com.wikantik.pressroom.business.search.IndexManager;
import com.wikantik.pressroom.business.themes.ThemeManager;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * The lifecycle {@link WebloggerImpl} runs over its managers: the start-up
 * order (properties before themes before threads before the index, which
 * later steps depend on), what a failed flush turns into, and that a manager
 * which fails to release or shut down does not stop the others.
 */
class WebloggerImplLifecycleTest {

    private final IndexManager index = mock(IndexManager.class);
    private final MediaFileManager media = mock(MediaFileManager.class);
    private final FileContentManager files = mock(FileContentManager.class);
    private final PluginManager plugins = mock(PluginManager.class);
    private final PropertiesManager properties = mock(PropertiesManager.class);
    private final ThemeManager themes = mock(ThemeManager.class);
    private final ThreadManager threads = mock(ThreadManager.class);
    private final UserManager users = mock(UserManager.class);
    private final WeblogManager weblogs = mock(WeblogManager.class);

    private WebloggerException flushFailure;

    private WebloggerImpl weblogger(IndexManager indexManager, ThreadManager threadManager) throws Exception {
        return new WebloggerImpl(indexManager, media, files, null, null, null, null, null, null, null, null,
                plugins, properties, themes, threadManager, users, weblogs, null, null, null, null, null) {
            @Override
            public void flush() throws WebloggerException {
                if (flushFailure != null) {
                    throw flushFailure;
                }
            }
        };
    }

    @Test
    void initializeStartsTheManagersInDependencyOrderThenFlushes() throws Exception {
        weblogger(index, threads).initialize();

        InOrder order = inOrder(properties, themes, threads, index, media);
        order.verify(properties).initialize();
        order.verify(themes).initialize();
        order.verify(threads).initialize();
        order.verify(index).initialize();
        order.verify(media).initialize();
    }

    @Test
    void aFailedFlushAfterInitializationIsAnInitializationException() throws Exception {
        flushFailure = new WebloggerException("db gone");

        InitializationException e = assertThrows(InitializationException.class,
                () -> weblogger(index, threads).initialize());

        assertSame(flushFailure, e.getCause());
    }

    @Test
    void releaseAsksEveryManagerAndSurvivesOneThatFails() throws Exception {
        doThrow(new IllegalStateException("boom")).when(files).release();

        assertDoesNotThrow(() -> weblogger(index, threads).release());

        verify(media).release();
        verify(files).release();
    }

    @Test
    void shutdownStopsTheIndexAndThreadsAndToleratesMissingOrFailingOnes() throws Exception {
        weblogger(index, threads).shutdown();
        verify(index).shutdown();
        verify(threads).shutdown();

        assertDoesNotThrow(() -> weblogger(null, null).shutdown(), "a tier with neither manager");

        doThrow(new IllegalStateException("index stuck")).when(index).shutdown();
        ThreadManager other = mock(ThreadManager.class);
        assertDoesNotThrow(() -> weblogger(index, other).shutdown());
        verify(other, never()).shutdown();
    }
}
