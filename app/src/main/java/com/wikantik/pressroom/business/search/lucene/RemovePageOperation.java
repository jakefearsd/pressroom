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
package com.wikantik.pressroom.business.search.lucene;

import java.io.IOException;

import org.apache.lucene.index.IndexWriter;

/**
 * Takes one page's document out of the index. Needs nothing from the
 * database: it runs after the page's row is gone.
 */
public class RemovePageOperation extends WriteToIndexOperation {

    private final String pageId;

    public RemovePageOperation(LuceneIndexManager mgr, String pageId) {
        super(mgr, null);
        this.pageId = pageId;
    }

    @Override
    protected void write(IndexWriter writer) throws IOException {
        writer.deleteDocuments(IndexUtil.document(FieldConstants.KIND_PAGE, pageId));
    }
}
