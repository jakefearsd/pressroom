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
package com.wikantik.pressroom.business.search;

import com.wikantik.pressroom.pojos.wrapper.WeblogEntryWrapper;

/**
 * One ranked result. Velocity templates read it, and Velocity does not see
 * record accessors, so the JavaBean getters are the contract.
 *
 * @param entry       the post
 * @param snippetHtml HTML-safe passage with the search words marked, or "" when none
 * @param otherBlog   true when the post belongs to a blog other than the one searched
 */
public record SearchHit(WeblogEntryWrapper entry, String snippetHtml, boolean otherBlog) {

    public WeblogEntryWrapper getEntry() {
        return entry;
    }

    public String getSnippetHtml() {
        return snippetHtml;
    }

    public boolean isOtherBlog() {
        return otherBlog;
    }
}
