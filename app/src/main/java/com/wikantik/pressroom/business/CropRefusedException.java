package com.wikantik.pressroom.business;

import com.wikantik.pressroom.WebloggerException;

/**
 * A crop {@link MediaFileManager#cropMediaFile} refuses for a reason the
 * caller can act on: the file is not an image the server can re-encode, or the
 * rectangle does not overlap it. Every other failure of the crop (a storage
 * error, an unreadable image) is a plain {@link WebloggerException} and stays
 * a failure, the API's opaque 500. The message is the English it always was;
 * the API keys its answer by {@link #reason()}, never by the message.
 */
public class CropRefusedException extends WebloggerException {

    private static final long serialVersionUID = 1L;

    /** Why the crop was refused: one meaning each. */
    public enum Reason {
        /** The file is not a JPEG or PNG image, the only kinds with a re-encode path. */
        NOT_CROPPABLE,
        /** The rectangle does not overlap the image; {@link #imageWidth()} and {@link #imageHeight()} say how big it is. */
        OUTSIDE_IMAGE
    }

    private final Reason reason;
    private final int imageWidth;
    private final int imageHeight;

    public CropRefusedException(String message, Reason reason, int imageWidth, int imageHeight, Throwable cause) {
        super(message, cause);
        this.reason = reason;
        this.imageWidth = imageWidth;
        this.imageHeight = imageHeight;
    }

    public Reason reason() {
        return reason;
    }

    /** The displayed (orientation-corrected) width, for {@link Reason#OUTSIDE_IMAGE}; 0 otherwise. */
    public int imageWidth() {
        return imageWidth;
    }

    /** The displayed (orientation-corrected) height, for {@link Reason#OUTSIDE_IMAGE}; 0 otherwise. */
    public int imageHeight() {
        return imageHeight;
    }
}
