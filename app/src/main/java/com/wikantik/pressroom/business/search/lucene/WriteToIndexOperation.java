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
package com.wikantik.pressroom.business.search.lucene;

import java.io.IOException;
import java.util.concurrent.locks.ReentrantLock;

import org.apache.lucene.analysis.miscellaneous.LimitTokenCountAnalyzer;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.wikantik.pressroom.business.Weblogger;
import com.wikantik.pressroom.config.WebloggerConfig;

/**
 * An operation that writes to index. It owns the whole write protocol, in
 * one place: {@link #prepare()} (re-read what the job names; false aborts),
 * open the writer, {@link #write(IndexWriter)}, {@link #afterWrite()} on
 * success or {@link #onFailure(IndexWriter)} on failure, close the writer.
 *
 * <p><b>Release rule.</b> An operation constructed with a {@link Weblogger}
 * releases it exactly once, in the final {@code finally} of {@link #doRun()},
 * after the writer is closed, whether the job aborted in {@code prepare},
 * failed, or succeeded: every operation runs on a worker thread, and that
 * thread's persistence session is the operation's to give back. An operation
 * with none (a page removal needs no database) releases nothing.
 *
 * @author Mindaugas Idzelis (min@idzelis.com)
 */
public abstract class WriteToIndexOperation extends IndexOperation {

    private static final Logger log =
            LoggerFactory.getLogger(WriteToIndexOperation.class);

    private final Weblogger toRelease;
    // Must stay open for the IndexWriter's whole lifetime -- IndexWriterConfig
    // only holds a reference to it, and Lucene re-uses it on every add/commit.
    // Closed alongside the writer, never earlier.
    private LimitTokenCountAnalyzer analyzer;

    protected WriteToIndexOperation(LuceneIndexManager mgr, Weblogger toRelease) {
        super(mgr);
        this.toRelease = toRelease;
    }

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

    /**
     * Re-reads whatever the job names (it may run on another thread, on a
     * detached object) before the writer opens.
     *
     * @return false to abort: nothing is written
     */
    protected boolean prepare() {
        return true;
    }

    /** The index change itself; a failure here is logged and ends the job. */
    protected abstract void write(IndexWriter writer) throws IOException;

    /** Runs after a successful write, with the writer still open. */
    protected void afterWrite() {
        // most operations have nothing to do
    }

    /** Runs when {@link #write} failed, before the writer is closed. */
    protected void onFailure(IndexWriter writer) {
        // most operations have nothing to undo
    }

    /** The template: prepare, write, close, release. Not overridable. */
    @Override
    protected final void doRun() {
        try {
            if (prepare()) {
                writeWithWriter();
            }
        } finally {
            if (toRelease != null) {
                toRelease.release();
            }
        }
    }

    // The writer is closed in endWriting() (the field alias), which PMD's
    // CloseResource cannot see.
    @SuppressWarnings("PMD.CloseResource")
    private void writeWithWriter() {
        IndexWriter writer = beginWriting();
        if (writer == null) {
            return;
        }
        try {
            write(writer);
            afterWrite();
        } catch (IOException | RuntimeException e) {
            log.error("Problems writing to the search index; operation {} failed",
                    getClass().getSimpleName(), e);
            onFailure(writer);
        } finally {
            endWriting(writer);
        }
    }

    /** Opens a writer, or null (logged) when the index cannot be opened. */
    private IndexWriter beginWriting() {
        try {
            // a wrapper of the manager's shared analyzer: closing it in
            // endWriting() does not close the one it wraps
            analyzer = new LimitTokenCountAnalyzer(
                    manager.getAnalyzer(),
                    WebloggerConfig.getIntProperty("lucene.analyzer.maxTokenCount"));
            return new IndexWriter(manager.getIndexDirectory(), new IndexWriterConfig(analyzer));
        } catch (IOException e) {
            log.error("ERROR creating writer", e);
            endWriting(null);
            return null;
        }
    }

    private void endWriting(IndexWriter writer) {
        if (writer != null) {
            try {
                writer.close();
            } catch (IOException e) {
                log.error("ERROR closing writer", e);
            }
        }
        // Close only after the writer is fully closed above -- the writer
        // uses the analyzer during its own close()/commit.
        if (analyzer != null) {
            analyzer.close();
            analyzer = null;
        }
    }
}
