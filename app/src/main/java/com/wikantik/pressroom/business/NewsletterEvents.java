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
package com.wikantik.pressroom.business;

import com.wikantik.pressroom.pojos.PressroomEvent;
import com.wikantik.pressroom.pojos.Weblog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The one recorder of a new newsletter subscription, shared by the public
 * form ({@code NewsletterController}) and a signed-in reader's subscribe
 * ({@code ReaderStateService}), so both count alike.
 */
public final class NewsletterEvents {

    private static final Logger log = LoggerFactory.getLogger(NewsletterEvents.class);

    private NewsletterEvents() {
    }

    /**
     * Records a {@code NEWSLETTER_SUBSCRIBED} event. Best-effort per
     * {@code EventManager}'s own contract: an event insert must never fail
     * the request that produced it, so any failure here is logged and
     * swallowed.
     */
    public static void recordSubscribed(Weblogger weblogger, Weblog weblog) {
        try {
            PressroomEvent event = new PressroomEvent();
            event.setWeblog(weblog);
            event.setEventType(PressroomEvent.EventType.NEWSLETTER_SUBSCRIBED);
            weblogger.getEventManager().record(event);
            weblogger.flush();
        } catch (Exception ex) {
            log.error("Could not record newsletter-subscribed event", ex);
        }
    }
}
