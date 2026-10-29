package com.wikantik.pressroom.business.fetch;

/** A fetched, recognised image: the name to store it under, its kind, and its bytes. */
public record FetchedImage(String fileName, ImageKind kind, byte[] bytes) {
    public long size() {
        return bytes.length;
    }
}
