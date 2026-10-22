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

/**
 * A 301 rule for one weblog-relative URL that would otherwise 404.
 *
 * <p>Both paths are weblog-relative -- no handle, no context path, no query
 * string -- so one rule behaves identically on the site host and on a custom
 * domain, the same invariant the render caches keep by keying on the handle.
 * They are stored in normalized form ({@link #normalizePath}), applied
 * identically at save and at match so what is stored is always matchable.
 */
public class WeblogRedirect implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * Where a rule came from: an operator wrote it, or a page-slug rename
     * minted it. The first question when a redirect fires unexpectedly.
     */
    public enum Origin {
        MANUAL, SLUG_HISTORY
    }

    private String id = EntityIds.newId();
    private Weblog weblog;
    private String sourcePath;
    private String targetPath;
    private Origin origin = Origin.MANUAL;
    private Timestamp createdAt;
    private long hitCount;
    private Timestamp lastHitAt;

    public WeblogRedirect() {
    }

    /**
     * The one normalization, shared by save and match: guarantee nothing --
     * this only canonicalizes spelling. Trailing slashes are stripped (root
     * stays {@code /}) because migrated sites are inconsistent about them
     * and a 404 over a slash defeats the feature's purpose. Case is
     * preserved; URLs are case-sensitive.
     *
     * @return the canonical spelling, or the argument's trimmed form when
     *         there is nothing to strip; {@code null} stays {@code null}.
     */
    public static String normalizePath(String path) {
        if (path == null) {
            return null;
        }
        String normalized = path.trim();
        while (normalized.length() > 1 && normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
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
     * The weblog whose URLs this rule may answer for.
     */
    public Weblog getWeblog() {
        return weblog;
    }

    public void setWeblog(Weblog weblog) {
        this.weblog = weblog;
    }

    /**
     * The weblog-relative path this rule answers, normalized.
     */
    public String getSourcePath() {
        return sourcePath;
    }

    public void setSourcePath(String sourcePath) {
        this.sourcePath = sourcePath;
    }

    /**
     * The weblog-relative path the 301 points at, normalized. Never an
     * external URL -- {@code WeblogRedirectManager.saveRedirect} refuses
     * anything that is not a plain absolute path.
     */
    public String getTargetPath() {
        return targetPath;
    }

    public void setTargetPath(String targetPath) {
        this.targetPath = targetPath;
    }

    public Origin getOrigin() {
        return origin;
    }

    public void setOrigin(Origin origin) {
        this.origin = origin;
    }

    public Timestamp getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Timestamp createdAt) {
        this.createdAt = createdAt;
    }

    /**
     * How many times this rule has been served. Best-effort bookkeeping --
     * see {@code WeblogRedirectManager.recordHit}.
     */
    public long getHitCount() {
        return hitCount;
    }

    public void setHitCount(long hitCount) {
        this.hitCount = hitCount;
    }

    /**
     * When this rule last fired, or {@code null} if it never has. What
     * distinguishes a rule the world still depends on from one that went
     * quiet a year ago.
     */
    public Timestamp getLastHitAt() {
        return lastHitAt;
    }

    public void setLastHitAt(Timestamp lastHitAt) {
        this.lastHitAt = lastHitAt;
    }

    @Override
    public String toString() {
        return "WeblogRedirect[" + sourcePath + " -> " + targetPath
                + ", origin=" + origin + "]";
    }

    @Override
    public boolean equals(Object other) {
        if (other == this) {
            return true;
        }
        if (!(other instanceof WeblogRedirect)) {
            return false;
        }
        WeblogRedirect o = (WeblogRedirect) other;
        return getId().equals(o.getId());
    }

    @Override
    public int hashCode() {
        return new HashCodeBuilder().append(getId()).toHashCode();
    }
}
