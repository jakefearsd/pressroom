package com.wikantik.pressroom.ui.restapi.v1;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.SchemaProperty;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.apache.commons.lang3.StringUtils;
import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.business.MediaFileManager;
import com.wikantik.pressroom.business.fetch.RemoteImageFetcher;
import com.wikantik.pressroom.config.WebloggerRuntimeConfig;
import com.wikantik.pressroom.pojos.MediaFile;
import com.wikantik.pressroom.pojos.MediaFileDirectory;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.pojos.WeblogPermission;
import com.wikantik.pressroom.ui.controllers.MediaUploads;
import com.wikantik.pressroom.ui.controllers.UISecurityEnforced;
import com.wikantik.pressroom.ui.restapi.ApiException;
import com.wikantik.pressroom.ui.restapi.MediaImports;
import com.wikantik.pressroom.ui.restapi.ColumnLimits;
import com.wikantik.pressroom.ui.controllers.MediaRules;
import com.wikantik.pressroom.ui.restapi.dto.MediaDtos;
import com.wikantik.pressroom.util.PressroomMessages;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;
import com.wikantik.pressroom.ui.restapi.ProblemCode;

/**
 * Media changes: upload, import from a URL, patch, delete, and directory
 * creation. {@code UISecurityEnforced} declares {@code WeblogPermission.POST}
 * -- media management is blog-wide structure, the level the Media Files pages
 * and {@code CategoriesApi} require -- and {@code PressroomHandlerInterceptor}
 * is what actually enforces it; this controller adds no permission checking
 * of its own. Reading the files and directories is a contributor's too, as
 * the editor's image chooser shows them, so those reads live in
 * {@link MediaReadApi}; the by-id lookups both use are {@link MediaApiBase}'s.
 */
@Tag(name = "Media writes", description = "Changing a weblog's media: upload, import from a URL, describe, move "
        + "and delete files, and create directories. Needs publish rights, as the Media Files pages do.")
@RestController
@RequestMapping("/v1/weblogs/{handle}/media")
public class MediaApi extends MediaApiBase implements UISecurityEnforced {

