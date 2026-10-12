package org.apache.roller.weblogger.business.fetch;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ImageKindTest {

    @Test
    void sniffsByMagicBytes() {
        assertEquals(Optional.of(ImageKind.JPEG),
                ImageKind.sniff(new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0x00}));
        assertEquals(Optional.of(ImageKind.PNG),
                ImageKind.sniff(new byte[] {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A}));
        assertEquals(Optional.of(ImageKind.GIF), ImageKind.sniff(ascii("GIF89a....")));
        assertEquals(Optional.of(ImageKind.GIF), ImageKind.sniff(ascii("GIF87a....")));
        assertEquals(Optional.of(ImageKind.WEBP), ImageKind.sniff(ascii("RIFF\0\0\0\0WEBPVP8 ")));
    }

    @Test
    void refusesEverythingElse() {
        assertEquals(Optional.empty(), ImageKind.sniff(new byte[0]));
        assertEquals(Optional.empty(), ImageKind.sniff(ascii("<svg xmlns=")));
        assertEquals(Optional.empty(), ImageKind.sniff(ascii("<!doctype html>")));
        assertEquals(Optional.empty(), ImageKind.sniff(ascii("RIFF\0\0\0\0WAVEfmt ")));
        assertEquals(Optional.empty(), ImageKind.sniff(ascii("RIFF\0\0\0\0WEB")));
        assertEquals(Optional.empty(), ImageKind.sniff(ascii("GIF88a....")));
    }

    @Test
    void namesItsExtensionAndType() {
        assertEquals("jpg", ImageKind.JPEG.extension());
        assertEquals("image/jpeg", ImageKind.JPEG.contentType());
        assertEquals("png", ImageKind.PNG.extension());
        assertEquals("gif", ImageKind.GIF.extension());
        assertEquals("webp", ImageKind.WEBP.extension());
        assertEquals("image/webp", ImageKind.WEBP.contentType());
    }

    private static byte[] ascii(String s) {
        return s.getBytes(StandardCharsets.US_ASCII);
    }
}
