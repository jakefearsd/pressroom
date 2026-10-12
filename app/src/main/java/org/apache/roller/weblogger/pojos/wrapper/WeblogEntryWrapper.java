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

package org.apache.roller.weblogger.pojos.wrapper;

import java.sql.Timestamp;
import java.util.List;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.roller.weblogger.business.URLStrategy;
import org.apache.roller.weblogger.business.Weblogger;
import org.apache.roller.weblogger.pojos.JsonLdType;
import org.apache.roller.weblogger.pojos.MediaFile;
import org.apache.roller.weblogger.pojos.WeblogEntry;
import org.apache.roller.weblogger.pojos.WeblogEntry.PubStatus;
import org.apache.roller.weblogger.pojos.WeblogEntryTagComparator;
import org.apache.roller.weblogger.util.HTMLSanitizer;


/**
 * Pojo safety wrapper for WeblogEntry objects.
 */
public final class WeblogEntryWrapper {

    private static final Logger log = LoggerFactory.getLogger(WeblogEntryWrapper.class);

    // keep a reference to the wrapped pojo
    private final WeblogEntry pojo;
    
    // url strategy to use for any url building
    private final URLStrategy urlStrategy;

    // the business tier, for the lookups the template API needs
    private final Weblogger weblogger;
    
    
    // this is private so that we can force the use of the .wrap(pojo) method
    private WeblogEntryWrapper(WeblogEntry toWrap, URLStrategy strat, Weblogger weblogger) {
        this.pojo = toWrap;
        this.urlStrategy = strat;
        this.weblogger = weblogger;
    }
    
    
    /**
     * Wrap the given pojo if it is not null. The wrapper is the template API
     * and does its own lookups: it is handed the (possibly preview-aware)
     * {@link URLStrategy} every URL it emits must come from, and the business
     * tier it resolves related objects through -- never a static locator.
     */
    public static WeblogEntryWrapper wrap(WeblogEntry toWrap, URLStrategy strat, Weblogger weblogger) {
        if(toWrap != null) {
            return new WeblogEntryWrapper(toWrap, strat, weblogger);
        }
        return null;
    }
    
    
    public String getId() {
        return this.pojo.getId();
    }
    
    
    public WeblogCategoryWrapper getCategory() {
        return WeblogCategoryWrapper.wrap(this.pojo.getCategory(), urlStrategy, weblogger);
    }
    
    
    public List<WeblogCategoryWrapper> getCategories() {      
        return this.pojo.getCategories().stream()
                .map(cat -> WeblogCategoryWrapper.wrap(cat, urlStrategy, weblogger))
                .collect(Collectors.toList());
    }
    
    
    public WeblogWrapper getWebsite() {
        return WeblogWrapper.wrap(this.pojo.getWebsite(), urlStrategy, weblogger);
    }
    
    
    /**
     * The author, resolved by name through the tier this wrapper was given;
     * null (never an exception) when the name no longer resolves, so a
     * byline cannot break a page.
     */
    public UserWrapper getCreator() {
        try {
            return UserWrapper.wrap(weblogger.getUserManager()
                    .getUserByUserName(this.pojo.getCreatorUserName()));
        } catch (Exception e) {
            log.error("ERROR fetching user object for username: {}", this.pojo.getCreatorUserName(), e);
            return null;
        }
    }
    
    
    public String getTitle() {
        return HTMLSanitizer.conditionallySanitizeText(this.pojo.getTitle());
	}

    
    public String getSummary() {
        return HTMLSanitizer.conditionallySanitize(this.pojo.getSummary());
    }
    
    /**
     * Simply returns the same value that the pojo would have returned.
     */
    public String getText() {
        return HTMLSanitizer.conditionallySanitize(this.pojo.getText());
    }

    
    
    public String getAnchor() {
        return this.pojo.getAnchor();
    }
    
    
    public List<WeblogEntryAttributeWrapper> getEntryAttributes() {
        return this.pojo.getEntryAttributes().stream()
                .map(WeblogEntryAttributeWrapper::wrap)
                .collect(Collectors.toList());
    }
    
    
    public String findEntryAttribute(String name) {
        return this.pojo.findEntryAttribute(name);
    }
    
    
    public Timestamp getPubTime() {
        return this.pojo.getPubTime();
    }
    
    
    public Timestamp getUpdateTime() {
        return this.pojo.getUpdateTime();
    }
    
    
    public PubStatus getStatus() {
        return this.pojo.getStatus();
    }
    
    
    public String getLink() {
        return this.pojo.getLink();
    }
    
    
    public Boolean getRightToLeft() {
        return this.pojo.getRightToLeft();
    }
    
    
    public Boolean getPinnedToMain() {
        return this.pojo.getPinnedToMain();
    }
    
    
    public String getLocale() {
        return this.pojo.getLocale();
    }
    
    
    public List<WeblogEntryTagWrapper> getTags() {
        return this.pojo.getTags().stream()
                .sorted(new WeblogEntryTagComparator()) // by name
                .map(tag -> WeblogEntryTagWrapper.wrap(tag, weblogger))
                .collect(Collectors.toList());
    }
    
    
    public String getTagsAsString() {
        return this.pojo.getTagsAsString();
    }


