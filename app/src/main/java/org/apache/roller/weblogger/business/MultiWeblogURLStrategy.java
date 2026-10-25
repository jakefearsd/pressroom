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

package org.apache.roller.weblogger.business;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.commons.lang3.StringUtils;
import org.apache.roller.weblogger.config.WebloggerRuntimeConfig;
import org.apache.roller.weblogger.pojos.Weblog;
import org.apache.roller.weblogger.util.URLUtilities;


/**
 * A Weblogger URLStrategy which builds urls for a multi-weblog environment.
 */
public class MultiWeblogURLStrategy extends AbstractURLStrategy {
    
    public MultiWeblogURLStrategy() {}
    
    
    /**
     * @inheritDoc
     */
    @Override
    public URLStrategy getPreviewURLStrategy(String previewTheme) {
        return new PreviewURLStrategy(previewTheme);
    }
    
    
    /**
     * Get root url for a given weblog. Optionally for a certain locale.
     *
     * <p>Every other weblog url on this strategy roots here, so this is the one
     * place virtual hosting changes url generation.
     *
     * <p>The custom domain is read off the WEBLOG, never off the request. The
     * render caches key on the weblog handle and not the host, so one weblog
     * reachable at two hostnames shares a single cached rendering -- and
     * #showSeoHead bakes absolute canonical/og:url values into those bytes.
     * Request-derived urls would let whichever host rendered first stamp its
     * own canonical onto the other's response; weblog-derived urls make the
     * bytes identical by construction.
     */
    @Override
    public String getWeblogURL(Weblog weblog,
                                            String locale,
                                            boolean absolute) {

        if (weblog == null) {
            return null;
        }
        return weblogRoot(weblog, locale, absolute)
                + URLUtilities.getQueryString(commonParams());
    }

    /**
     * The root that every url for this weblog hangs off, with no query string.
     *
     * <p>Separate from {@link #getWeblogURL} because the builders below use it
     * as a PREFIX, and a prefix must not carry a query string. That distinction
     * did not exist before, and PreviewURLStrategy -- whose getWeblogURL
     * appends the previewed theme -- broke every inherited builder because of
     * it: the feed, search and media urls spliced their path onto the end of
     * the theme parameter's value. A subclass changing where urls live
     * overrides this; a subclass adding a parameter overrides
     * {@link #commonParams}.
     */
    protected String weblogRoot(Weblog weblog, String locale, boolean absolute) {

        StringBuilder url = new StringBuilder();
        String customDomain = weblog == null ? null : weblog.getCustomDomain();

        if (customDomain != null && !customDomain.isBlank()) {
            // The weblog owns this hostname, so its root replaces the
            // handle segment -- but NOT the context path. This is one
            // Tomcat context reached under many hostnames, so a deployment
            // under a prefix (e.g. /roller) still needs that prefix on the
            // custom domain: the weblog's root there is "/roller/", not
            // "/". getRelativeContextURL() is "" at the root context and
            // "/roller" under a prefix, so it is correct un-guarded in both
            // the absolute and relative forms.
            if (absolute) {
                url.append("https://").append(customDomain);
            }
            url.append(WebloggerRuntimeConfig.getRelativeContextURL());
            url.append('/');
        } else {
            if (absolute) {
                url.append(WebloggerRuntimeConfig.getAbsoluteContextURL());
            } else {
                url.append(WebloggerRuntimeConfig.getRelativeContextURL());
            }
            // The site space has no handle segment: its pages live at the
            // context root, "<ctx>/<slug>".
            url.append('/');
            if (!weblog.isSiteSpace()) {
                url.append(weblog.getHandle()).append('/');
            }
        }

        if (locale != null) {
            url.append(locale).append('/');
        }

        return url.toString();
    }
    
    
    /**
     * Parameters carried by every url this strategy builds. Empty here; the
     * preview strategy adds the theme being previewed.
     */
    protected Map<String, String> commonParams() {
        return Map.of();
    }


