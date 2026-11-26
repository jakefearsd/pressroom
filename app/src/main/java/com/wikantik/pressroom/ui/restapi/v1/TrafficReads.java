package com.wikantik.pressroom.ui.restapi.v1;

import com.wikantik.pressroom.business.Weblogger;
import com.wikantik.pressroom.business.analytics.AnalyticsManager;
import com.wikantik.pressroom.business.analytics.BlogTraffic;
import com.wikantik.pressroom.business.analytics.TrafficState;
import com.wikantik.pressroom.config.WebloggerConfig;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.ui.restapi.dto.TrafficDtos;

/**
 * One weblog's traffic read, shared by the weblog's own route and the site admin's per-blog route (the
 * only way to the site space's numbers): the snapshot, the weblog's time zone, where "Open in Umami"
 * goes, and why a weblog has no numbers when its registration failed.
 */
final class TrafficReads {

    private static final String DASHBOARD_URL_KEY = "analytics.umami.dashboardUrl";

    private TrafficReads() {
    }

    static TrafficDtos.TrafficView read(Weblogger weblogger, Weblog weblog, String period) {
        AnalyticsManager analytics = weblogger.getAnalyticsManager();
        BlogTraffic traffic = analytics.getBlogTraffic(weblog.getId(), period);
        // from memory: a blog has a website ID exactly when it is registered
        String websiteId = analytics.websiteIdFor(weblog.getId());
        String registrationError = traffic.state() == TrafficState.NO_DATA && websiteId == null
                ? traffic.lastError() : null;
        return TrafficDtos.toView(traffic, period, weblog.getTimeZoneInstance().toZoneId().getId(),
                TrafficDtos.umamiUrl(WebloggerConfig.getProperty(DASHBOARD_URL_KEY), websiteId),
                registrationError);
    }
}
