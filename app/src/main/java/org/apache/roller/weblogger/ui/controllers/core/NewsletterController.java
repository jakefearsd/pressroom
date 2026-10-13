/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  The ASF licenses this file to You
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
package org.apache.roller.weblogger.ui.controllers.core;

import java.io.IOException;
import java.util.Collections;
import java.util.List;

import jakarta.servlet.http.HttpServletRequest;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.roller.util.RollerConstants;
import org.apache.roller.weblogger.WebloggerException;
import org.apache.roller.weblogger.business.FormSubmissionManager;
import org.apache.roller.weblogger.business.ListmonkClient;
import org.apache.roller.weblogger.config.WebloggerConfig;
import org.apache.roller.weblogger.pojos.RollerEvent;
import org.apache.roller.weblogger.pojos.Weblog;
import org.apache.roller.weblogger.ui.controllers.BaseController;
import org.apache.roller.weblogger.util.GenericThrottle;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/**
 * Serves the public newsletter subscribe endpoint at
 * {@code POST /newsletter/subscribe}: forwards a reader's email to Listmonk
 * for the weblog whose {@code newsletterListUuid} matches the posted list, and
 * records a first-party {@code NEWSLETTER_SUBSCRIBED} event on success. Posted
 * by the JS {@code #showAudienceAssets} injects onto every rendered page (and
 * by the standalone {@code #showSubscribeForm} macro).
 *
 * <p><b>Routing.</b> {@code /newsletter/*} is a dispatcher prefix mapping
 * ({@code ServletRegistrationConfig.NEWSLETTER_URL_PATTERNS}), so the lookup
 * path has {@code /newsletter} stripped and this controller carries no
 * class-level {@code @RequestMapping} -- the handler below is written
 * relative to the prefix, {@code /subscribe}.
 *
 * <p><b>The open-relay guard.</b> The posted {@code list_uuids[0]} is looked
 * up against {@code WeblogManager.getWeblogByNewsletterListUuid} BEFORE
 * anything is forwarded: an unrecognised uuid 404s rather than being handed
 * to Listmonk, so this endpoint cannot be used to relay subscriptions for
 * lists this install never configured.
 *
 * <p><b>Layered defences, in order</b> (mirroring {@code ContactController}):
 * a per-IP throttle refuses abusive clients outright (429); an unrecognised
 * list uuid 404s; a filled honeypot field or a submission faster than a human
 * could type answers exactly like success (200) but forwards nothing, so
 * automation learns nothing from being detected; a malformed email 400s; an
 * unconfigured {@code ListmonkClient} (blank {@code newsletter.listmonk.baseurl},
 * the dev default) 503s; an {@code IOException} forwarding to Listmonk 502s.
 * Listmonk's own 200/409 are passed straight through -- both are success from
 * the reader's point of view ({@code #showAudienceAssets} treats them
 * identically), and only 200 (a genuinely new subscription) records the
 * event: an already-subscribed address is not a new conversion.
 */
@Controller
public class NewsletterController extends BaseController {

    private static final Logger log = LoggerFactory.getLogger(NewsletterController.class);

    /** Not in the servlet API's constant list, which stops at 505. */
    private static final int TOO_MANY_REQUESTS = 429;
    private static final int BAD_GATEWAY = 502;
    private static final int SERVICE_UNAVAILABLE = 503;

    /**
     * The naive-bot timer, same idea as {@code ContactController}'s
     * {@code contact.form.min.seconds}: a submit faster than this is treated
     * as automation. Not a configuration property -- unlike the contact form
     * (whose {@code min.seconds} is deliberately tunable), no deploy of this
     * fork has asked to change it, so it stays a constant rather than
     * inventing an undocumented knob.
     */
    private static final long MIN_ELAPSED_MS = 3 * (long) RollerConstants.SEC_IN_MS;

    /**
     * The Listmonk client, built lazily on first use rather than in a
     * constructor -- {@code WebloggerConfig} is not necessarily loaded when
     * Spring instantiates controllers, exactly as {@code ContactController}'s
     * throttle is deferred for the same reason.
     */
    private volatile ListmonkClient listmonkClient;

    /**
     * Throttle for subscribe submissions, keyed by client address. Built
     * lazily on first use, exactly as {@code ContactController.throttle} is.
     */
    private volatile GenericThrottle throttle;

    // ------------------------------------------------------------- security
    //
    // The subscribe form is for anonymous readers; the interceptor must not
    // send them to the login page.

    @Override
    public boolean isUserRequired() {
        return false;
    }

    @Override
    public boolean isWeblogRequired() {
        return false;
    }

    @Override
    public List<String> requiredGlobalPermissionActions() {
        return Collections.emptyList();
    }

    // ------------------------------------------------------------ the form

