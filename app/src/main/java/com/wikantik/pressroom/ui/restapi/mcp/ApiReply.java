package com.wikantik.pressroom.ui.restapi.mcp;

/** What the API answered a dispatched {@link ApiCall}: status, content type, body. */
public record ApiReply(int status, String contentType, String body) {

    public boolean isSuccess() {
        return status >= 200 && status < 300;
    }
}
