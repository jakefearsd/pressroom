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

package com.wikantik.pressroom.business;

import java.util.List;
import java.util.Map;
import com.wikantik.pressroom.pojos.Weblog;


/**
 * An interface representing Pressroom's url strategy.
 *
 * Implementations of this interface provide methods which can be used to form
 * all of the public urls used by Pressroom.
 */
public interface URLStrategy {
    
    /**
     * Get a version of this url strategy meant for use in previewing and set
     * it to preview a given theme.
     */
    URLStrategy getPreviewURLStrategy(String previewTheme);
    
    
    /**
     * Url to login page.
     */
    String getLoginURL(boolean absolute);
    
    
    /**
     * Url to logout page.
     */
    String getLogoutURL(boolean absolute);
    
    /**
     * Get a url to a UI action in a given namespace, optionally specifying
     * a weblogHandle parameter if that is needed by the action.
     */
    String getActionURL(String action,
                                            String namespace,
                                            String weblogHandle,
                                            Map<String, String> parameters,
                                            boolean absolute);
    
    
    /**
     * Get a url to add a new weblog entry.
     */
    String getEntryAddURL(String weblogHandle,
                                              boolean absolute);
    
    
    /**
     * Get a url to edit a specific weblog entry.
     */
    String getEntryEditURL(String weblogHandle,
                                               String entryId,
                                               boolean absolute);
    
    
    /**
     * Get a url to weblog config page.
     */
    String getWeblogConfigURL(String weblogHandle,
                                                  boolean absolute);
    
    
    /**
     * Get OpenSearch compatible search URL template for weblog search page.
     */
    String getWeblogSearchPageURLTemplate(Weblog weblog);

    
    /**
     * Get root url for a given weblog.  Optionally for a certain locale.
     */
    String getWeblogURL(Weblog weblog,
                                            String locale,
                                            boolean absolute);
    
    
    /**
     * Get url for a single weblog entry on a given weblog.
     */
    String getWeblogEntryURL(Weblog weblog,
                                                 String locale,
                                                 String entryAnchor,
                                                 boolean absolute);
    
    
    /**
     * Get url for a single mediafile on a given weblog.
     */
    String getMediaFileURL(Weblog weblog, String fileAnchor,
                                                 boolean absolute);

    /**
     * Get url for a single mediafile thumbnail on a given weblog.
     */
    String getMediaFileThumbnailURL(Weblog weblog,
                                                String fileAnchor,
                                                boolean absolute);

    /**
     * Get url for a collection of entries on a given weblog.
     */
    String getWeblogCollectionURL(Weblog weblog,
                                                      String locale,
                                                      String category,
                                                      String dateString,
                                                      List<String> tags,
                                                      int pageNum,
                                                      boolean absolute);
    
    
    /**
     * Get url for a custom page on a given weblog.
     */
    String getWeblogPageURL(Weblog weblog,
                                                String locale,
                                                String pageLink,
                                                String entryAnchor,
                                                String category,
                                                String dateString,
                                                List<String> tags,
                                                int pageNum,
                                                boolean absolute);
    
    
    /**
     * Get url for a published static Page, which is served at
     * {@code /<handle>/<slug>} (not the {@code /page/<slug>} route of
     * {@link #getWeblogPageURL}, which serves CUSTOM templates).
     */
    String getWeblogStaticPageURL(Weblog weblog, String slug, boolean absolute);


    /**
     * Get url for a feed on a given weblog.
     */
    String getWeblogFeedURL(Weblog weblog,
                                                String locale,
                                                String type,
                                                String format,
                                                String category,
                                                String term,
                                                List<String> tags,
                                                boolean excerpts,
                                                boolean absolute);
    
    
    /**
     * Get url to search endpoint on a given weblog.
     */
    String getWeblogSearchURL(Weblog weblog,
                                                  String locale,
                                                  String query,
                                                  String category,
                                                  int pageNum,
                                                  boolean absolute);
    
    
    /**
     * Get url to a resource on a given weblog.
     */
    String getWeblogResourceURL(Weblog weblog,
                                                    String filePath,
                                                    boolean absolute);
    
    


}
