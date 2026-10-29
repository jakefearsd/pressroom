package com.wikantik.pressroom.ui.restapi.v1;

import io.swagger.v3.oas.annotations.Parameter;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import org.apache.commons.lang3.StringUtils;
import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.business.MediaFileManager;
import com.wikantik.pressroom.business.fetch.RemoteImageFetcher;
import com.wikantik.pressroom.config.WebloggerRuntimeConfig;
import com.wikantik.pressroom.pojos.MediaFile;
import com.wikantik.pressroom.pojos.MediaFileDirectory;
import com.wikantik.pressroom.pojos.MediaFileFilter;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.pojos.WeblogPermission;
import com.wikantik.pressroom.ui.controllers.MediaUploads;
import com.wikantik.pressroom.ui.controllers.WeblogOwnership;
import com.wikantik.pressroom.ui.controllers.UISecurityEnforced;
import com.wikantik.pressroom.ui.restapi.ApiException;
import com.wikantik.pressroom.ui.restapi.MediaImports;
import com.wikantik.pressroom.ui.restapi.Paging;
import com.wikantik.pressroom.ui.restapi.dto.ClearFields;
import com.wikantik.pressroom.ui.restapi.ColumnLimits;
import com.wikantik.pressroom.ui.restapi.dto.MediaDtos;
import com.wikantik.pressroom.util.PressroomMessages;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/**
 * Media read, patch, delete and directory listing/creation.
 * {@code UISecurityEnforced} declares {@code WeblogPermission.POST} -- media
 * management is blog-wide structure, the same level {@code CategoriesApi}
 * requires -- and {@code PressroomHandlerInterceptor} is what actually enforces
 * it; this controller adds no permission checking of its own.
 *
 * <p>Private directories are a pure visibility flag with no bypass of any
 * kind (see CLAUDE.md's Media Pipeline section): they 404 on the public
 * media path for everyone except a signed-in editor of the owning weblog. A
 * caller holding a weblog-scoped token for this weblog already IS such an
 * editor -- {@code PressroomHandlerInterceptor} would not have let the request
 * through otherwise -- so nothing here excludes a private directory or its
 * files from a caller who reached this controller at all.
 */
@RestController
@RequestMapping("/v1/weblogs/{handle}/media")
public class MediaApi extends BaseApiController implements UISecurityEnforced {

    // 200 is the documented cap for every list endpoint (docs/api/README.md, Pagination); the cap is
    // repeated in each controller, so change all of them together.
    private static final int MAX_LIMIT = 200;