    @PostMapping(value = "/subscribe", consumes = "application/json")
    public ResponseEntity<Void> subscribe(
            @RequestBody SubscribePayload payload, HttpServletRequest request) {

        if (throttlingEnabled() && throttle().isAbusive(request.getRemoteAddr())) {
            return ResponseEntity.status(TOO_MANY_REQUESTS).build();
        }

        String listUuid = firstListUuid(payload);
        Weblog weblog = lookupWeblogByListUuid(listUuid);
        if (weblog == null) {
            return ResponseEntity.notFound().build();
        }

        // Honeypot and timing: answer exactly like success so automation
        // cannot tell it was detected. Nothing is forwarded.
        if (StringUtils.isNotBlank(payload.website())
                || payload.elapsedMs() < MIN_ELAPSED_MS) {
            if (throttlingEnabled()) {
                throttle().processHit(request.getRemoteAddr());
            }
            return ResponseEntity.ok().build();
        }

        if (StringUtils.isBlank(payload.email()) || !payload.email().contains("@")
                || payload.email().length() > FormSubmissionManager.MAX_EMAIL) {
            return ResponseEntity.badRequest().build();
        }

        ListmonkClient client = listmonkClient();
        if (client.isUnconfigured()) {
            return ResponseEntity.status(SERVICE_UNAVAILABLE).build();
        }

        // Counted here, before the actual Listmonk call, so every real
        // attempt -- 200, 409, or a 502 from a Listmonk-side failure --
        // counts toward the budget alike; only requests refused before this
        // point (404/400/503/honeypot) are free.
        if (throttlingEnabled()) {
            throttle().processHit(request.getRemoteAddr());
        }

        int status;
        try {
            status = client.subscribe(payload.email(), listUuid);
        } catch (IOException ex) {
            log.error("Could not forward newsletter subscription to Listmonk for weblog {}",
                    weblog.getHandle(), ex);
            return ResponseEntity.status(BAD_GATEWAY).build();
        }

        if (status == 200) {
            recordEventBestEffort(weblog);
        }
        return ResponseEntity.status(status).build();
    }

    // ------------------------------------------------------------ helpers

    /** The first (and, today, only) list uuid in the payload, or null when absent. */
    private static String firstListUuid(SubscribePayload payload) {
        List<String> uuids = payload.list_uuids();
        return (uuids == null || uuids.isEmpty()) ? null : uuids.get(0);
    }

    /** The weblog configured with this newsletter list uuid, or null when unknown -- never throws. */
    private Weblog lookupWeblogByListUuid(String listUuid) {
        if (StringUtils.isBlank(listUuid)) {
            return null;
        }
        try {
            return weblogger.getWeblogManager().getWeblogByNewsletterListUuid(listUuid);
        } catch (WebloggerException ex) {
            // An outage reads as an unknown list (404) to the visitor: the endpoint is public, so it must not
            // reveal state or throw. The ERROR line is how an operator learns the lookups are failing.
            log.error("Error looking up weblog by newsletter list uuid", ex);
            return null;
        }
    }

    /**
     * Records a {@code NEWSLETTER_SUBSCRIBED} event. Best-effort per
     * {@code EventManager}'s own contract: an event insert must never fail
     * the request that produced it, so any failure here is logged and
     * swallowed.
     */
    private void recordEventBestEffort(Weblog weblog) {
        try {
            RollerEvent event = new RollerEvent();
            event.setWeblog(weblog);
            event.setEventType(RollerEvent.EventType.NEWSLETTER_SUBSCRIBED);
            weblogger.getEventManager().record(event);
            weblogger.flush();
        } catch (Exception ex) {
            log.error("Could not record newsletter-subscribed event", ex);
        }
    }

    // -------------------------------------------------------- listmonk client

    /**
     * Package-private so {@code NewsletterControllerTest} can inject a mock
     * without a real Listmonk instance -- the same seam shape
     * {@code ContactController.sendNotification} gives its mail collaborator.
     */
    void setListmonkClient(ListmonkClient client) {
        this.listmonkClient = client;
    }

    private ListmonkClient listmonkClient() {
        ListmonkClient client = listmonkClient;
        if (client == null) {
            synchronized (this) {
                client = listmonkClient;
                if (client == null) {
                    client = ListmonkClient.fromConfig();
                    listmonkClient = client;
                }
            }
        }
        return client;
    }

    // ------------------------------------------------------------ throttle

    /**
     * Whether the throttle applies. A startup property, like the contact
     * throttle: sizing and the on/off switch both need a restart to change.
     */
    private static boolean throttlingEnabled() {
        return WebloggerConfig.getBooleanProperty("newsletter.subscribe.throttle.enabled", true);
    }

    /**
     * The throttle itself, built on first use and cached -- its
     * threshold/interval/maxentries size a fixed tracking cache and are read
     * once, so the object is built once and never rebuilt.
     */
    private GenericThrottle throttle() {
        GenericThrottle t = throttle;
        if (t == null) {
            synchronized (this) {
                t = throttle;
                if (t == null) {
                    t = buildThrottle();
                    throttle = t;
                }
            }
        }
        return t;
    }

    private GenericThrottle buildThrottle() {
        int threshold = intProperty("newsletter.subscribe.throttle.threshold", 10);
        int interval = intProperty("newsletter.subscribe.throttle.interval", 60);
        int maxEntries = intProperty("newsletter.subscribe.throttle.maxentries", 250);
        log.info("Newsletter subscribe throttle sized at {} submissions per {}s", threshold, interval);
        return new GenericThrottle(threshold, interval * RollerConstants.SEC_IN_MS, maxEntries);
    }

    private static int intProperty(String name, int fallback) {
        try {
            return Integer.parseInt(WebloggerConfig.getProperty(name));
        } catch (NumberFormatException e) {
            log.warn("bad input for config property {}; using {}", name, fallback, e);
            return fallback;
        }
    }

    // -------------------------------------------------------------- payload

    /** The JSON body {@code #showAudienceAssets}'s subscribe form posts. */
    record SubscribePayload(String email, List<String> list_uuids, String website, long elapsedMs) {
    }
}
