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
package org.apache.roller.weblogger.ui.restapi.v1;

import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.List;
import org.apache.roller.weblogger.WebloggerException;
import org.apache.roller.weblogger.pojos.Weblog;
import org.apache.roller.weblogger.pojos.WeblogPermission;
import org.apache.roller.weblogger.pojos.WeblogRedirect;
import org.apache.roller.weblogger.ui.controllers.UISecurityEnforced;
import org.apache.roller.weblogger.ui.controllers.WeblogOwnership;
import org.apache.roller.weblogger.ui.restapi.ApiException;
import org.apache.roller.weblogger.ui.restapi.ColumnLimits;
import org.apache.roller.weblogger.ui.restapi.dto.RedirectDtos;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/**
 * Redirect-rule management -- the ONLY admin surface for redirects, by the
 * spec's decision: a site migration is a script emitting exact rules through
 * this controller, not a form filled fifty times, and the automatic
 * slug-history path needs no UI at all.
 *
 * <p>There is no update verb on purpose: a rule is two strings, so
 * delete-and-recreate IS the update, and it keeps the no-chaining validation
 * to one code path ({@code WeblogRedirectManager.saveRedirect}). The manager
 * owns normalization and every refusal (off-site targets, chaining,
 * duplicates); this controller maps those refusals to readable 400s and adds
 * only the column-length check the manager answers with a bare exception.
 */
@RestController
@RequestMapping("/v1/weblogs/{handle}/redirects")
public class RedirectsApi extends BaseApiController implements UISecurityEnforced {

    @GetMapping("")
    public List<RedirectDtos.RedirectView> list(HttpServletRequest request)
            throws WebloggerException {
        Weblog weblog = requireActionWeblog(request);
        return weblogger.getWeblogRedirectManager().getRedirects(weblog).stream()
                .map(RedirectDtos::toView)
                .toList();
    }

    @PostMapping("")
    public ResponseEntity<RedirectDtos.RedirectView> create(
            HttpServletRequest request, @RequestBody RedirectDtos.RedirectWrite body)
            throws WebloggerException {
        Weblog weblog = requireActionWeblog(request);

        if (body.source() == null || body.source().isBlank()) {
            throw ApiException.invalidField("source", "source is required.");
        }
        if (body.target() == null || body.target().isBlank()) {
            throw ApiException.invalidField("target", "target is required.");
        }
        ColumnLimits.requireMaxLength("source", body.source(), ColumnLimits.REDIRECT_PATH);
        ColumnLimits.requireMaxLength("target", body.target(), ColumnLimits.REDIRECT_PATH);

        WeblogRedirect rule = new WeblogRedirect();
        rule.setWeblog(weblog);
        rule.setSourcePath(body.source());
        rule.setTargetPath(body.target());
        rule.setOrigin(WeblogRedirect.Origin.MANUAL);

        // Every saveRedirect refusal -- off-site target, chaining, duplicate
        // source -- is validation with a readable message; surfaced as a 400
        // rather than falling through the generic handler as an opaque 500.
        try {
            weblogger.getWeblogRedirectManager().saveRedirect(rule);
        } catch (WebloggerException refusal) {
            throw ApiException.badRequest(refusal.getMessage(), refusal);
        }
        weblogger.flush();

        URI location = ServletUriComponentsBuilder.fromRequestUri(request)
                .path("/{id}")
                .buildAndExpand(rule.getId())
                .toUri();
        return ResponseEntity.created(location).body(RedirectDtos.toView(rule));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> remove(HttpServletRequest request,
            @PathVariable("id") String id) throws WebloggerException {
        WeblogRedirect rule = requireRedirect(request, id);
        weblogger.getWeblogRedirectManager().removeRedirect(rule);
        weblogger.flush();
        return ResponseEntity.noContent().build();
    }

    /**
     * The rule with this id, but only when it belongs to the action weblog
     * -- 404 either way, never 403, so a foreign rule's id and a missing one
     * are indistinguishable.
     */
    private WeblogRedirect requireRedirect(HttpServletRequest request, String id) {
        WeblogRedirect rule = WeblogOwnership.redirect(
                weblogger, id, requireActionWeblog(request));
        if (rule == null) {
            throw ApiException.notFound("No such redirect.");
        }
        return rule;
    }

    @Override
    public boolean isUserRequired() {
        return true;
    }

    @Override
    public boolean isWeblogRequired() {
        return true;
    }

    @Override
    public List<String> requiredWeblogPermissionActions() {
        return List.of(WeblogPermission.POST);
    }

    @Override
    public List<String> requiredGlobalPermissionActions() {
        return List.of();
    }
}
