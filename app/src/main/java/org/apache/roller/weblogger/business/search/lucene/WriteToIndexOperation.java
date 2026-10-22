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
/* Created on Aug 12, 2003 */
package org.apache.roller.weblogger.business.search.lucene;

import java.util.concurrent.locks.ReentrantLock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * An operation that writes to index.
 * @author Mindaugas Idzelis (min@idzelis.com)
 */
public abstract class WriteToIndexOperation extends IndexOperation {

    public WriteToIndexOperation(LuceneIndexManager mgr) {
        super(mgr);
    }

    private static final Logger log =
            LoggerFactory.getLogger(WriteToIndexOperation.class);

    /**
     * Runs the write holding the manager's write lock, which serialises
     * writers only; searches never take it. Before the lock is released --
     * and so before the operation is done -- the manager's searchers are
     * refreshed, so a search that starts once this returns sees the write.
     */
    @Override
    public void run() {
        ReentrantLock lock = manager.getWriteLock();
        lock.lock();
        try {
            writeThenRefresh();
        } finally {
            lock.unlock();
        }
    }

    private void writeThenRefresh() {
        try {
            log.debug("Starting search index operation");
            doRun();
            log.debug("Search index operation complete");
        } catch (Exception e) {
            log.error("Error writing to the search index", e);
        } finally {
            // whether or not the write went through, and even if it died
            // with an Error after committing: a failed or rolled-back write
            // changed nothing, and the refresh then finds nothing to open
            manager.refreshSearcher();
        }
    }
}
