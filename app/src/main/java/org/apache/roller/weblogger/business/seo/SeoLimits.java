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
package org.apache.roller.weblogger.business.seo;

/**
 * The lengths a search result shows of an entry, in characters: the one
 * place the SEO checks, the audits and the editor's counters read them from,
 * so an author is never told two different numbers.
 */
public final class SeoLimits {

    /** Longer page titles are cut off in a search result. */
    public static final int TITLE_MAX = 60;

    /** Longer search descriptions are cut off in a search result. */
    public static final int DESCRIPTION_MAX = 160;

    /** Shorter search descriptions tend to be replaced by text from the page. */
    public static final int DESCRIPTION_MIN = 50;

    private SeoLimits() {
    }
}
