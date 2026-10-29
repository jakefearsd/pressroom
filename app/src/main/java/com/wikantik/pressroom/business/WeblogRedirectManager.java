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

import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.pojos.WeblogRedirect;

/**
 * Interface to redirect-rule management: 301s for weblog URLs that would
 * otherwise 404.
 *
 * <p>The fail-closed property does not live here -- it lives in <em>where
 * {@link #resolve} is called from</em>: only code points where a 404 has
 * already been decided (see the spec,
 * {@code docs/superpowers/specs/2026-08-24-url-redirects-design.md}). A rule
 * therefore cannot shadow live content, not because this manager validates
 * against it, but because the lookup is structurally unreachable while
 * anything real is being served.
 */
public interface WeblogRedirectManager {

    /**
     * The logger every served redirect and every rule mutation is written
     * to, deliberately named rather than per-class: its verbosity is tunable
     * independently of the rendering tier's, and every redirect ever served
     * is one {@code grep pressroom.redirects} away in the app log.
     */
    String LOG_NAME = "pressroom.redirects";

    /**
     * Save a rule, normalizing both paths and validating: weblog-relative
     * absolute paths only (leading {@code /}, no {@code //} prefix, no query
     * string, no backslash or control characters), target distinct from
     * source, no duplicate source, and no chaining -- the new rule may
     * neither begin where an existing rule of the same weblog ends nor end
     * where one begins, so resolution is always a single hop.
     *
     * @throws WebloggerException on any refusal, with a readable message.
     */
    void saveRedirect(WeblogRedirect redirect) throws WebloggerException;

    /**
     * Remove one rule.
     */
    void removeRedirect(WeblogRedirect redirect) throws WebloggerException;

    /**
     * Lookup by surrogate key. Ownership is the caller's problem, exactly as
     * with the other by-id lookups (see {@code WeblogOwnership}).
     */
    WeblogRedirect getRedirect(String id) throws WebloggerException;

    /**
     * All rules for one weblog, newest first.
     */
    List<WeblogRedirect> getRedirects(Weblog weblog) throws WebloggerException;

    /**
     * The rule answering one weblog-relative path, or {@code null}. The path
     * is normalized the same way {@link #saveRedirect} normalizes what it
     * stores, so both spellings of a trailing slash find the rule.
     */
    WeblogRedirect resolve(Weblog weblog, String path) throws WebloggerException;

    /**
     * Bump {@code hitCount}/{@code lastHitAt} for a served redirect.
     * Best-effort by contract: the redirect this bookkeeps was already owed
     * to the reader, so a failure here is logged and never propagated.
     */
    void recordHit(WeblogRedirect redirect);

    /**
     * Record that live content moved from {@code oldPath} to {@code newPath}
     * (a page-slug rename), maintaining the table's invariants in one place:
     * rules whose source is either path are removed (a dormant rule on the
     * old path is superseded by the fresher intent of the rename; a rule on
     * the new path could never fire and only accretes), rules TARGETING the
     * old path are re-pointed at the new one (or they strand on a 404 --
     * and collapsing is what keeps resolution one-hop under repeated
     * renames), and a {@code SLUG_HISTORY} rule old-to-new is minted.
     *
     * <p>Runs in the caller's transaction on purpose: a rename whose history
     * failed to write is a rename that silently killed a URL, so the two
     * succeed or fail together.
     */
    void recordRename(Weblog weblog, String oldPath, String newPath)
            throws WebloggerException;

    /**
     * Remove every rule of one weblog -- the weblog-deletion cascade.
     */
    void removeRedirects(Weblog weblog) throws WebloggerException;
}
