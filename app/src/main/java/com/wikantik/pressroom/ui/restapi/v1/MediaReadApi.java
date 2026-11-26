package com.wikantik.pressroom.ui.restapi.v1;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Comparator;
import java.util.List;
import org.apache.commons.lang3.StringUtils;
import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.pojos.MediaFile;
import com.wikantik.pressroom.pojos.MediaFileDirectory;
import com.wikantik.pressroom.pojos.MediaFileType;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.pojos.WeblogPermission;
import com.wikantik.pressroom.ui.controllers.MediaListing;
import com.wikantik.pressroom.ui.controllers.UISecurityEnforced;
import com.wikantik.pressroom.ui.restapi.ApiException;
import com.wikantik.pressroom.ui.restapi.ProblemCode;
import com.wikantik.pressroom.ui.restapi.Paging;
import com.wikantik.pressroom.ui.restapi.dto.MediaDtos;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Reading a weblog's media: its files, one file, its directories.
 * {@code UISecurityEnforced} declares {@code WeblogPermission.EDIT_DRAFT}:
 * the entry editor's image chooser ({@code MediaFileImageChooserController},
 * EDIT_DRAFT) lists a contributor the files and the directories, so a
 * contributor's token reads them too (owner decisions, editor parity).
 * Changing media stays a publisher's, in {@link MediaApi}, as on the Media
 * Files pages; the declared permission is per controller, which is why the
 * reads live here. {@code PressroomHandlerInterceptor} enforces it; this
 * controller adds no permission checking of its own.
 *
 * <p>Private directories are a pure visibility flag with no bypass of any
 * kind (see docs/dev/media.md): they 404 on the public media path, but a
 * member of the owning weblog sees them, as the image chooser lists every
 * directory. A caller holding a token for this weblog is such a member --
 * {@code PressroomHandlerInterceptor} would not have let the request through
 * otherwise -- so nothing here excludes a private directory or its files.
 */
@Tag(name = "Media", description = "Reading a weblog's media files and directories. A contributor may, as the "
        + "editor's image chooser shows them.")
@RestController
@RequestMapping("/v1/weblogs/{handle}/media")
public class MediaReadApi extends MediaApiBase implements UISecurityEnforced {

    private static final int MAX_LIMIT = Paging.MAX_LIMIT;

    /**
     * The weblog's media files. With {@code directoryId} (or its old name
     * {@code dir}), only that directory's files (ownership-checked, 404 for
     * an unknown or foreign directory id); without it, every file in the
     * weblog regardless of directory, via the same {@code searchMediaFiles}
     * path the JSP search screen uses with an otherwise-empty filter. Sorted
     * by name for a stable, deterministic order -- neither source guarantees
     * one on its own.
     *
     * <p>{@code offset}/{@code limit} slice that sorted list, so a large
     * library can be read a page at a time; the answer is the page
     * envelope, and without a limit it is every file (limit then the item
     * count, hasMore false). A limit is capped at {@link #MAX_LIMIT}.
     */
    @Operation(operationId = "mediaList")
    @GetMapping("")
    public MediaDtos.MediaPage list(
            HttpServletRequest request,
            // No @Parameter: the media tool shares one directoryId argument across actions, and
            // MediaImport's text (the import default) is the one it must carry (ToolSchemasTest).
            @RequestParam(value = "directoryId", required = false) String directoryId,
            @Parameter(description = "Deprecated: the old name of directoryId.")
            @RequestParam(value = "dir", required = false) String dir,
            @Parameter(description = "Only files whose name starts with this text, as the Media Files screen's "
                    + "search does; across the weblog, or within directoryId when both are given.")
            @RequestParam(value = "q", required = false) String q,
            @Parameter(description = "Only files of this type: image, video, audio or other (the screen's Others).")
            @RequestParam(value = "type", required = false) String type,
            @Parameter(description = "Order by name (the default, ignoring case), date_uploaded (newest first) "
                    + "or type (content type), then name.")
            @RequestParam(value = "sort", required = false) String sort,
            @Parameter(description = "Skip this many files of the sorted list. Default 0.")
            @RequestParam(value = "offset", required = false) Integer offset,
            @Parameter(description = "At most this many files (capped at 200). Without it, every file.")
            @RequestParam(value = "limit", required = false) Integer limit) throws WebloggerException {
        Weblog weblog = requireActionWeblog(request);
        int from = offset == null ? 0 : offset;
        Paging.requireRange(limit == null ? 1 : limit, from);
        String wanted = eitherName("directoryId", directoryId, "dir", dir);

        MediaListing.Sort order = sort == null || sort.isBlank() ? MediaListing.Sort.NAME : MediaListing.Sort.of(sort);
        if (order == null) {
            throw ApiException.problemOnField("sort", ProblemCode.PARAMETER_INVALID_VALUE, "sort");
        }
        MediaFileType wantedType = type == null || type.isBlank() ? null : MediaListing.typeOf(type);
        if (wantedType == null && type != null && !type.isBlank()) {
            throw ApiException.problemOnField("type", ProblemCode.PARAMETER_INVALID_VALUE, "type");
        }
        MediaFileDirectory directory = StringUtils.isNotBlank(wanted) ? requireDirectory(request, wanted) : null;
        List<MediaFile> files = MediaListing.sorted(
                MediaListing.find(weblogger.getMediaFileManager(), weblog, directory, q, wantedType), order);

        long size = limit == null ? Long.MAX_VALUE : Math.min(limit, MAX_LIMIT);
        List<MediaDtos.MediaView> items = files.stream().skip(from).limit(size)
                .map(f -> MediaDtos.toView(f, url(weblog, f))).toList();
        // Without a limit, every file: limit is then the item count and nothing follows.
        int pageSize = limit == null ? items.size() : (int) size;
        return new MediaDtos.MediaPage(items, from, pageSize, from + items.size() < files.size());
    }

    @Operation(operationId = "mediaGet")
    @GetMapping("/{id}")
    public MediaDtos.MediaView get(HttpServletRequest request, @PathVariable("id") String id)
            throws WebloggerException {
        Weblog weblog = requireActionWeblog(request);
        MediaFile file = requireMedia(request, id);
        return MediaDtos.toView(file, url(weblog, file));
    }

    @Operation(operationId = "mediaListDirectories")
    @GetMapping("/directories")
    public List<MediaDtos.DirectoryView> directories(HttpServletRequest request) throws WebloggerException {
        Weblog weblog = requireActionWeblog(request);
        List<MediaFileDirectory> directories = weblogger.getMediaFileManager().getMediaFileDirectories(weblog);
        return directories.stream()
                .sorted(Comparator.comparing(
                        MediaFileDirectory::getName, Comparator.nullsLast(String::compareToIgnoreCase)))
                .map(MediaDtos::toView)
                .toList();
    }

    @Override
    public boolean isUserRequired() {
        return true;
    }

    @Override
    public boolean isWeblogRequired() {
        return true;
    }

    @Override
    public List<String> requiredWeblogPermissionActions() {
        return List.of(WeblogPermission.EDIT_DRAFT);
    }

    @Override
    public List<String> requiredGlobalPermissionActions() {
        return List.of();
    }
}
