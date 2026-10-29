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

package com.wikantik.pressroom.ui.controllers.editor;

import org.apache.commons.lang3.StringUtils;
import com.wikantik.pressroom.pojos.WeblogPage;

/**
 * Form bean for adding/editing a {@link WeblogPage}, shaped like
 * {@link EntryBean}: a flat set of Strings the JSP binds by name, with
 * {@code copyTo}/{@code copyFrom} doing the conversion to and from the
 * entity.
 */
public class PageBean {

    private String id = null;
    private String slug = null;
    private String title = null;
    private String content = null;
    // Defaulting to DRAFT here (as WeblogPage.status itself defaults) means a
    // freshly-submitted "new page" form -- which carries no status field at
    // all until the JSP grows one -- still creates a draft rather than an
    // NPE or an accidental publish.
    private String status = WeblogPage.PubStatus.DRAFT.name();
    private boolean showInNav = true;
    private int navOrder = 0;

    private String metaTitle = null;
    private String searchDescription = null;
    private String canonicalUrl = null;
    private boolean noindex = false;
    private String ogImageId = null;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getSlug() {
        return slug;
    }

    public void setSlug(String slug) {
        this.slug = slug;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public boolean getShowInNav() {
        return showInNav;
    }

    public void setShowInNav(boolean showInNav) {
        this.showInNav = showInNav;
    }

    public int getNavOrder() {
        return navOrder;
    }

    public void setNavOrder(int navOrder) {
        this.navOrder = navOrder;
    }

    public String getMetaTitle() {
        return metaTitle;
    }

    public void setMetaTitle(String metaTitle) {
        this.metaTitle = metaTitle;
    }

    public String getSearchDescription() {
        return searchDescription;
    }

    public void setSearchDescription(String searchDescription) {
        this.searchDescription = searchDescription;
    }

    public String getCanonicalUrl() {
        return canonicalUrl;
    }

    public void setCanonicalUrl(String canonicalUrl) {
        this.canonicalUrl = canonicalUrl;
    }

    public boolean getNoindex() {
        return noindex;
    }

    public void setNoindex(boolean noindex) {
        this.noindex = noindex;
    }

    public String getOgImageId() {
        return ogImageId;
    }

    public void setOgImageId(String ogImageId) {
        this.ogImageId = ogImageId;
    }

    /**
     * Applies this bean's fields onto {@code page}. The id and the weblog are
     * deliberately not touched here -- both are the caller's business
     * (creation attaches the action weblog; editing already has an
     * ownership-checked page), not something a posted form gets to set.
     */
    public void copyTo(WeblogPage page) {
        page.setSlug(slug);
        page.setTitle(title);
        page.setContent(content);
        page.setStatus(parseStatus(status));
        page.setShowInNav(showInNav);
        page.setNavOrder(navOrder);
        page.setMetaTitle(metaTitle);
        page.setSearchDescription(searchDescription);
        page.setCanonicalUrl(canonicalUrl);
        page.setNoindex(noindex);
        page.setOgImageId(ogImageId);
    }

    /**
     * A blank or unrecognized status defaults to DRAFT rather than throwing --
     * the same reasoning as {@code WeblogEntry.JsonLdType.fromString}: a
     * status the form somehow failed to carry, or a crafted POST carrying
     * garbage ({@code bean.status=BOGUS}), must not turn into a 500. This is
     * genuinely lenient (catches {@link IllegalArgumentException}), not just
     * a blank check -- {@code PubStatus.valueOf} throws on any unrecognized
     * name, and a blank check alone leaves every other bad value unguarded.
     */
    private static WeblogPage.PubStatus parseStatus(String value) {
        if (!StringUtils.isBlank(value)) {
            try {
                return WeblogPage.PubStatus.valueOf(value);
            } catch (IllegalArgumentException ignored) {
                // falls through to the DRAFT default below
            }
        }
        return WeblogPage.PubStatus.DRAFT;
    }

    /** Populates this bean from an existing page, for the edit form. */
    public void copyFrom(WeblogPage page) {
        this.id = page.getId();
        this.slug = page.getSlug();
        this.title = page.getTitle();
        this.content = page.getContent();
        this.status = page.getStatus() == null ? WeblogPage.PubStatus.DRAFT.name() : page.getStatus().name();
        this.showInNav = page.getShowInNav() != null && page.getShowInNav();
        this.navOrder = page.getNavOrder();
        this.metaTitle = page.getMetaTitle();
        this.searchDescription = page.getSearchDescription();
        this.canonicalUrl = page.getCanonicalUrl();
        this.noindex = page.getNoindex() != null && page.getNoindex();
        this.ogImageId = page.getOgImageId();
    }
}
