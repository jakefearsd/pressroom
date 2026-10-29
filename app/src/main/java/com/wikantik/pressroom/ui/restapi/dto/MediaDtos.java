package com.wikantik.pressroom.ui.restapi.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.Set;
import org.apache.commons.lang3.StringUtils;
import com.wikantik.pressroom.pojos.MediaFile;
import com.wikantik.pressroom.pojos.MediaFileDirectory;
import com.wikantik.pressroom.ui.controllers.MediaUploads;
import com.wikantik.pressroom.ui.restapi.ApiException;
import com.wikantik.pressroom.ui.restapi.ColumnLimits;
import com.wikantik.pressroom.util.PressroomMessages;

/**
 * Views of a media file and a media file directory, for the automation API.
 */
public final class MediaDtos {

    /** The fields {@code clear} may name on a media file. */
    public static final List<String> MEDIA_CLEARABLE = List.of("altText", "focalX", "focalY");

    /** Fields that are cleared together or not at all. */
    public static final List<List<String>> MEDIA_PAIRS = List.of(List.of("focalX", "focalY"));

    private MediaDtos() {
    }

    /**
     * {@code directory} is the owning directory's id, not its name -- unlike
     * {@code EntryDtos.EntryView.category}, which is a name because
     * {@code EntryDtos.EntryWrite.category} is also a name. Here the write
     * side ({@link MediaPatch#directoryId}) is explicitly an id, so the read
     * side matches it: a client can round-trip what it read straight back
     * into a patch without a second lookup.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record MediaView(
            String id, String name, String altText, String contentType, long length,
            int width, int height, String directory, Double focalX, Double focalY,
            String url, String blurhash) {
    }

    /** The body of POST /media/imports: an image to fetch from a public URL. */
    public record MediaImport(
            @Schema(description = "A public http or https URL of a JPEG, PNG, GIF or WebP image.") String url,
            @Schema(description = "A name. On import, the image's file name; the extension follows the image's "
                    + "real type.")
            String name,
            @Schema(description = "A directory id. On import, defaults to the weblog's default directory.")
            String directoryId,
            @Schema(description = "Alt text for the image.") String altText) {
    }

    /**
     * A partial update. Every component is nullable and null means ABSENT,
     * not "clear this"; {@code clear} removes a value. An explicitly empty
     * {@code altText} ("") still sets an empty alt text (the author marked it
     * on purpose, see {@link #applyPatch}), while {@code clear} removes it.
     * {@code directoryId} is not applied by {@link #applyPatch} -- moving a
     * file needs an ownership-checked directory lookup this pure mapper has
     * no business making, so the controller applies it after an explicit
     * lookup of its own.
     */
    public record MediaPatch(String altText, Double focalX, Double focalY, String directoryId, String name,
            @Schema(description = "Fields to remove: altText, focalX with focalY.")
            List<String> clear) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record DirectoryView(String id, String name, String description, boolean isPrivate, int fileCount) {
    }

    /**
     * The body of a directory create request. {@code description} is
     * optional; {@code name} is required, enforced by {@code MediaApi}, not
     * this record.
     */
    public record DirectoryWrite(String name, String description) {
    }

    /**
     * One uploaded file's outcome. {@code file} is populated only when
     * {@code status} is {@code "created"} -- a refused file was never
     * persisted, so there is nothing to return a view of.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record UploadResult(String fileName, String status, String detail, MediaView file) {
    }

    /**
     * The whole batch's outcome. {@code created}/{@code failed} are always
     * derived from {@code results} by {@link #summarise} rather than
     * threaded through independently, so they can never drift out of sync
     * with the list a caller would count by hand.
     */
    public record UploadResponse(List<UploadResult> results, int created, int failed) {
    }

    public static UploadResponse summarise(List<UploadResult> results) {
        int created = (int) results.stream().filter(r -> "created".equals(r.status())).count();
        return new UploadResponse(results, created, results.size() - created);
    }