    /**
     * Get url for a single weblog entry on a given weblog.
     */
    @Override
    public String getWeblogEntryURL(Weblog weblog,
                                                 String locale,
                                                 String entryAnchor,
                                                 boolean absolute) {
        
        if(weblog == null || entryAnchor == null) {
            return null;
        }
        
        StringBuilder url = new StringBuilder();
        
        url.append(weblogRoot(weblog, locale, absolute));
        url.append("entry/").append(URLUtilities.encode(entryAnchor));
        
    return url.toString();
}
    
    
    /**
     * Get url for a single weblog media file on a given weblog.
     */
    @Override
    public String getMediaFileURL(
            Weblog weblog,
            String fileAnchor,
            boolean absolute) {
        
        if(fileAnchor == null) {
            return null;
        }
        
        StringBuilder url = new StringBuilder();
        url.append(weblogRoot(weblog, null, absolute));
        url.append("mediaresource");
        url.append('/');
        url.append(URLUtilities.encode(fileAnchor));
        
        return url.toString();
    }


    /**
     * Get url for a single weblog media file on a given weblog.
     */
    @Override
    public String getMediaFileThumbnailURL(Weblog weblog,
            String fileAnchor,
            boolean absolute) {
            
        return getMediaFileURL(weblog, fileAnchor, absolute) + "?t=true";
    }

    
    /**
     * Get url for a collection of entries on a given weblog.
     */
    @Override
    public String getWeblogCollectionURL(Weblog weblog,
                                                      String locale,
                                                      String category,
                                                      String dateString,
                                                      List<String> tags,
                                                      int pageNum,
                                                      boolean absolute) {
        
        if(weblog == null) {
            return null;
        }
        
        StringBuilder pathinfo = new StringBuilder(URL_BUFFER_SIZE);
        Map<String, String> params = new HashMap<>(commonParams());
        
        pathinfo.append(weblogRoot(weblog, locale, absolute));
        
        String cat;
        if("root".equals(category)) {
            cat = null;
        } else {
            cat = category;
        }
        
        if(cat != null && dateString == null) {
            pathinfo.append("category/").append(URLUtilities.encodePath(cat));
            
        } else if(dateString != null && cat == null) {
            pathinfo.append("date/").append(dateString);  
        
        } else if(tags != null && !tags.isEmpty()) {
            pathinfo.append("tags/").append(URLUtilities.getEncodedTagsString(tags));
        } else {
            if(dateString != null) {
                params.put("date", dateString);
            }
            if(cat != null) {
                params.put("cat", cat);
            }
        }

        if(pageNum > 0) {
            params.put("page", Integer.toString(pageNum));
        }
        
        return pathinfo.append(URLUtilities.getQueryString(params)).toString();
    }
    
    
    /**
     * Get url for a custom page on a given weblog.
     */
    @Override
    public String getWeblogPageURL(Weblog weblog,
                                                String locale,
                                                String pageLink,
                                                String entryAnchor,
                                                String category,
                                                String dateString,
                                                List<String> tags,
                                                int pageNum,
                                                boolean absolute) {
        
        if(weblog == null) {
            return null;
        }
        
        StringBuilder pathinfo = new StringBuilder(URL_BUFFER_SIZE);
        Map<String, String> params = new HashMap<>(commonParams());
        
        pathinfo.append(weblogRoot(weblog, locale, absolute));
        
        if(pageLink != null) {
            pathinfo.append("page/").append(URLUtilities.encode(pageLink));
            
            // for custom pages we only allow query params
            if(dateString != null) {
                params.put("date", dateString);
            }
            if(category != null) {
                params.put("cat", category);
            }
            if(tags != null && !tags.isEmpty()) {
                params.put("tags", String.join(" ", tags));
            }
            if(pageNum > 0) {
                params.put("page", Integer.toString(pageNum));
            }
        } else {
            // if there is no page link then this is just a typical collection url
            return getWeblogCollectionURL(weblog, locale, category, dateString, tags, pageNum, absolute);
        }
        
        return pathinfo.append(URLUtilities.getQueryString(params)).toString();
    }
    
    
    /** {@inheritDoc} */
    @Override
    public String getWeblogStaticPageURL(Weblog weblog, String slug, boolean absolute) {
        if (weblog == null || slug == null) {
            return null;
        }
        // The front door's page IS the root, so its link is "<ctx>/", never
        // "<ctx>/<slug>" (which would be a second address for the same page).
        if (weblog.isSiteSpace() && WebloggerRuntimeConfig.isFrontDoorPage(slug)) {
            return weblogRoot(weblog, null, absolute);
        }
        return weblogRoot(weblog, null, absolute) + URLUtilities.encode(slug);
    }


