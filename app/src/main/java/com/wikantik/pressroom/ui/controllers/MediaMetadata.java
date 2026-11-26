package com.wikantik.pressroom.ui.controllers;

import java.util.List;

import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.business.MediaFileManager;
import com.wikantik.pressroom.pojos.MediaFile;
import com.wikantik.pressroom.util.Utilities;

/** What the media edit form and the API both do with a file's tags: one split, one write. */
public final class MediaMetadata {

    private MediaMetadata() {
    }

    /** The tags a string names: split on spaces and commas, as every tag field is. */
    public static List<String> tagNames(String tags) {
        return Utilities.splitStringAsTags(tags);
    }

    /**
     * Makes {@code tags} the file's whole tag set, through the manager (it removes the ones no longer
     * named); a null string removes them all, as an unsent form field always has.
     */
    public static void setTags(MediaFileManager manager, MediaFile file, String tags) throws WebloggerException {
        if (tags == null) {
            file.getTags().clear();
        } else {
            manager.updateTags(file, tagNames(tags));
        }
    }
}