    @Autowired
    RemoteImageFetcher imageFetcher;

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
    // The parts are documented as the multipart body, not as query parameters
    // (springdoc's reading of @RequestParam), so a generated client sends a working
    // upload (OpenApiContractTest.theUploadIsAMultipartBodyOfBinaryFileParts).
    @Operation(operationId = "mediaUpload", requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(
            required = true, content = @Content(mediaType = MediaType.MULTIPART_FORM_DATA_VALUE,
                    schema = @Schema(type = "object", requiredProperties = {"file"}),
                    schemaProperties = {
                        @SchemaProperty(name = "file", array = @ArraySchema(
                                schema = @Schema(type = "string", format = "binary"),
                                arraySchema = @Schema(description = "One part per file; repeat the part to upload several."))),
                        @SchemaProperty(name = "directoryId", schema = @Schema(type = "string",
                                description = "The directory to store the files in; the weblog's default directory if omitted."))
                    })))
    @ApiResponse(responseCode = "201", description = "Every file was stored.")
    @ApiResponse(responseCode = "207", description = "Some files were refused; results says which and why.")
    @PostMapping(value = "", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<MediaDtos.UploadResponse> upload(
            HttpServletRequest request,
            @Parameter(hidden = true) @RequestParam(value = "file", required = false) MultipartFile[] files,
            @Parameter(hidden = true) @RequestParam(value = "directoryId", required = false) String directoryId)
            throws WebloggerException {
        Weblog weblog = requireActionWeblog(request);
        if (files == null || files.length == 0) {
            throw ApiException.problemOnField("file", ProblemCode.MEDIA_FILE_REQUIRED);
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
    @Operation(operationId = "mediaImport")
    @ApiResponse(responseCode = "201", description = "Imported as a new file.")
    @ApiResponse(responseCode = "200", description = "The directory already holds these bytes; that file.")
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
            // The import step's own text, passed through whole (ImportRefused: safe to show).
            throw ApiException.problemOnField("url", ProblemCode.MEDIA_IMPORT_REFUSED, e, e.getMessage());
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
            throw ApiException.problemOnField("url", ProblemCode.FIELD_REQUIRED, "url");
        }
        ColumnLimits.requireMaxLength("altText", body.altText(), ColumnLimits.MEDIA_ALT_TEXT);
        MediaFileDirectory directory = StringUtils.isNotBlank(body.directoryId())
                ? requireDirectory(request, body.directoryId())
                : MediaUploads.defaultDirectory(weblog, mfm);
        if (!WebloggerRuntimeConfig.getBooleanProperty("uploads.enabled")) {
            throw ApiException.problem(ProblemCode.UPLOAD_DISABLED);
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
    @Operation(operationId = "mediaUpdate")
    @PatchMapping("/{id}")
    public MediaDtos.MediaView update(
            HttpServletRequest request, @PathVariable("id") String id,
            @RequestBody MediaDtos.MediaPatch body) throws WebloggerException {
        Weblog weblog = requireActionWeblog(request);
        MediaFile file = requireMedia(request, id);
        Set<String> cleared = MediaDtos.MEDIA_CLEAR.validate(body.clear(), body);
        MediaFileDirectory target = body.directoryId() == null ? null : requireDirectory(request, body.directoryId());

        MediaDtos.applyPatch(file, body, cleared, target != null ? target : file.getDirectory());
        weblogger.getMediaFileManager().updateMediaFile(weblog, file);

        if (target != null && (file.getDirectory() == null || !target.getId().equals(file.getDirectory().getId()))) {
            weblogger.getMediaFileManager().moveMediaFile(file, target);
        }

        weblogger.flush();
        return MediaDtos.toView(file, url(weblog, file));
    }

    @Operation(operationId = "mediaDelete")
    @ApiResponse(responseCode = "204", description = "Done; no body.")
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(HttpServletRequest request, @PathVariable("id") String id)
            throws WebloggerException {
        Weblog weblog = requireActionWeblog(request);
        MediaFile file = requireMedia(request, id);
        weblogger.getMediaFileManager().removeMediaFile(weblog, file);
        weblogger.flush();
        return ResponseEntity.noContent().build();
    }

    /**
     * The name is judged by {@link MediaRules#directoryNameProblem}, which
     * normalises it exactly as {@code JPAMediaFileManagerImpl
     * .createMediaFileDirectory} will (trimmed, one leading slash dropped)
     * before judging it, so a name the manager would refuse is refused here
     * with a code instead of reaching it as a bare exception; the normalised
     * name is what is created.
     */
    @Operation(operationId = "mediaCreateDirectory")
    @ApiResponse(responseCode = "201", description = "Created.")
    @PostMapping("/directories")
    public ResponseEntity<MediaDtos.DirectoryView> createDirectory(
            HttpServletRequest request, @RequestBody MediaDtos.DirectoryWrite body) throws WebloggerException {
        Weblog weblog = requireActionWeblog(request);
        MediaRules.DirectoryName requested = MediaRules.directoryNameProblem(weblog, body.name());
        if (requested.violation() != null) {
            throw RuleProblems.problem(requested.violation(), true);
        }
        String normalized = requested.name();
        if (body.description() != null) {
            ColumnLimits.requireMaxLength("description", body.description(), ColumnLimits.DIRECTORY_DESCRIPTION);
        }

        MediaFileDirectory directory = weblogger.getMediaFileManager().createMediaFileDirectory(weblog, normalized);
        if (body.description() != null) {
            directory.setDescription(body.description());
        }
        weblogger.flush();

        return ResponseEntity.status(HttpStatus.CREATED).body(MediaDtos.toView(directory));
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
