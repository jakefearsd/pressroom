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

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Decoding an uploaded image is bounded by its declared size, read from the
 * header before a single pixel is decoded: a solid-colour PNG declaring
 * 20000x20000 compresses to about 1.5 MB, inside the upload limit, but needs
 * about 1.6 GB of raster, an OutOfMemoryError no catch (Exception) stops
 * (code review, 2026-11-03). The stream is always closed, which the plain
 * ImageIO.read(InputStream) calls this replaces never did.
 */
class ImageDecodingTest {

    /** A call, not a mention: "ImageIO.read()" in prose has no argument. */
    private static final java.util.regex.Pattern CALL = java.util.regex.Pattern.compile("ImageIO\\.read\\(\\s*[^)\\s]");

    @Test
    void anImageWithinTheLimitDecodes() throws Exception {
        Tracked in = new Tracked(png(12, 10));

        BufferedImage image = ImageDecoding.read(in);

        assertNotNull(image);
        assertEquals(12, image.getWidth());
        assertEquals(10, image.getHeight());
        assertTrue(in.closed, "the stream is closed");
    }

    @Test
    void anImageDeclaringMorePixelsThanTheLimitIsRefusedBeforeDecoding() {
        // a header alone: decoding it would fail on the missing data, so a
        // TooLarge here can only come from reading the declared size first
        Tracked in = new Tracked(pngHeaderOnly(20_000, 20_000));

        ImageDecoding.TooLarge refused = assertThrows(ImageDecoding.TooLarge.class, () -> ImageDecoding.read(in));

        assertTrue(refused.getMessage().contains("20000x20000"), refused.getMessage());
        assertTrue(in.closed, "closed when refused, too");
    }

    @Test
    void theLimitIsInclusive() throws Exception {
        assertEquals(64_000_000L, ImageDecoding.MAX_PIXELS);
        assertNotNull(ImageDecoding.check(8_000, 8_000), "exactly 64 megapixels is allowed");
        assertThrows(ImageDecoding.TooLarge.class, () -> ImageDecoding.check(8_000, 8_001));
    }

    @Test
    void somethingThatIsNotAnImageReadsAsNull() throws Exception {
        Tracked in = new Tracked("not an image".getBytes(StandardCharsets.UTF_8));

        assertNull(ImageDecoding.read(in));
        assertTrue(in.closed);
    }

    /** Every decode goes through the bound: no plain ImageIO.read is left in main code. */
    @Test
    void nothingElseDecodesAnImageUnbounded() throws IOException {
        java.nio.file.Path main = java.nio.file.Path.of("src/main/java");
        java.util.List<String> unbounded;
        try (java.util.stream.Stream<java.nio.file.Path> files = java.nio.file.Files.walk(main)) {
            unbounded = files.filter(f -> f.toString().endsWith(".java"))
                    .filter(f -> !f.getFileName().toString().equals("ImageDecoding.java"))
                    .filter(f -> {
                        try {
                            return CALL.matcher(java.nio.file.Files.readString(f)).find();
                        } catch (IOException e) {
                            throw new java.io.UncheckedIOException(e);
                        }
                    })
                    .map(f -> main.relativize(f).toString())
                    .toList();
        }
        assertEquals(java.util.List.of(), unbounded, "decode through ImageDecoding.read");
    }

    private static byte[] png(int width, int height) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB), "png", out);
        return out.toByteArray();
    }

    /** A PNG signature and an IHDR chunk declaring the size, then IEND: no pixel data at all. */
    private static byte[] pngHeaderOnly(int width, int height) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(new byte[] {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n'});
        ByteBuffer ihdr = ByteBuffer.allocate(13).putInt(width).putInt(height)
                .put((byte) 8).put((byte) 2).put((byte) 0).put((byte) 0).put((byte) 0);
        chunk(out, "IHDR", ihdr.array());
        chunk(out, "IEND", new byte[0]);
        return out.toByteArray();
    }

    private static void chunk(ByteArrayOutputStream out, String type, byte[] data) {
        byte[] name = type.getBytes(StandardCharsets.US_ASCII);
        out.writeBytes(ByteBuffer.allocate(4).putInt(data.length).array());
        out.writeBytes(name);
        out.writeBytes(data);
        CRC32 crc = new CRC32();
        crc.update(name);
        crc.update(data);
        out.writeBytes(ByteBuffer.allocate(4).putInt((int) crc.getValue()).array());
    }

    /** Remembers whether it was closed. */
    private static final class Tracked extends ByteArrayInputStream {
        boolean closed;

        Tracked(byte[] bytes) {
            super(bytes);
        }

        @Override
        public void close() throws IOException {
            closed = true;
            super.close();
        }
    }
}