    /**
     * Get url for a feed on a given weblog.
     */
    @Override
    public String getWeblogFeedURL(Weblog weblog,
                                                String locale,
                                                String type,
                                                String format,
                                                String category,
                                                String term,
                                                List<String> tags,
                                                boolean excerpts,
                                                boolean absolute) {
        
        if(weblog == null) {
            return null;
        }
        
        StringBuilder url = new StringBuilder(URL_BUFFER_SIZE);
        
        url.append(weblogRoot(weblog, locale, absolute));
        url.append("feed/").append(type).append('/').append(format);
        
        Map<String, String> params = new HashMap<>(commonParams());
        if(category != null && !category.isBlank()) {
            params.put("cat", category);
        }
        if(tags != null && !tags.isEmpty()) {
          params.put("tags", String.join(" ", tags));
        }
        if(term != null && !term.isBlank()) {
            params.put("q", term.trim());
        }
        if(excerpts) {
            params.put("excerpts", "true");
        }
        
        return url.append(URLUtilities.getQueryString(params)).toString();
    }
    
    
    /**
     * Get url to search endpoint on a given weblog.
     */
    @Override
    public String getWeblogSearchURL(Weblog weblog,
                                                  String locale,
                                                  String query,
                                                  String category,
                                                  int pageNum,
                                                  boolean absolute) {
        
        if(weblog == null) {
            return null;
        }
        
        StringBuilder url = new StringBuilder(URL_BUFFER_SIZE);
        
        url.append(weblogRoot(weblog, locale, absolute));
        url.append("search");
        
        Map<String, String> params = new HashMap<>(commonParams());
        if(query != null) {
            params.put("q", query);
            
            // other stuff only makes sense if there is a query
            if(category != null) {
                params.put("cat", category);
            }
            if(pageNum > 0) {
                params.put("page", Integer.toString(pageNum));
            }
        }
        
        return url.append(URLUtilities.getQueryString(params)).toString();
    }
    
    
    /**
     * Get url to a resource on a given weblog.
     */
    @Override
    public String getWeblogResourceURL(Weblog weblog, String filePath, boolean absolute) {
        
        if(weblog == null || StringUtils.isEmpty(filePath)) {
            return null;
        }
        
        StringBuilder url = new StringBuilder(URL_BUFFER_SIZE);
        
        url.append(weblogRoot(weblog, null, absolute));
        url.append("resource/");
        
        if(filePath.startsWith("/")) {
            url.append(URLUtilities.encodePath(filePath.substring(1)));
        } else {
            url.append(URLUtilities.encodePath(filePath));
        }
        
        return url.toString();
    }
    
    
    @Override
    public String getWeblogSearchPageURLTemplate(Weblog weblog) {
        if(weblog == null) {
            return null;
        }
        
        StringBuilder url = new StringBuilder(URL_BUFFER_SIZE);
        
        url.append(weblogRoot(weblog, null, true));
        url.append("search");

        // Built by hand rather than through getQueryString, and that is the
        // point of the comment: the braces are OpenSearch template
        // placeholders that the CONSUMER substitutes, not values to send. Run
        // through getQueryString they would come back percent-encoded as
        // %7BsearchTerms%7D and no consumer would recognise them. This is the
        // one url on this class whose query string is a template rather than
        // data, which is exactly why it does not share the encoder.
        url.append("?q={searchTerms}&page={startPage}");

        return url.toString();
    }


}
