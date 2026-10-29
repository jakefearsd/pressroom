package com.wikantik.pressroom.business.fetch;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Optional;

/** The raster images an import accepts, recognised by their first bytes, never by a header or a URL. */
public enum ImageKind {
    JPEG("jpg", "image/jpeg"), PNG("png", "image/png"), GIF("gif", "image/gif"), WEBP("webp", "image/webp");

    private static final byte[] JPEG_MAGIC = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF};
    private static final byte[] PNG_MAGIC = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
    private static final byte[] GIF87A = ascii("GIF87a");
    private static final byte[] GIF89A = ascii("GIF89a");
    private static final byte[] RIFF = ascii("RIFF");
    private static final byte[] WEBP_TAG = ascii("WEBP");

    private final String extension;
    private final String contentType;

    ImageKind(String extension, String contentType) {
        this.extension = extension;
        this.contentType = contentType;
    }

    public String extension() {
        return extension;
    }

    public String contentType() {
        return contentType;
    }

    /** The kind whose signature {@code head} starts with; a WebP is a RIFF container tagged WEBP at byte 8. */
    public static Optional<ImageKind> sniff(byte[] head) {
        if (startsWith(head, JPEG_MAGIC)) {
            return Optional.of(JPEG);
        }
        if (startsWith(head, PNG_MAGIC)) {
            return Optional.of(PNG);
        }
        if (startsWith(head, GIF87A) || startsWith(head, GIF89A)) {
            return Optional.of(GIF);
        }
        if (startsWith(head, RIFF) && head.length >= 12
                && Arrays.equals(Arrays.copyOfRange(head, 8, 12), WEBP_TAG)) {
            return Optional.of(WEBP);
        }
        return Optional.empty();
    }

    private static boolean startsWith(byte[] head, byte[] magic) {
        return head.length >= magic.length && Arrays.equals(Arrays.copyOf(head, magic.length), magic);
    }

    private static byte[] ascii(String s) {
        return s.getBytes(StandardCharsets.US_ASCII);
    }
}
