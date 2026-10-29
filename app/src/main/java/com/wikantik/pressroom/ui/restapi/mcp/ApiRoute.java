package com.wikantik.pressroom.ui.restapi.mcp;

import org.springframework.web.method.HandlerMethod;

/** One /api/v1 route: method, path template relative to /api, and the handler Spring maps it to. */
public record ApiRoute(String method, String pathTemplate, HandlerMethod handler) {

    public String key() {
        return method + " " + pathTemplate;
    }
}
