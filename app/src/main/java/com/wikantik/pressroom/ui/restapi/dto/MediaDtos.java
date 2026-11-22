package com.wikantik.pressroom.ui.restapi.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.apache.commons.lang3.StringUtils;
import com.wikantik.pressroom.pojos.MediaFile;
import com.wikantik.pressroom.pojos.MediaFileDirectory;
import com.wikantik.pressroom.ui.controllers.MediaRules;
import com.wikantik.pressroom.ui.controllers.MediaUploads;
import com.wikantik.pressroom.ui.controllers.RuleViolation;
import com.wikantik.pressroom.ui.restapi.ApiException;
import com.wikantik.pressroom.ui.restapi.ColumnLimits;
import com.wikantik.pressroom.ui.restapi.FieldErrors;
import com.wikantik.pressroom.ui.restapi.Paging;
import com.wikantik.pressroom.ui.restapi.ProblemCode;
import com.wikantik.pressroom.ui.restapi.RuleProblems;
import com.wikantik.pressroom.util.PressroomMessages;

/**
 * Views of a media file and a media file directory, for the automation API.
 */
public final class MediaDtos {

    /** What {@code clear} may name on a media file, and how each is cleared. */
    public static final ClearPolicy<MediaFile> MEDIA_CLEAR = ClearPolicy.<MediaFile>builder()
            .field("altText", f -> f.setAltText(null))
            .field("focalX", f -> f.setFocalX(null))
            .field("focalY", f -> f.setFocalY(null))
            .pair("focalX", "focalY")
            .build();

    private MediaDtos() {
    }

    /** One page of media files. */
    public record MediaPage(
            @Schema(description = Paging.ITEMS_DESCRIPTION, requiredMode = Schema.RequiredMode.REQUIRED)
            List<MediaView> items,
            @Schema(description = Paging.OFFSET_DESCRIPTION, requiredMode = Schema.RequiredMode.REQUIRED) int offset,
            @Schema(description = Paging.LIMIT_DESCRIPTION, requiredMode = Schema.RequiredMode.REQUIRED) int limit,
            @Schema(description = Paging.HAS_MORE_DESCRIPTION, requiredMode = Schema.RequiredMode.REQUIRED)
            boolean hasMore) {
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
     * The body of POST /media/{id}/crop. The rectangle is in pixels of the
     * displayed (orientation-corrected) image, as the editor draws it; one that
     * overshoots the image is clamped. Components are boxed so a missing one is
     * a named 400, not a silent 0.
     */
    public record MediaCrop(
            @Schema(description = "Left edge of the crop, in pixels of the displayed image. Required.") Integer x,
            @Schema(description = "Top edge of the crop, in pixels of the displayed image. Required.") Integer y,
            @Schema(description = "Width of the crop in pixels; at least 1. Required.") Integer width,
            @Schema(description = "Height of the crop in pixels; at least 1. Required.") Integer height,
            @Schema(description = "The file's width as media get reported it. When sent, the crop runs only if it "
                    + "still matches, so a retry cannot crop twice.") Integer expectedWidth,
            @Schema(description = "The file's height as media get reported it; checked as expectedWidth is.")
            Integer expectedHeight) {
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
    public record MediaPatch(
            @Schema(description = "Alt text for the image; an empty string marks it decorative. Remove with clear.")
            String altText,
            @Schema(description = "The focal point's left-to-right position, a fraction of the width from 0 to 1. "
                    + "A file has both coordinates or neither.")
            Double focalX,
            @Schema(description = "The focal point's top-to-bottom position, a fraction of the height from 0 to 1.")
            Double focalY,
            @Schema(description = "A directory id: moves the file there.")
            String directoryId,
            @Schema(description = "The file's name; not blank.")
            String name,
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
    public record DirectoryWrite(
            @Schema(description = "The directory's name, unique in the weblog. Required.")
            String name,
            @Schema(description = "What the directory holds.")
            String description) {
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
     * The admin UI's focal-point rule ({@code MediaFileBean.copyTo}): each
     * coordinate is a fraction of the image, 0 to 1, and a file has both or
     * neither. The UI clamps a dragged marker; a caller sending a number out
     * of range has made a mistake, so it is a 400, as an out-of-range latitude
     * is. One coordinate moves an existing point; on a file with none it
     * would leave half a point, and is a 400 too. Checked before anything is
     * applied, so a refused patch changes nothing.
     */
    private static void checkFocalPoint(FieldErrors errors, MediaFile file, MediaPatch patch) {
        errors.check("focalX", () -> requireFraction("focalX", patch.focalX()));
        errors.check("focalY", () -> requireFraction("focalY", patch.focalY()));
        if (patch.focalX() == null && patch.focalY() == null) {
            return;
        }
        // a value both sent and cleared is ClearFields' 400, already refused
        boolean hasX = patch.focalX() != null || file.getFocalX() != null;
        boolean hasY = patch.focalY() != null || file.getFocalY() != null;
        if (hasX != hasY) {
            String field = patch.focalX() != null ? "focalX" : "focalY";
            errors.addIfAbsent(field, ProblemCode.FOCAL_PAIR_ONLY);
        }
    }

    private static void requireFraction(String field, Double value) {
        if (value != null && !(value >= 0.0 && value <= 1.0)) {
            throw ApiException.problemOnField(field, ProblemCode.FOCAL_OUT_OF_RANGE, field);
        }
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
        applyPatch(file, patch, cleared, file.getDirectory());
    }

    /**
     * As {@link #applyPatch(MediaFile, MediaPatch, Set)}, with the name the
     * file will have -- a new {@code name}, or its own when {@code directory}
     * is not the folder it is in now (a move) -- also judged against the files
     * of {@code directory}, the directory the file will be in: one 400 for
     * every bad field first, then the 409 for a name another file there holds,
     * and nothing is applied.
     */
    public static void applyPatch(MediaFile file, MediaPatch patch, Set<String> cleared,
            MediaFileDirectory directory) {
        // Every field is judged before any is applied (the file is managed),
        // and one 400 names all the bad ones.
        FieldErrors errors = new FieldErrors();
        checkFocalPoint(errors, file, patch);
        if (patch.altText() != null) {
            errors.check("altText", () -> ColumnLimits.requireMaxLength("altText", patch.altText(),
                    ColumnLimits.MEDIA_ALT_TEXT));
        }
        String name = patch.name() == null ? null : patch.name().trim();
        if (name != null) {
            RuleProblems.add(errors, MediaRules.fileNameShapeProblem(name));
        }
        errors.throwIfAny();
        // A move keeps the file's name unless a new one is sent, and the target folder must not
        // already hold whichever name the file will have there.
        boolean moving = directory != null
                && (file.getDirectory() == null || !Objects.equals(directory.getId(), file.getDirectory().getId()));
        String judged = name != null ? name : moving ? file.getName() : null;
        RuleViolation taken = MediaRules.fileNameTaken(directory, judged, file.getId());
        if (taken != null) {
            throw RuleProblems.problem(taken, true);
        }
        if (patch.altText() != null) {
            file.setAltText(patch.altText());
        }
        if (patch.focalX() != null) {
            file.setFocalX(patch.focalX());
        }
        if (patch.focalY() != null) {
            file.setFocalY(patch.focalY());
        }
        if (name != null) {
            file.setName(name);
        }
        MEDIA_CLEAR.apply(file, cleared);
    }
}
