/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  The ASF licenses this file to You
 * under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.  For additional information regarding
 * copyright in this work, please see the NOTICE file in the top level
 * directory of this distribution.
 */

package com.wikantik.pressroom.pojos.wrapper;

import java.sql.Timestamp;
import com.wikantik.pressroom.business.Weblogger;
import com.wikantik.pressroom.pojos.WeblogEntryTag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * Pojo safety wrapper for WeblogEntryTag objects.
 */
public final class WeblogEntryTagWrapper {

    private static final Logger log = LoggerFactory.getLogger(WeblogEntryTagWrapper.class);

    // keep a reference to the wrapped pojo
    private final WeblogEntryTag pojo;

    // the business tier, for the lookups the template API needs
    private final Weblogger weblogger;


    // this is private so that we can force the use of the .wrap(pojo) method
    private WeblogEntryTagWrapper(WeblogEntryTag toWrap, Weblogger weblogger) {
        this.pojo = toWrap;
        this.weblogger = weblogger;
    }


    // wrap the given pojo if it is not null
    public static WeblogEntryTagWrapper wrap(WeblogEntryTag toWrap, Weblogger weblogger) {
        if (toWrap != null) {
            return new WeblogEntryTagWrapper(toWrap, weblogger);
        }

        return null;
    }

    /**
     * The tagger, resolved by name through the tier this wrapper was given;
     * null (never an exception) when the name no longer resolves.
     */
    public UserWrapper getUser() {
        try {
            return UserWrapper.wrap(weblogger.getUserManager()
                    .getUserByUserName(this.pojo.getCreatorUserName()));
        } catch (Exception e) {
            log.error("ERROR fetching user object for username: {}", this.pojo.getCreatorUserName(), e);
            return null;
        }
    }
    
    
    public String getName() {
        return this.pojo.getName();
    }
    
    
    public Timestamp getTime() {
        return this.pojo.getTime();
    }
    
}