    public String formatPubTime(String pattern) {
        return this.pojo.formatPubTime(pattern);
    }
    
    
    public String formatUpdateTime(String pattern) {
        return this.pojo.formatUpdateTime(pattern);
    }
    
    
    /**
     * Absolute permalink, built from the strategy this wrapper was given --
     * so a theme preview links within the preview and the live site links to
     * itself, whichever strategy the caller installed.
     */
    public String getPermalink() {
        return urlStrategy.getWeblogEntryURL(this.pojo.getWebsite(), null, this.pojo.getAnchor(), true);
    }
    
    
    /**
     * @deprecated Use getPermalink() instead
     */
    @Deprecated
    @SuppressWarnings("deprecation")
    public String getPermaLink() {
        return this.pojo.getPermaLink();
    }


    public String getDisplayTitle() {
        return this.pojo.getDisplayTitle();
    }
    
    
    public String getRss09xDescription() {
        return this.pojo.getRss09xDescription();
    }
    
    
    public String getRss09xDescription(int maxLength) {
        return this.pojo.getRss09xDescription(maxLength);
    }


    public String getTransformedText() {
        return weblogger.getEntryRenderer().transformedText(this.pojo);
    }
    
    
    public String getTransformedSummary() {
        return weblogger.getEntryRenderer().transformedSummary(this.pojo);
    }
    
    
    public String displayContent(String readMoreLink) {
        return weblogger.getEntryRenderer().displayContent(this.pojo, readMoreLink);
    }
    
    
    public String getDisplayContent() {
        return weblogger.getEntryRenderer().displayContent(this.pojo, null);
    }

	public String getSearchDescription() {
        return HTMLSanitizer.conditionallySanitizeText(this.pojo.getSearchDescription());
	}

    public String getFeaturedImageId() {
        return this.pojo.getFeaturedImageId();
    }

    /**
     * Resolves {@link #getFeaturedImageId()} to its {@link MediaFileWrapper},
     * or null if no featured image is set, or if the id no longer resolves
     * (the media file was deleted independently -- see the javadoc on
     * {@link WeblogEntry#getFeaturedImageId()}).
     */
    public MediaFileWrapper getFeaturedImage() {
        return resolveMediaFile(this.pojo.getFeaturedImageId());
    }

    public String getMetaTitle() {
        return HTMLSanitizer.conditionallySanitizeText(this.pojo.getMetaTitle());
    }

    public String getOgImageId() {
        return this.pojo.getOgImageId();
    }

    /**
     * Resolves {@link #getOgImageId()} to its {@link MediaFileWrapper}, or
     * null if no distinct Open Graph image is set (callers fall back to
     * {@link #getFeaturedImage()}) or if the id no longer resolves.
     */
    public MediaFileWrapper getOgImage() {
        return resolveMediaFile(this.pojo.getOgImageId());
    }

    public String getCanonicalUrl() {
        return this.pojo.getCanonicalUrl();
    }

    public Boolean getNoindex() {
        return this.pojo.getNoindex();
    }

    public Boolean getCommentsClosed() {
        return this.pojo.getCommentsClosed();
    }

    public JsonLdType getJsonLdType() {
        return this.pojo.getJsonLdType();
    }

    public Double getGeoLatitude() {
        return this.pojo.getGeoLatitude();
    }

    public Double getGeoLongitude() {
        return this.pojo.getGeoLongitude();
    }

    public Timestamp getEventStart() {
        return this.pojo.getEventStart();
    }

    public Timestamp getEventEnd() {
        return this.pojo.getEventEnd();
    }

    public String getEventLocation() {
        return HTMLSanitizer.conditionallySanitizeText(this.pojo.getEventLocation());
    }

    private MediaFileWrapper resolveMediaFile(String mediaFileId) {
        if (mediaFileId == null) {
            return null;
        }
        try {
            MediaFile mediaFile = weblogger.getMediaFileManager().getMediaFile(mediaFileId);
            return MediaFileWrapper.wrap(mediaFile, urlStrategy, weblogger);
        } catch (Exception e) {
            log.debug("Could not resolve media file {}", mediaFileId, e);
            return null;
        }
    }

    /**
     * this is a special method to access the original pojo.
     * we don't really want to do this, but it's necessary
     * because some parts of the rendering process still need the
     * orginal pojo object.
     */
    public WeblogEntry getPojo() {
        return this.pojo;
    }
    
}
