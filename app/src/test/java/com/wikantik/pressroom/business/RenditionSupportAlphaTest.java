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

import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Images with an alpha channel through {@link RenditionSupport}: a
 * transparent PNG must keep its transparency when rotated or cropped, and
 * must still encode as JPEG (which has no alpha) instead of failing. The
 * opaque cases are in {@code RenditionSupportTest}.
 */
class RenditionSupportAlphaTest {

    private static BufferedImage translucent(int width, int height) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) {
                image.setRGB(x, y, 0x80FF0000);
            }
        }
        return image;
    }

    @Test
    void rotatingATransparentImageKeepsItsAlphaAndSwapsItsDimensions() {
        BufferedImage rotated = RenditionSupport.applyOrientation(translucent(4, 2), 6);

        assertEquals(2, rotated.getWidth());
        assertEquals(4, rotated.getHeight());
        assertTrue(rotated.getColorModel().hasAlpha(), "the rotated copy must still have an alpha channel");
        assertEquals(0x80, rotated.getRGB(0, 0) >>> 24, "the transparency itself must survive");
    }

    @Test
    void croppingATransparentImageKeepsItsAlpha() {
        BufferedImage cropped = RenditionSupport.crop(translucent(6, 6), new Rectangle(1, 1, 3, 2));

        assertEquals(3, cropped.getWidth());
        assertEquals(2, cropped.getHeight());
        assertTrue(cropped.getColorModel().hasAlpha());
        assertEquals(0x80, cropped.getRGB(0, 0) >>> 24);
    }

    @Test
    void aTransparentImageStillEncodesAsAnOpaqueJpeg() throws Exception {
        byte[] jpeg = RenditionSupport.encodeToBytes(translucent(8, 8), "jpg", 0.8f);

        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(jpeg));
        assertNotNull(decoded, "the bytes must be a readable image");
        assertEquals(8, decoded.getWidth());
        assertFalse(decoded.getColorModel().hasAlpha(), "JPEG has no alpha channel");
    }
}
