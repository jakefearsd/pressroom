package com.wikantik.pressroom.ui.restapi.v1;

import jakarta.servlet.http.HttpServletRequest;
import org.apache.commons.lang3.StringUtils;
import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.pojos.MediaFile;
import com.wikantik.pressroom.pojos.MediaFileDirectory;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.ui.controllers.WeblogOwnership;
import com.wikantik.pressroom.ui.restapi.ApiException;
import com.wikantik.pressroom.ui.restapi.ProblemCode;

/**
 * The by-id lookups the media controllers share: {@link MediaReadApi} (the
 * reads a contributor may make) and {@link MediaApi} (the changes, a
 * publisher's). Two controllers because the declared permission is per
 * controller; one place for "this weblog's file" and "this weblog's
 * directory", so the two cannot drift.
 */
abstract class MediaApiBase extends BaseApiController {

    /**
     * The media file with this id, but only when it belongs to the action
     * weblog. Delegates to {@link WeblogOwnership#mediaFile}, the by-id
     * ownership check the entry write's image-id check shares, so both
     * agree on what "this weblog's file" means.
     */
    MediaFile requireMedia(HttpServletRequest request, String id) throws WebloggerException {
        Weblog weblog = requireActionWeblog(request);
        if (StringUtils.isBlank(id)) {
            throw ApiException.problem(ProblemCode.MEDIA_NOT_FOUND);
        }
        MediaFile file = WeblogOwnership.mediaFile(weblogger, id, weblog);
        if (file == null) {
            throw ApiException.problem(ProblemCode.MEDIA_NOT_FOUND);
        }
        return file;
    }

    /**
     * The directory with this id, but only when it belongs to the action
     * weblog; 404 for a blank, unknown, or foreign id -- {@code
     * getMediaFileDirectory} is a global by-id lookup, and both the {@code
     * dir} filter and a PATCH's {@code directoryId} are client input.
     */
    MediaFileDirectory requireDirectory(HttpServletRequest request, String id) throws WebloggerException {
        Weblog weblog = requireActionWeblog(request);
        if (StringUtils.isBlank(id)) {
            throw ApiException.problem(ProblemCode.DIRECTORY_NOT_FOUND);
        }
        MediaFileDirectory directory = weblogger.getMediaFileManager().getMediaFileDirectory(id);
        if (directory == null || directory.getWeblog() == null
                || !directory.getWeblog().getId().equals(weblog.getId())) {
            throw ApiException.problem(ProblemCode.DIRECTORY_NOT_FOUND);
        }
        return directory;
    }

    String url(Weblog weblog, MediaFile file) {
        return weblogger.getUrlStrategy().getMediaFileURL(weblog, file.getId(), true);
    }
}