    @Autowired
    RemoteImageFetcher imageFetcher;

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
     * library can be read a page at a time; the response stays the bare
     * array it always was, and without either parameter it is the whole
     * list, as before. A limit is capped at {@link #MAX_LIMIT}.
     */
    @GetMapping("")
    public List<MediaDtos.MediaView> list(
            HttpServletRequest request,
            // No @Parameter: the media tool shares one directoryId argument across actions, and
            // MediaImport's text (the import default) is the one it must carry (ToolSchemasTest).
            @RequestParam(value = "directoryId", required = false) String directoryId,
            @Parameter(description = "Deprecated: the old name of directoryId.")
            @RequestParam(value = "dir", required = false) String dir,
            @Parameter(description = "Skip this many files of the name-sorted list. Default 0.")
            @RequestParam(value = "offset", required = false) Integer offset,
            @Parameter(description = "At most this many files (capped at 200). Without it, every file.")
            @RequestParam(value = "limit", required = false) Integer limit) throws WebloggerException {
        Weblog weblog = requireActionWeblog(request);
        int from = offset == null ? 0 : offset;
        Paging.requireRange(limit == null ? 1 : limit, from);
        String wanted = eitherName("directoryId", directoryId, "dir", dir);

        List<MediaFile> files;
        if (StringUtils.isNotBlank(wanted)) {
            files = new ArrayList<>(requireDirectory(request, wanted).getMediaFiles());
        } else {
            // Copied into a mutable list -- searchMediaFiles's contract
            // makes no promise the returned list supports sort(), and this
            // controller needs a deterministic order regardless.
            files = new ArrayList<>(weblogger.getMediaFileManager().searchMediaFiles(weblog, new MediaFileFilter()));
        }
        files.sort(Comparator.comparing(MediaFile::getName, Comparator.nullsLast(String::compareToIgnoreCase)));

        long size = limit == null ? Long.MAX_VALUE : Math.min(limit, MAX_LIMIT);
        return files.stream().skip(from).limit(size).map(f -> MediaDtos.toView(f, url(weblog, f))).toList();
    }

    @GetMapping("/{id}")
    public MediaDtos.MediaView get(HttpServletRequest request, @PathVariable("id") String id)
            throws WebloggerException {
        Weblog weblog = requireActionWeblog(request);
        MediaFile file = requireMedia(request, id);
        return MediaDtos.toView(file, url(weblog, file));
    }

    /**
     * Multipart batch upload. A batch is not a transaction --
     * {@code createMediaFile} reports quota/forbidden-extension refusals
     * through {@code PressroomMessages} WITHOUT throwing (see CLAUDE.md's
     * Media Pipeline / bulk-upload note -- one bad file used to suppress
     * the entire success list before W4 fixed it the same way this does),
     * so every file's error count is snapshotted individually around its
     * own call; the absence of an exception proves nothing about whether
     * that one file landed. Responds 201 only when every file in the batch
     * landed, 207 the moment any one did not, so a caller can always tell
     * partial success from total failure from the status code alone,
     * before parsing the body.
     *
     * <p>{@code file} is deliberately {@code required = false}: an empty or
     * entirely absent batch is ordinary client input (an unselected file
     * input still submits an empty part), and letting Spring's
     * missing-required-parameter machinery handle it would surface as an
     * opaque 500 -- {@code ApiExceptionHandler} has no mapping for that
     * exception. This method rejects it itself, as a 400.
     */
    @PostMapping(value = "", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<MediaDtos.UploadResponse> upload(
            HttpServletRequest request,
            @RequestParam(value = "file", required = false) MultipartFile[] files,
            @RequestParam(value = "directoryId", required = false) String directoryId) throws WebloggerException {
        Weblog weblog = requireActionWeblog(request);
        if (files == null || files.length == 0) {
            throw ApiException.invalidField("file", "At least one file is required.");
        }

        MediaFileManager mfm = weblogger.getMediaFileManager();
        MediaFileDirectory directory = StringUtils.isNotBlank(directoryId)
                ? requireDirectory(request, directoryId)
                : MediaUploads.defaultDirectory(weblog, mfm);

        PressroomMessages messages = new PressroomMessages();
        List<MediaDtos.UploadResult> results = new ArrayList<>();
        for (MultipartFile upload : files) {
            results.add(processUpload(upload, weblog, directory, mfm, messages));
        }
        weblogger.flush();

        MediaDtos.UploadResponse response = MediaDtos.summarise(results);
        return ResponseEntity
                .status(response.failed() == 0 ? HttpStatus.CREATED : HttpStatus.MULTI_STATUS)
                .body(response);
    }

    /**
     * Import an image from a public URL through {@link MediaImports}: 201
     * with a Location for a new file, 200 with the file already in the
     * directory when the bytes match one. The checks before any network
     * request are this endpoint's own and log their refusal on the same
     * import logger.
     */
    @PostMapping("/imports")
    public ResponseEntity<MediaDtos.MediaView> importImage(HttpServletRequest request,
            @RequestBody MediaDtos.MediaImport body) throws WebloggerException {
        Weblog weblog = requireActionWeblog(request);
        long started = System.nanoTime();
        MediaFileDirectory directory;
        try {
            directory = importDirectory(request, weblog, body, weblogger.getMediaFileManager());
        } catch (ApiException e) {
            MediaImports.log(weblog, body.url(), "refused", 0, started);
            throw e;
        }
        MediaImports.Imported imported;
        try {
            imported = MediaImports.importImage(weblogger, imageFetcher, weblog, directory,
                    body.url(), body.name(), body.altText());
        } catch (MediaImports.ImportRefused e) {
            throw ApiException.invalidField("url", e.getMessage(), e);
        }
        MediaFile file = imported.file();
        MediaDtos.MediaView view = MediaDtos.toView(file, url(weblog, file));
        if (!imported.created()) {
            return ResponseEntity.ok(view);
        }
        URI location = ServletUriComponentsBuilder.fromContextPath(request)
                .path("/api/v1/weblogs/{handle}/media/{id}")
                .buildAndExpand(weblog.getHandle(), file.getId()).toUri();
        return ResponseEntity.created(location).body(view);
    }

    /**
     * The checks an import must pass before any network request, in the
     * order the caller hears about them; the directory to store into when
     * all pass. Each refusal is an ApiException.
     */
    private MediaFileDirectory importDirectory(HttpServletRequest request, Weblog weblog,
            MediaDtos.MediaImport body, MediaFileManager mfm) throws WebloggerException {
        if (StringUtils.isBlank(body.url())) {
            throw ApiException.invalidField("url", "url is required.");
        }
        ColumnLimits.requireMaxLength("altText", body.altText(), ColumnLimits.MEDIA_ALT_TEXT);
        MediaFileDirectory directory = StringUtils.isNotBlank(body.directoryId())
                ? requireDirectory(request, body.directoryId())
                : MediaUploads.defaultDirectory(weblog, mfm);
        if (!WebloggerRuntimeConfig.getBooleanProperty("uploads.enabled")) {
            throw ApiException.badRequest("File upload is disabled for this site.");
        }
        return directory;
    }

    /**
     * One file's outcome, isolated from the rest of the batch. Delegates to
     * {@link MediaUploads#store} -- the shared per-file upload logic, moved
     * there (Task A6) so the editor's session upload endpoint
     * ({@code MediaFileAddController#upload}) cannot drift from this one on
     * what a refusal is. See {@link #upload}'s javadoc for the
     * batch-is-not-a-transaction rationale {@code store} implements.
     */
    private MediaDtos.UploadResult processUpload(MultipartFile upload, Weblog weblog,
            MediaFileDirectory directory, MediaFileManager mfm, PressroomMessages messages) throws WebloggerException {
        MediaUploads.Result r = MediaUploads.store(upload, weblog, directory, mfm, messages);
        return r.file() == null
                ? new MediaDtos.UploadResult(r.fileName(), r.status(), r.detail(), null)
                : new MediaDtos.UploadResult(
                        r.fileName(), "created", null, MediaDtos.toView(r.file(), url(weblog, r.file())));
    }

    /**
     * {@code directoryId} is resolved (ownership-checked) BEFORE anything
     * is applied or persisted -- a foreign directory id must refuse the
     * whole request untouched, not patch the file's metadata and only then
     * discover the move target does not belong to this weblog. Once
     * resolved, the move itself happens after the metadata update,
     * matching the order {@code MediaFileEditController.save} uses.
     */
    @PatchMapping("/{id}")
    public MediaDtos.MediaView update(
            HttpServletRequest request, @PathVariable("id") String id,
            @RequestBody MediaDtos.MediaPatch body) throws WebloggerException {
        Weblog weblog = requireActionWeblog(request);
        MediaFile file = requireMedia(request, id);
        Set<String> cleared = ClearFields.validate(body.clear(), body,
                MediaDtos.MEDIA_CLEARABLE, MediaDtos.MEDIA_PAIRS);
        MediaFileDirectory target = body.directoryId() == null ? null : requireDirectory(request, body.directoryId());

        MediaDtos.applyPatch(file, body, cleared);
        weblogger.getMediaFileManager().updateMediaFile(weblog, file);

        if (target != null && (file.getDirectory() == null || !target.getId().equals(file.getDirectory().getId()))) {
            weblogger.getMediaFileManager().moveMediaFile(file, target);
        }

        weblogger.flush();
        return MediaDtos.toView(file, url(weblog, file));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(HttpServletRequest request, @PathVariable("id") String id)
            throws WebloggerException {
        Weblog weblog = requireActionWeblog(request);
        MediaFile file = requireMedia(request, id);
        weblogger.getMediaFileManager().removeMediaFile(weblog, file);
        weblogger.flush();
        return ResponseEntity.noContent().build();
    }

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

    /**
     * The blank/reserved/duplicate pre-checks run against the SAME
     * normalised name {@code JPAMediaFileManagerImpl.createMediaFileDirectory}
     * will itself compute -- it strips one leading slash before validating,
     * so a raw pre-check against the client's literal string sees different
     * input than the manager does. {@code "/"} passes a raw blank check
     * ("/".isBlank() is false) and a raw duplicate check, then normalises to
     * "" inside the manager and throws a bare {@code WebloggerException}
     * ("Invalid name!") that {@code ApiExceptionHandler} can only turn into
     * an opaque 500; {@code "/existing"} passes the same way and only
     * collides with an existing "existing" directory AFTER normalisation.
     * Note this is NOT the same shape as {@code CategoriesApi.create}'s
     * duplicate guard, despite looking similar -- that guard's manager-side
     * counterpart (`WeblogCategory`'s constructor) does no normalisation of
     * its own, so a raw pre-check there really does see what the manager
     * sees. Here it does not, unless this method replicates the manager's
     * one normalisation step first. Replicated rather than caught-and-mapped
     * because the transformation (strip a single leading slash) and the one
     * reserved name ("default") are small and stable enough that duplicating
     * them here is lower-risk than parsing the manager's free-text exception
     * messages to tell "Invalid name!" apart from "Directory exists".
     */
    @PostMapping("/directories")
    public ResponseEntity<MediaDtos.DirectoryView> createDirectory(
            HttpServletRequest request, @RequestBody MediaDtos.DirectoryWrite body) throws WebloggerException {
        Weblog weblog = requireActionWeblog(request);
        String name = body.name() == null ? null : body.name().trim();
        if (name == null || name.isBlank()) {
            throw ApiException.invalidField("name", "name is required.");
        }

        String normalized = name.startsWith("/") ? name.substring(1) : name;
        if (normalized.isEmpty()) {
            throw ApiException.invalidField("name", "name is required.");
        }
        if ("default".equals(normalized)) {
            throw ApiException.invalidField("name", "'default' is a reserved directory name.");
        }
        ColumnLimits.requireMaxLength("name", normalized, ColumnLimits.DIRECTORY_NAME);
        if (weblog.hasMediaFileDirectory(normalized)) {
            throw ApiException.conflictOnField("name", "A directory named '" + normalized + "' already exists.");
        }
        if (body.description() != null) {
            ColumnLimits.requireMaxLength("description", body.description(), ColumnLimits.DIRECTORY_DESCRIPTION);
        }

        MediaFileDirectory directory = weblogger.getMediaFileManager().createMediaFileDirectory(weblog, name);
        if (body.description() != null) {
            directory.setDescription(body.description());
        }
        weblogger.flush();

        return ResponseEntity.status(HttpStatus.CREATED).body(MediaDtos.toView(directory));
    }

    /**
     * The media file with this id, but only when it belongs to the action
     * weblog. Delegates to {@link WeblogOwnership#mediaFile}, the by-id
     * ownership check the entry write's image-id check shares, so both
     * agree on what "this weblog's file" means.
     */
    private MediaFile requireMedia(HttpServletRequest request, String id) throws WebloggerException {
        Weblog weblog = requireActionWeblog(request);
        if (StringUtils.isBlank(id)) {
            throw ApiException.notFound("No such media file.");
        }
        MediaFile file = WeblogOwnership.mediaFile(weblogger, id, weblog);
        if (file == null) {
            throw ApiException.notFound("No such media file.");
        }
        return file;
    }

    /**
     * The directory with this id, but only when it belongs to the action
     * weblog; 404 for a blank, unknown, or foreign id -- {@code
     * getMediaFileDirectory} is a global by-id lookup, and both the {@code
     * dir} filter and a PATCH's {@code directoryId} are client input.
     */
    private MediaFileDirectory requireDirectory(HttpServletRequest request, String id) throws WebloggerException {
        Weblog weblog = requireActionWeblog(request);
        if (StringUtils.isBlank(id)) {
            throw ApiException.notFound("No such directory.");
        }
        MediaFileDirectory directory = weblogger.getMediaFileManager().getMediaFileDirectory(id);
        if (directory == null || directory.getWeblog() == null
                || !directory.getWeblog().getId().equals(weblog.getId())) {
            throw ApiException.notFound("No such directory.");
        }
        return directory;
    }

    private String url(Weblog weblog, MediaFile file) {
        return weblogger.getUrlStrategy().getMediaFileURL(weblog, file.getId(), true);
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
        return List.of(WeblogPermission.POST);
    }

    @Override
    public List<String> requiredGlobalPermissionActions() {
        return List.of();
    }
}
