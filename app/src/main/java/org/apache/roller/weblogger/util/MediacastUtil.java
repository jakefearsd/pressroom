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

package org.apache.roller.weblogger.util;

import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * Utility for deailing with mediacast files.
 */
public final class MediacastUtil {
    
    private static final Logger log = LoggerFactory.getLogger(MediacastUtil.class);
    
    public static final int BAD_URL = 1;
    public static final int CHECK_FAILED = 2;
    public static final int BAD_RESPONSE = 3;
    public static final int INCOMPLETE = 4;
    
    
    // non-instantiable
    private MediacastUtil() {}
    
    
    /**
     * Validate a Mediacast resource.
     */
    public static MediacastResource lookupResource(String url)
            throws MediacastException {
        
        if(url == null || url.isBlank()) {
            return null;
        }
        
        MediacastResource resource;
        try {
            HttpURLConnection con = (HttpURLConnection) toUrl(url).openConnection();
            con.setRequestMethod("HEAD");
            int response = con.getResponseCode();
            String message = con.getResponseMessage();
            
            if(response != 200) {
                log.debug("Mediacast error {}:{} from url {}", response, message, url);
                throw new MediacastException(BAD_RESPONSE, "weblogEdit.mediaCastResponseError");
            } else {
                String contentType = con.getContentType();
                long length = con.getContentLength();

                if(contentType == null || length == -1) {
                    log.debug("Response valid, but contentType or length is invalid");
                    throw new MediacastException(INCOMPLETE, "weblogEdit.mediaCastLacksContentTypeOrLength");
                }

                resource = new MediacastResource(url, contentType, length);
                log.debug("Valid mediacast resource = {}", resource);

            }
        } catch (MalformedURLException mfue) {
            log.debug("Malformed MediaCast url: {}", url);
            throw new MediacastException(BAD_URL, "weblogEdit.mediaCastUrlMalformed", mfue);
        } catch (MediacastException me) {
            // Already diagnosed above (bad response, or missing content type or
            // length). Without this the generic handler below would swallow it
            // and the editor would report "failed fetching info" for every
            // failure, whatever actually went wrong.
            throw me;
        } catch (Exception e) {
            log.error("ERROR while checking MediaCast URL: {}: {}", url, e.getMessage());
            throw new MediacastException(CHECK_FAILED, "weblogEdit.mediaCastFailedFetchingInfo", e);
        }
        return resource;
    }

    /**
     * Parses through {@link URI}, since {@code new URL(String)} is deprecated.
     * An unparseable or relative string is reported as the
     * {@link MalformedURLException} the caller already maps to {@code BAD_URL}.
     * Trade-off: a URL with a raw space, which the old constructor let through,
     * is now refused as malformed -- it was never a valid URL.
     */
    private static URL toUrl(String url) throws MalformedURLException {
        try {
            return new URI(url).toURL();
        } catch (URISyntaxException | IllegalArgumentException e) {
            MalformedURLException bad = new MalformedURLException(e.getMessage());
            bad.initCause(e);
            throw bad;
        }
    }

}