    /**
     * Reads the newest (most recently added) error out of {@code messages}
     * -- the one {@code createMediaFile}/{@code canSave} just added for
     * THIS file -- and maps its key onto one of the three refusal statuses
     * the API promises. {@code FileContentManagerImpl.canSave} is the only
     * source of these keys today: {@code error.upload.dirmax} (the
     * weblog's total quota) and {@code error.upload.filemax} (the per-file
     * size cap) are both a size limit from the caller's point of view, so
     * both map to {@code "quota_exceeded"}; {@code error.upload.forbiddenFile}
     * maps to {@code "forbidden_extension"}; anything else (chiefly
     * {@code error.upload.disabled}, and defensively any key this method
     * has never seen) is {@code "error"}.
     *
     * <p>Delegates to {@link MediaUploads#refusal} -- the mapping is shared
     * with the editor's session upload endpoint (Task A6) and lives in
     * {@code ui.controllers} rather than here so both callers see exactly
     * one implementation. See {@code MediaUploads}'s class javadoc for why
     * the dependency points this direction and not the other.
     */
    public static UploadResult refusal(String fileName, PressroomMessages messages) {
        MediaUploads.Result r = MediaUploads.refusal(fileName, messages);
        return new UploadResult(r.fileName(), r.status(), r.detail(), null);
    }

    public static MediaView toView(MediaFile file, String url) {
        MediaFileDirectory directory = file.getDirectory();
        return new MediaView(
                file.getId(),
                file.getName(),
                file.getAltText(),
                file.getContentType(),
                file.getLength(),
                file.getWidth(),
                file.getHeight(),
                directory == null ? null : directory.getId(),
                file.getFocalX(),
                file.getFocalY(),
                url,
                file.getBlurhash());
    }

    public static DirectoryView toView(MediaFileDirectory directory) {
        return new DirectoryView(
                directory.getId(),
                directory.getName(),
                directory.getDescription(),
                directory.isPrivate(),
                directory.getMediaFiles().size());
    }

    /**
     * Whitespace-only counts as missing, matching the renderer's fallback to
     * the filename and the admin UI's own marker ({@code MediaFileView.jsp}
     * uses {@code fn:trim}) -- deliberately NOT EL's notion of {@code empty},
     * which would report a whitespace-only value as "described". Task 14's
     * media audit endpoint consumes this exact definition, so there is
     * exactly one definition of "missing" for both to share.
     */
    public static boolean isAltTextMissing(MediaFile file) {
        return StringUtils.isBlank(file.getAltText());
    }

    /**
     * Applies every field {@code patch} actually carries onto {@code file}.
     * {@code altText}, {@code focalX} and {@code focalY} are plain field
     * assignments; {@code directoryId} is deliberately left untouched here --
     * see {@link MediaPatch}'s javadoc. {@code name} is null-means-absent
     * like the others, but a PRESENT, blank value is rejected rather than
     * stored -- unlike {@code altText}, where an explicit empty string is a
     * real "the author cleared it" value, there is no equivalent "the file
     * is deliberately unnamed" concept a blank name could mean, only a
     * silently broken one.
     */
    public static void applyPatch(MediaFile file, MediaPatch patch, Set<String> cleared) {
        if (patch.altText() != null) {
            ColumnLimits.requireMaxLength("altText", patch.altText(), ColumnLimits.MEDIA_ALT_TEXT);
            file.setAltText(patch.altText());
        }
        if (patch.focalX() != null) {
            file.setFocalX(patch.focalX());
        }
        if (patch.focalY() != null) {
            file.setFocalY(patch.focalY());
        }
        if (patch.name() != null) {
            String name = patch.name().trim();
            if (name.isEmpty()) {
                throw ApiException.invalidField("name", "name cannot be blank.");
            }
            ColumnLimits.requireMaxLength("name", name, ColumnLimits.MEDIA_NAME);
            file.setName(name);
        }
        for (String name : cleared) {
            switch (name) {
                case "altText" -> file.setAltText(null);
                case "focalX" -> file.setFocalX(null);
                case "focalY" -> file.setFocalY(null);
                default -> throw new IllegalArgumentException("Not clearable: " + name);
            }
        }
    }
}
