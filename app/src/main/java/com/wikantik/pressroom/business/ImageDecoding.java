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

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.util.Iterator;

/**
 * Decodes a stored image, bounded by the size its header declares.
 *
 * <p>The upload limit bounds bytes, not pixels: a solid-colour PNG declaring
 * 20000x20000 compresses to about 1.5 MB but needs about 1.6 GB of raster,
 * and the OutOfMemoryError that follows is an Error no catch (Exception)
 * stops (code review, 2026-11-03). So the reader is asked for the first
 * image's width and height, which reads only the header, and an image over
 * {@link #MAX_PIXELS} is refused with {@link TooLarge} before any pixel is
 * decoded. Such a file is still stored and served; it only gets no thumbnail,
 * renditions or crop.
 *
 * <p>The stream is always closed, which the plain
 * {@code ImageIO.read(InputStream)} calls this replaces never did.
 */
public final class ImageDecoding {

    /**
     * The largest image decoded: 64 megapixels, about 190 MB as an RGB
     * raster, which covers the 48-50 MP photos of current phones.
     */
    public static final long MAX_PIXELS = 64_000_000L;

    private ImageDecoding() {
    }

    /**
     * The first image in {@code in}, decoded; null when it is not an image any
     * installed reader understands. The stream is closed either way.
     *
     * @throws TooLarge when the header declares more than {@link #MAX_PIXELS}
     */
    public static BufferedImage read(InputStream in) throws IOException {
        try (InputStream stream = in; ImageInputStream images = ImageIO.createImageInputStream(stream)) {
            if (images == null) {
                return null;
            }
            Iterator<ImageReader> readers = ImageIO.getImageReaders(images);
            if (!readers.hasNext()) {
                return null;
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(images, true, true);
                check(reader.getWidth(0), reader.getHeight(0));
                return reader.read(0);
            } finally {
                reader.dispose();
            }
        }
    }

    /**
     * Refuses a size over {@link #MAX_PIXELS}.
     *
     * @return the size as "WxH", for a message
     */
    static String check(long width, long height) throws TooLarge {
        String size = width + "x" + height;
        if (width * height > MAX_PIXELS) {
            throw new TooLarge("The image is " + size + " pixels, more than the " + MAX_PIXELS / 1_000_000
                    + " megapixels Pressroom decodes");
        }
        return size;
    }

    /** An image larger than {@link #MAX_PIXELS}; nothing was decoded. */
    public static final class TooLarge extends IOException {
        private static final long serialVersionUID = 1L;

        TooLarge(String message) {
            super(message);
        }
    }
}
