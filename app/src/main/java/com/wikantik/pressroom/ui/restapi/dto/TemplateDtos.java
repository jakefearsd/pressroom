package com.wikantik.pressroom.ui.restapi.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.pojos.TemplateRendition.RenditionType;
import com.wikantik.pressroom.pojos.WeblogTemplate;

/** A weblog's own templates and stylesheet, for the session-only routes (SA-18). */
public final class TemplateDtos {

    private TemplateDtos() {
    }

    /** One template; {@code contents} only when it is read singly. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record TemplateView(
            @Schema(description = "The template's id.", requiredMode = Schema.RequiredMode.REQUIRED) String id,
            @Schema(description = "The template's name.", requiredMode = Schema.RequiredMode.REQUIRED) String name,
            @Schema(description = "WEBLOG, PERMALINK, SEARCH, TAGSINDEX, STYLESHEET or CUSTOM: what the template "
                    + "is for. Fixed once it exists.", requiredMode = Schema.RequiredMode.REQUIRED)
            String action,
            @Schema(description = "What the template is for, in a sentence.")
            String description,
            @Schema(description = "The path a custom template is served under.")
            String link,
            @Schema(description = "True when a custom template is linked in the weblog's navigation bar.",
                    requiredMode = Schema.RequiredMode.REQUIRED) boolean navbar,
            @Schema(description = "True when a custom template is hidden from the weblog's public pages.",
                    requiredMode = Schema.RequiredMode.REQUIRED) boolean hidden,
            @Schema(description = "The output content type; absent when the engine chooses.")
            String contentType,
            @Schema(description = "True for a template a custom theme requires, which cannot be removed.",
                    requiredMode = Schema.RequiredMode.REQUIRED)
            boolean required,
            Instant lastModified,
            @Schema(description = "The template's markup; present when one template is read, not in the list.")
            String contents) {
    }

    public static TemplateView toView(WeblogTemplate t, boolean withContents) throws WebloggerException {
        String contents = null;
        if (withContents) {
            var standard = t.getTemplateRendition(RenditionType.STANDARD);
            contents = standard == null ? "" : standard.getTemplate();
        }
        return new TemplateView(t.getId(), t.getName(), t.getAction().name(), t.getDescription(), t.getLink(),
                t.isNavbar(), t.isHidden(), t.getOutputContentType(), t.isRequired(),
                t.getLastModified() == null ? null : t.getLastModified().toInstant(), contents);
    }

    /** The Templates screen: the weblog's templates and the kinds it may add. */
    public record TemplateList(
            @Schema(description = "The weblog's templates, without their markup; the stylesheet has its own route.",
                    requiredMode = Schema.RequiredMode.REQUIRED) List<TemplateView> items,
            @Schema(description = "The values of {@code action} a new template may take now.",
                    requiredMode = Schema.RequiredMode.REQUIRED)
            List<String> availableActions) {
    }

    /** A new template. */
    public record TemplateCreate(
            @Schema(description = "The template's name, at most 255 characters, unique in the weblog. Required.")
            String name,
            @Schema(description = "What the template is for: one of the list's availableActions. Required.")
            String action) {
    }

    /** The Template Edit form. A null field is left unchanged. */
    public record TemplateWrite(
            @Schema(description = "A custom template's name; ignored for the other kinds, as the form does.")
            String name,
            @Schema(description = "A custom template's description.")
            String description,
            @Schema(description = "A custom template's link.")
            String link,
            @Schema(description = "Whether a custom template is linked in the navigation bar.")
            Boolean navbar,
            @Schema(description = "Whether a custom template is hidden from the public pages.")
            Boolean hidden,
            @Schema(description = "The template's markup.")
            String contents,
            @Schema(description = "The output content type; empty string lets the engine choose.")
            String contentType) {
    }

    /** The Stylesheet screen's facts. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record StylesheetView(
            @Schema(description = "True when the weblog holds a stylesheet of its own.",
                    requiredMode = Schema.RequiredMode.REQUIRED)
            boolean exists,
            @Schema(description = "True when the weblog is on a custom theme, which owns its stylesheet.",
                    requiredMode = Schema.RequiredMode.REQUIRED)
            boolean customTheme,
            @Schema(description = "True when the weblog's shared theme defines a stylesheet that can be copied.",
                    requiredMode = Schema.RequiredMode.REQUIRED)
            boolean sharedThemeStylesheet,
            @Schema(description = "The stylesheet's CSS; absent when the weblog has none of its own.")
            String contents,
            Instant lastModified) {
    }

    /** The CSS to store. */
    public record StylesheetWrite(
            @Schema(description = "The stylesheet's CSS. Required.")
            String contents) {
    }
}
