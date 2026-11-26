package com.wikantik.pressroom.ui.restapi.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import com.wikantik.pressroom.business.themes.SharedTheme;
import com.wikantik.pressroom.ui.controllers.SharedThemeRules;

/** Shared themes as the create-weblog and Theme screens offer them, and the switch to a custom theme. */
public final class ThemeDtos {

    private ThemeDtos() {
    }

    /** One shared theme, as the pickers show it. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ThemeChoice(
            @Schema(description = "The theme's id: what {@code theme} takes.", requiredMode = Schema.RequiredMode.REQUIRED)
            String id,
            @Schema(description = "The theme's name.", requiredMode = Schema.RequiredMode.REQUIRED)
            String name,
            @Schema(description = "What the theme looks like, in a sentence or two.")
            String description,
            @Schema(description = "Where the preview image is served, relative to the site root and starting "
                    + "with a slash (\"/themes/journal/preview.png\"); prefix the site's address. Absent when "
                    + "the theme has no preview.")
            String previewPath) {
    }

    public static ThemeChoice toChoice(SharedTheme theme) {
        return new ThemeChoice(theme.getId(), theme.getName(), theme.getDescription(),
                SharedThemeRules.previewPath(theme));
    }

    /** The Theme screen's facts for one weblog. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record WeblogThemes(
            @Schema(description = "The shared theme the weblog is on; absent when it is on a custom theme or its "
                    + "theme cannot be resolved.")
            String current,
            @Schema(description = "True when the weblog has its own templates (a custom theme): there is no way back.",
                    requiredMode = Schema.RequiredMode.REQUIRED)
            boolean custom,
            @Schema(description = "True when the weblog may take a custom theme: the site allows them (or the "
                    + "weblog is already custom) and it is not the site space.",
                    requiredMode = Schema.RequiredMode.REQUIRED)
            boolean customAllowed,
            @Schema(description = "True when a weblog on a shared theme holds its own copy of that theme's "
                    + "stylesheet.", requiredMode = Schema.RequiredMode.REQUIRED)
            boolean sharedStylesheetCustomised,
            @Schema(description = "True when the weblog has no default page template yet, so a custom theme "
                    + "would be its first customisation.", requiredMode = Schema.RequiredMode.REQUIRED)
            boolean firstCustomization,
            @Schema(description = "The shared themes this weblog may switch to, in the theme manager's order.",
                    requiredMode = Schema.RequiredMode.REQUIRED)
            List<ThemeChoice> themes) {
    }

    /** The body of the switch to a custom theme. */
    public record CustomThemeRequest(
            @Schema(description = "The id of the shared theme whose templates to import into the weblog; omit to "
                    + "switch with no import. Importing the weblog's current theme keeps a customised "
                    + "stylesheet.")
            String importTheme) {
    }
}
