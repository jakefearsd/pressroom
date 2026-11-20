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


package com.wikantik.pressroom.ui.rendering.pagers;

import java.util.Date;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.wikantik.pressroom.business.URLStrategy;
import com.wikantik.pressroom.business.Weblogger;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.pojos.WeblogEntrySearchCriteria;
import com.wikantik.pressroom.pojos.wrapper.WeblogEntryWrapper;

/**
 * A pager whose entries come from one search that a subclass narrows: it owns
 * the lazily-loaded day map, the "more entries" flag and the log-and-carry-on
 * handling of a failed query, which the latest, day and month pagers used to
 * each copy.
 */
abstract class QueriedEntriesPager extends AbstractWeblogEntriesPager {

    private static final Logger log = LoggerFactory.getLogger(QueriedEntriesPager.class);

    private Map<Date, List<WeblogEntryWrapper>> entries;

    /** Are there more pages? */
    private boolean more;

    QueriedEntriesPager(URLStrategy strat, Weblogger weblogger, Weblog weblog, String locale,
            String pageLink, String entryAnchor, String dateString, String catName,
            List<String> tags, int page) {
        super(strat, weblogger, weblog, locale, pageLink, entryAnchor, dateString, catName, tags, page);
    }

    /** Sets whatever range or filter is particular to this pager on the search. */
    protected abstract void narrow(WeblogEntrySearchCriteria wesc);

    @Override
    public final Map<Date, List<WeblogEntryWrapper>> getEntries() {
        if (entries == null) {
            entries = newDayMap();
            try {
                WeblogEntrySearchCriteria wesc = new WeblogEntrySearchCriteria();
                narrow(wesc);
                more = queryEntries(wesc, entries);
            } catch (Exception e) {
                log.error("ERROR: getting entry month map", e);
            }
        }
        return entries;
    }

    @Override
    public boolean hasMoreEntries() {
        return more;
    }
}
