package com.wikantik.pressroom.ui.controllers;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.apache.commons.lang3.StringUtils;
import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.business.MediaFileManager;
import com.wikantik.pressroom.pojos.MediaFile;
import com.wikantik.pressroom.pojos.MediaFileComparator;
import com.wikantik.pressroom.pojos.MediaFileComparator.MediaFileComparatorType;
import com.wikantik.pressroom.pojos.MediaFileDirectory;
import com.wikantik.pressroom.pojos.MediaFileFilter;
import com.wikantik.pressroom.pojos.MediaFileType;

/**
 * How the Media Files screen and the API pick and order the files they show: the sort keys, and the search
 * (a name prefix and a type, answered by {@code MediaFileManager.searchMediaFiles}, never a second
 * implementation of it).
 */
public final class MediaListing {

    /** The sort keys the screen's menu offers; {@code key} is the value it posts and the API accepts. */
    public enum Sort {
        NAME("name"), DATE_UPLOADED("date_uploaded"), TYPE("type");

        private final String key;

        Sort(String key) {
            this.key = key;
        }

        public String key() {
            return key;
        }

        /** The sort for {@code key}, or null when it is none of them. */
        public static Sort of(String key) {
            for (Sort sort : values()) {
                if (sort.key.equals(key)) {
                    return sort;
                }
            }
            return null;
        }
    }

    private MediaListing() {
    }

    /**
     * The type for the API's {@code type} value: {@code image}, {@code video}, {@code audio} or {@code other}
     * (the screen's "Others"), in lower case; null when it is none of them.
     */
    public static MediaFileType typeOf(String key) {
        return switch (key == null ? "" : key) {
            case "image" -> MediaFileType.IMAGE;
            case "video" -> MediaFileType.VIDEO;
            case "audio" -> MediaFileType.AUDIO;
            case "other" -> MediaFileType.OTHERS;
            default -> null;
        };
    }

    /** {@code files} in the order of {@code sort}: by name (ignoring case), by content type, or newest upload first. */
    public static List<MediaFile> sorted(List<MediaFile> files, Sort sort) {
        List<MediaFile> sorted = new ArrayList<>(files);
        Comparator<MediaFile> byName = Comparator.comparing(MediaFile::getName,
                Comparator.nullsLast(String::compareToIgnoreCase));
        switch (sort) {
            case TYPE -> sorted.sort(new MediaFileComparator(MediaFileComparatorType.TYPE).thenComparing(byName));
            case DATE_UPLOADED ->
                    sorted.sort(new MediaFileComparator(MediaFileComparatorType.DATE_UPLOADED).thenComparing(byName));
            case NAME -> sorted.sort(byName);
        }
        return sorted;
    }

    /**
     * The files to list: with no {@code text} and no {@code type}, those of {@code directory} or, with no
     * directory, every file of the weblog; with either, the manager's search of the weblog, narrowed to
     * {@code directory} when one is given.
     */
    public static List<MediaFile> find(MediaFileManager manager, com.wikantik.pressroom.pojos.Weblog weblog,
            MediaFileDirectory directory, String text, MediaFileType type) throws WebloggerException {
        boolean filtered = StringUtils.isNotBlank(text) || type != null;
        if (directory != null && !filtered) {
            return new ArrayList<>(directory.getMediaFiles());
        }
        MediaFileFilter filter = new MediaFileFilter();
        if (StringUtils.isNotBlank(text)) {
            filter.setName(text.trim());
        }
        filter.setType(type);
        // Copied: the manager's list is not promised to be sortable.
        List<MediaFile> found = new ArrayList<>(manager.searchMediaFiles(weblog, filter));
        if (directory != null) {
            found.removeIf(f -> f.getDirectory() == null || !directory.getId().equals(f.getDirectory().getId()));
        }
        return found;
    }
}
