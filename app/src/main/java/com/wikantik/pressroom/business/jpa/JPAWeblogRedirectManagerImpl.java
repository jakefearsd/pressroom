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
package com.wikantik.pressroom.business.jpa;

import java.sql.Timestamp;
import java.util.List;

import jakarta.persistence.Query;
import jakarta.persistence.TypedQuery;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.business.WeblogRedirectManager;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.pojos.WeblogRedirect;

/**
 * JPA implementation of {@link WeblogRedirectManager}.
 */
// Two loggers on purpose: the second is the shared redirect audit log, named for grepping.
@SuppressWarnings("PMD.MoreThanOneLogger")
public class JPAWeblogRedirectManagerImpl implements WeblogRedirectManager {

    private static final Logger log =
            LoggerFactory.getLogger(JPAWeblogRedirectManagerImpl.class);

    /** Every rule mutation, greppable by the same name the serve path logs to. */
    private static final Logger redirectLog = LoggerFactory.getLogger(LOG_NAME);

    private final JPAPersistenceStrategy strategy;

    protected JPAWeblogRedirectManagerImpl(JPAPersistenceStrategy strategy) {
        log.debug("Instantiating JPA Weblog Redirect Manager");
        this.strategy = strategy;
    }

    @Override
    public void saveRedirect(WeblogRedirect redirect) throws WebloggerException {
        if (redirect.getWeblog() == null) {
            throw new WebloggerException("redirect must belong to a weblog");
        }

        String source = validatedPath("source", redirect.getSourcePath());
        String target = validatedPath("target", redirect.getTargetPath());
        if (target.equals(source)) {
            throw new WebloggerException(
                    "redirect target equals its source: " + source);
        }
        redirect.setSourcePath(source);
        redirect.setTargetPath(target);

        Weblog weblog = redirect.getWeblog();
        for (WeblogRedirect existing : bySource(weblog, source)) {
            if (!existing.getId().equals(redirect.getId())) {
                throw new WebloggerException(
                        "a redirect for " + source + " already exists");
            }
        }
        // One hop, enforced from both ends: the new rule may neither begin
        // where an existing rule ends nor end where one begins. Chains are
        // where loops live, and a one-hop table is one a log reader can
        // follow without simulating anything.
        for (WeblogRedirect existing : byTarget(weblog, source)) {
            if (!existing.getId().equals(redirect.getId())) {
                throw new WebloggerException("redirect source " + source
                        + " is already the target of " + existing.getSourcePath());
            }
        }
        for (WeblogRedirect existing : bySource(weblog, target)) {
            if (!existing.getId().equals(redirect.getId())) {
                throw new WebloggerException("redirect target " + target
                        + " is itself redirected (to " + existing.getTargetPath() + ")");
            }
        }

        if (redirect.getCreatedAt() == null) {
            redirect.setCreatedAt(new Timestamp(System.currentTimeMillis()));
        }
        strategy.store(redirect);

        redirectLog.info("rule saved: weblog={} id={} origin={} {} -> {}",
                weblog.getHandle(), redirect.getId(), redirect.getOrigin(),
                source, target);
    }

    @Override
    public void removeRedirect(WeblogRedirect redirect) throws WebloggerException {
        strategy.remove(redirect);

        redirectLog.info("rule removed: weblog={} id={} origin={} {} -> {}",
                redirect.getWeblog().getHandle(), redirect.getId(),
                redirect.getOrigin(), redirect.getSourcePath(),
                redirect.getTargetPath());
    }

    @Override
    public WeblogRedirect getRedirect(String id) throws WebloggerException {
        return (WeblogRedirect) strategy.load(WeblogRedirect.class, id);
    }

    @Override
    public List<WeblogRedirect> getRedirects(Weblog weblog) throws WebloggerException {
        TypedQuery<WeblogRedirect> query = strategy.getNamedQuery(
                "WeblogRedirect.getByWeblog", WeblogRedirect.class);
        query.setParameter(1, weblog);
        return query.getResultList();
    }

    @Override
    public WeblogRedirect resolve(Weblog weblog, String path) throws WebloggerException {
        String normalized = WeblogRedirect.normalizePath(path);
        if (normalized == null || !normalized.startsWith("/")) {
            return null;
        }
        // The serve-path lookup keeps the strategy's default no-flush query
        // mode: it runs on every would-404 request and has no in-flight
        // writes of its own to see.
        TypedQuery<WeblogRedirect> query = strategy.getNamedQuery(
                "WeblogRedirect.getByWeblogAndSource", WeblogRedirect.class);
        query.setParameter(1, weblog);
        query.setParameter(2, normalized);
        List<WeblogRedirect> results = query.getResultList();
        return results.isEmpty() ? null : results.get(0);
    }

