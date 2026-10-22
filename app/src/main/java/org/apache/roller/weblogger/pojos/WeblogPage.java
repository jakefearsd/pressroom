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
package org.apache.roller.weblogger.pojos;

import java.io.Serializable;
import java.sql.Timestamp;

import org.apache.commons.lang3.builder.HashCodeBuilder;
import org.apache.roller.weblogger.business.shortcodes.ShortcodeContext;

/**
 * A static page: content authored in Markdown, outside the entry chronology.
 *
 * <p>Deliberately not a {@link WeblogEntry} with a flag -- see the header
 * comment on {@code V014__weblog_pages.sql} for why. A page has no category,
 * tags, pubtime, comment settings, or locale.
 */
public class WeblogPage implements ShortcodeContext, Serializable {

    private static final long serialVersionUID = 1L;

    /** A page's publication state. */
    public enum PubStatus {
        DRAFT, PUBLISHED
    }

    private String id = EntityIds.newId();
    private Weblog weblog;
    private String slug;
    private transient String loadedSlug;
    private String title;
    private String content;
    private PubStatus status = PubStatus.DRAFT;
    private Boolean showInNav = Boolean.TRUE;
    private int navOrder = 0;
    private Timestamp created;
    private Timestamp updated;
    private String metaTitle;
    private String searchDescription;
    private String canonicalUrl;
    private Boolean noindex = Boolean.FALSE;
    private String ogImageId;

    public WeblogPage() {
    }

    /**
     * Database surrogate key.
     */
    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    /**
     * The weblog this page belongs to.
     */
    @Override
    public Weblog getWeblog() {
        return weblog;
    }

    public void setWeblog(Weblog weblog) {
        this.weblog = weblog;
    }

    /**
     * The URL-safe identifier that resolves this page within its weblog;
     * unique per weblog.
     */
    @Override
    public String getSlug() {
        return slug;
    }

    public void setSlug(String slug) {
        this.slug = slug;
    }

    /**
     * The slug this page was LOADED with, snapshotted by a JPA post-load
     * callback (see {@code WeblogPage.orm.xml}) -- the same mechanism entry
     * revisions use. {@code savePage} only ever sees the caller's new value,
     * so this is how it can tell a rename from any other edit and mint the
     * redirect that keeps the old URL alive. Getter-only on purpose: a
     * property without a setter is not mapped, so no {@code <transient>} row
     * is needed.
     *
     * @return the loaded slug, or {@code null} on an instance that was never
     *         loaded from the store (a brand-new page).
     */
    public String getLoadedSlug() {
        return loadedSlug;
    }

    /** JPA post-load callback; not for application code. */
    public void snapshotLoadedSlug() {
        this.loadedSlug = slug;
    }

    /**
     * The page's title.
     */
    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    /**
     * The page's source text, in Markdown, before shortcode expansion.
     */
    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    /**
     * {@inheritDoc}
     *
     * <p>A page's raw text is its {@link #getContent()}.
     */
    @Override
    public String getRawText() {
        return content;
    }

    /**
     * Whether the page is a draft or published.
     */
    public PubStatus getStatus() {
        return status;
    }

    public void setStatus(PubStatus status) {
        this.status = status;
    }

    /**
     * Whether the page appears in navigation.
     */
    public Boolean getShowInNav() {
        return showInNav;
    }

    public void setShowInNav(Boolean showInNav) {
        this.showInNav = showInNav;
    }

    /**
     * Sort order among pages shown in navigation, ascending.
     */
    public int getNavOrder() {
        return navOrder;
    }

    public void setNavOrder(int navOrder) {
        this.navOrder = navOrder;
    }

    /**
     * When the page was created.
     */
    public Timestamp getCreated() {
        return created;
    }

    public void setCreated(Timestamp created) {
        this.created = created;
    }

    /**
     * When the page was last updated.
     */
    public Timestamp getUpdated() {
        return updated;
    }

    public void setUpdated(Timestamp updated) {
        this.updated = updated;
    }

    /**
     * SEO title override; null falls back to {@link #getTitle()}.
     */
    public String getMetaTitle() {
        return metaTitle;
    }

    public void setMetaTitle(String metaTitle) {
        this.metaTitle = metaTitle;
    }

    /**
     * SEO meta description.
     */
    public String getSearchDescription() {
        return searchDescription;
    }

    public void setSearchDescription(String searchDescription) {
        this.searchDescription = searchDescription;
    }

    /**
     * SEO canonical URL override.
     */
    public String getCanonicalUrl() {
        return canonicalUrl;
    }

    public void setCanonicalUrl(String canonicalUrl) {
        this.canonicalUrl = canonicalUrl;
    }

    /**
     * Whether to emit a {@code noindex} robots directive.
     */
    public Boolean getNoindex() {
        return noindex;
    }

    public void setNoindex(Boolean noindex) {
        this.noindex = noindex;
    }

    /**
     * The {@link MediaFile} id used as the Open Graph / Twitter card image.
     */
    public String getOgImageId() {
        return ogImageId;
    }

    public void setOgImageId(String ogImageId) {
        this.ogImageId = ogImageId;
    }

    // ------------------------------------------------------- Good citizenship

    @Override
    public String toString() {
        return "WeblogPage [id=" + getId() + ", weblog="
                + (getWeblog() == null ? null : getWeblog().getHandle())
                + ", slug=" + getSlug() + "]";
    }

    @Override
    public boolean equals(Object other) {
        if (other == this) {
            return true;
        }
        if (!(other instanceof WeblogPage)) {
            return false;
        }
        final WeblogPage that = (WeblogPage) other;
        return this.getId() != null && this.getId().equals(that.getId());
    }

    @Override
    public int hashCode() {
        return new HashCodeBuilder().append(getId()).toHashCode();
    }

}
