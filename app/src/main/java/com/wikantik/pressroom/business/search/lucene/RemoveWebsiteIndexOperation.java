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
/* Created on Jul 16, 2003 */
package com.wikantik.pressroom.business.search.lucene;

import java.io.IOException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.Term;
import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.business.Weblogger;
import com.wikantik.pressroom.pojos.Weblog;

/**
 * An index operation that rebuilds a given users index (or all indexes).
 * 
 * @author Mindaugas Idzelis (min@idzelis.com)
 */
public class RemoveWebsiteIndexOperation extends WriteToIndexOperation {

    // ~ Static fields/initializers
    // =============================================

    private static final Logger log = LoggerFactory.getLogger(
            RemoveWebsiteIndexOperation.class);

    // ~ Instance fields
    // ========================================================

    private final Weblog website;
    private final Weblogger pressroom;
    private String handle;
    private String name;

    // ~ Constructors
    // ===========================================================

    /**
     * Create a new operation that will recreate an index.
     * 
     * @param website
     *            The website to rebuild the index for, or null for all sites.
     */
    public RemoveWebsiteIndexOperation(Weblogger pressroom, LuceneIndexManager mgr,
            Weblog website) {
        super(mgr, pressroom);
        this.pressroom = pressroom;
        this.website = website;
    }

    // ~ Methods
    // ================================================================

    @Override
    protected boolean prepare() {
        // The handle as it was given: a weblog being deleted is removed from
        // the index after its delete commits, when it can no longer be
        // re-queried. A weblog that still exists is re-queried, since this
        // operation can run on a separate thread and the object passed in is
        // detached.
        try {
            Weblog current = pressroom.getWeblogManager().getWeblog(website.getId());
            Weblog named = current != null ? current : website;
            handle = named.getHandle();
            name = named.getName();
        } catch (WebloggerException ex) {
            log.error("Error getting website object", ex);
            return false;
        }
        return true;
    }

    @Override
    protected void write(IndexWriter writer) throws IOException {
        Term tHandle = IndexUtil.handleTerm(handle);
        if (tHandle != null) {
            writer.deleteDocuments(tHandle);
        }
    }

    @Override
    protected void afterWrite(double length) {
        log.info("Completed deleting indices for website '{}' in '{}' seconds", name, length);
    }
}
