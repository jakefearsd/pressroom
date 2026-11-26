package com.wikantik.pressroom.ui.controllers;

import java.util.List;

import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.business.Weblogger;
import com.wikantik.pressroom.pojos.MediaFileDirectory;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.ui.restapi.ProblemCode;
import com.wikantik.pressroom.util.cache.CacheManager;

/**
 * What the Media Files screen and the API both do to a media folder: the rule for deleting one, the delete,
 * and the private flag. One copy, so the two surfaces cannot come to disagree.
 */
public final class MediaDirectories {

    private MediaDirectories() {
    }

    /**
     * Why {@code directory} may not be deleted, or null when it may: the weblog's default folder is where
     * uploads land, and a folder still holding files is not deleted out from under them (the screen offers
     * Delete folder on an empty, non-default folder only; the removal itself is recursive).
     */
    public static RuleViolation deleteProblem(MediaFileDirectory directory) {
        if (MediaRules.DEFAULT_DIRECTORY.equals(directory.getName())) {
            return RuleViolation.conflict("id", "mediaFile.deleteFolder.error", List.of(),
                    ProblemCode.DIRECTORY_IS_DEFAULT);
        }
        if (!directory.getMediaFiles().isEmpty()) {
            return RuleViolation.conflict("id", "mediaFile.deleteFolder.error", List.of(),
                    ProblemCode.DIRECTORY_NOT_EMPTY);
        }
        return null;
    }

    /**
     * Removes the folder (and, by the manager, anything it held), saves the weblog so {@code lastModified}
     * moves (the only thing that expires rendered pages holding a gallery of it) and flushes. The caller has
     * already judged {@link #deleteProblem}.
     */
    public static void delete(Weblogger weblogger, Weblog weblog, MediaFileDirectory directory)
            throws WebloggerException {
        weblogger.getMediaFileManager().removeMediaFileDirectory(directory);
        weblogger.getWeblogManager().saveWeblog(weblog);
        weblogger.flush();
        CacheManager.invalidate(weblog);
    }

    /**
     * Sets the folder's private flag. Private folders vanish from every public surface (base media path,
     * inline galleries, sitemap images) and stay visible to the blog's members. Saving the weblog bumps its
     * {@code lastModified}, which expires the rendered pages and the cached sitemap that advertised the
     * folder's images; {@code CacheManager.invalidate} reaches only the site-wide cache.
     */
    public static void setPrivate(Weblogger weblogger, Weblog weblog, MediaFileDirectory directory,
            boolean isPrivate) throws WebloggerException {
        directory.setPrivate(isPrivate);
        weblogger.getWeblogManager().saveWeblog(weblog);
        weblogger.flush();
        CacheManager.invalidate(weblog);
    }
}