    @Override
    public void recordHit(WeblogRedirect redirect) {
        // Best-effort by contract: the 301 this bookkeeps was already owed to
        // the reader, so nothing here may propagate. The bump is a single
        // statement (not load-modify-store) so concurrent redirects cannot
        // lose updates to each other.
        try {
            Query update = strategy.getNamedUpdate("WeblogRedirect.recordHit");
            update.setParameter(1, redirect.getId());
            update.setParameter(2, new Timestamp(System.currentTimeMillis()));
            update.executeUpdate();
            strategy.flush();
        } catch (Exception ex) {
            log.warn("Could not record redirect hit for rule {}",
                    redirect.getId(), ex);
        }
    }

    @Override
    public void recordRename(Weblog weblog, String oldPath, String newPath)
            throws WebloggerException {
        String from = WeblogRedirect.normalizePath(oldPath);
        String to = WeblogRedirect.normalizePath(newPath);

        // Order matters. Sources on either path go first: a rule sitting on
        // the old path is superseded by the rename's fresher intent, and one
        // sitting on the new path is now live content's address (it could
        // never fire, but it would fail the mint's duplicate/chain checks
        // and confuse every later reader of the table). With those gone, the
        // collapse cannot create a self-loop and the mint passes validation.
        for (WeblogRedirect superseded : bySource(weblog, from)) {
            removeRedirect(superseded);
        }
        for (WeblogRedirect occupying : bySource(weblog, to)) {
            removeRedirect(occupying);
        }
        for (WeblogRedirect stranded : byTarget(weblog, from)) {
            stranded.setTargetPath(to);
            strategy.store(stranded);
            redirectLog.info("rule re-pointed by rename: weblog={} id={} {} -> {} (was -> {})",
                    weblog.getHandle(), stranded.getId(),
                    stranded.getSourcePath(), to, from);
        }

        WeblogRedirect minted = new WeblogRedirect();
        minted.setWeblog(weblog);
        minted.setSourcePath(from);
        minted.setTargetPath(to);
        minted.setOrigin(WeblogRedirect.Origin.SLUG_HISTORY);
        saveRedirect(minted);
    }

    @Override
    public void removeRedirects(Weblog weblog) throws WebloggerException {
        Query removeAll = strategy.getNamedUpdate("WeblogRedirect.removeByWeblog");
        removeAll.setParameter(1, weblog);
        removeAll.executeUpdate();
    }

    // The validation/ceremony lookups use the flush-first query mode
    // (FlushModeType.AUTO), NOT the strategy's default no-flush mode:
    // recordRename removes and re-points rules and then mints one in the
    // same unit of work, so the mint's duplicate/chain checks must see the
    // removes that preceded them or a legal rename is refused for a
    // collision that no longer exists.

    private List<WeblogRedirect> bySource(Weblog weblog, String source)
            throws WebloggerException {
        TypedQuery<WeblogRedirect> query = strategy.getNamedQueryCommitFirst(
                "WeblogRedirect.getByWeblogAndSource", WeblogRedirect.class);
        query.setParameter(1, weblog);
        query.setParameter(2, source);
        return query.getResultList();
    }

    private List<WeblogRedirect> byTarget(Weblog weblog, String target)
            throws WebloggerException {
        TypedQuery<WeblogRedirect> query = strategy.getNamedQueryCommitFirst(
                "WeblogRedirect.getByWeblogAndTarget", WeblogRedirect.class);
        query.setParameter(1, weblog);
        query.setParameter(2, target);
        return query.getResultList();
    }

    /**
     * Normalize one path and refuse everything that is not a plain
     * weblog-relative absolute path. The {@code //} refusal is the
     * protocol-relative form that sneaks past a starts-with-slash check --
     * a redirect table that can point off-site is a phishing primitive, so
     * targets are constrained to this weblog by shape, and sources get the
     * identical rule because a source that could never match (a query
     * string, a control character) is a rule that silently never fires.
     */
    private static String validatedPath(String label, String path)
            throws WebloggerException {
        String normalized = WeblogRedirect.normalizePath(path);
        if (normalized == null || normalized.isBlank()) {
            throw new WebloggerException("redirect " + label + " is required");
        }
        if (!normalized.startsWith("/") || normalized.startsWith("//")) {
            throw new WebloggerException("redirect " + label
                    + " must be a weblog-relative path starting with '/': " + normalized);
        }
        if (normalized.indexOf('?') >= 0) {
            throw new WebloggerException("redirect " + label
                    + " may not carry a query string: " + normalized);
        }
        if (normalized.indexOf('\\') >= 0) {
            throw new WebloggerException("redirect " + label
                    + " may not contain a backslash: " + normalized);
        }
        for (int i = 0; i < normalized.length(); i++) {
            char c = normalized.charAt(i);
            if (c < 0x20 || c == 0x7f) {
                throw new WebloggerException("redirect " + label
                        + " may not contain control characters");
            }
        }
        return normalized;
    }
}
