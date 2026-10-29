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

import com.wikantik.pressroom.WebloggerException;
import java.util.List;
import java.util.Map;
import com.wikantik.pressroom.business.URLStrategy;
import com.wikantik.pressroom.business.Weblogger;
import com.wikantik.pressroom.business.UserManager;
import com.wikantik.pressroom.pojos.User;
import com.wikantik.pressroom.pojos.wrapper.UserWrapper;


/**
 * Paging through a collection of users.
 */
public class UsersPager extends AbstractPager<UserWrapper> {
    
    
    /** The business tier this pager queries; handed in by the model that built it (plan Task 11). */
    private final Weblogger weblogger;

    private String letter = null;
    
    // collection for the pager
    
    // are there more items?
    
    
    public UsersPager(
            URLStrategy    strat,
            Weblogger      weblogger,
            String         baseUrl,
            String         locale,
            int            sinceDays,
            int            page,
            int            length) {
        
        super(strat, baseUrl, page, length);
        this.weblogger = weblogger;
        
        
        // initialize the collection
        getItems();
    }
    
    
    public UsersPager(
            URLStrategy    strat,
            Weblogger      weblogger,
            String         baseUrl,
            String         letter,
            String         locale,
            int            sinceDays,
            int            page,
            int            length) {
        
        super(strat, baseUrl, page, length);
        this.weblogger = weblogger;
        
        this.letter = letter;
        
        // initialize the collection
        getItems();
    }
    
    
    /**
     * A letter-filtered listing has to carry the letter across pages, or page
     * two of "starting with B" silently becomes page two of everything.
     */
    @Override
    protected Map<String, String> linkParams() {
        return letter == null ? Map.of() : Map.of("letter", letter);
    }
    
    
    @Override
    protected List<UserWrapper> fetchPage(int offset, int limit) throws WebloggerException {

        UserManager umgr = weblogger.getUserManager();

        List<User> rawUsers = letter == null
                ? umgr.getUsers(Boolean.TRUE, null, null, offset, limit)
                : umgr.getUsersByLetter(letter.charAt(0), offset, limit);

        return rawUsers.stream().map(UserWrapper::wrap).toList();
    }


    @Override
    protected String itemLabel() {
        return "user";
    }
    
    
    
}
