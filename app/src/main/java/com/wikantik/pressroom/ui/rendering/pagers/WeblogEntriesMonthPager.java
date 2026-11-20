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

import java.util.List;
import com.wikantik.pressroom.business.URLStrategy;
import com.wikantik.pressroom.business.Weblogger;
import com.wikantik.pressroom.pojos.Weblog;

/** A pager over one month of entries. */
public class WeblogEntriesMonthPager extends WeblogEntriesPeriodPager {

    public WeblogEntriesMonthPager(
            URLStrategy        strat,
            Weblogger          weblogger,
            Weblog             weblog,
            String             locale,
            String             pageLink,
            String             entryAnchor,
            String             dateString,
            String             catName,
            List<String>       tags,
            int                page) {
        super(Period.MONTH, strat, weblogger, weblog, locale, pageLink, entryAnchor, dateString,
                catName, tags, page);
    }
}
