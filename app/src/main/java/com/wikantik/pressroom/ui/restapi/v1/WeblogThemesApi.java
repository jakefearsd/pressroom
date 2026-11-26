package com.wikantik.pressroom.ui.restapi.v1;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.business.Weblogger;
import com.wikantik.pressroom.business.themes.ThemeManager;
import com.wikantik.pressroom.pojos.ThemeTemplate.ComponentType;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.pojos.WeblogTheme;
import com.wikantik.pressroom.ui.controllers.RuleViolation;
import com.wikantik.pressroom.ui.controllers.SharedThemeRules;
import com.wikantik.pressroom.ui.restapi.RuleProblems;
import com.wikantik.pressroom.ui.restapi.auth.AdminScoped;
import com.wikantik.pressroom.ui.restapi.auth.SessionOnly;
import com.wikantik.pressroom.ui.restapi.dto.ThemeDtos;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The Theme page over the API: the shared themes the weblog may switch to (the switch itself is
 * {@code PATCH /v1/weblogs/{handle}} with {@code theme}), and the one-way switch to a custom theme.
 *
 * <p>The list is token-safe ({@code @AdminScoped}, as the weblog's settings). The switch to a custom theme
 * is {@code @SessionOnly} (SA-18): it imports templates, whose output is not sanitized, so a template write
 * is script on every page of the blog.
 */
@Tag(name = "Weblog themes", description = "The shared themes a weblog may switch to, and the switch to its own "
        + "templates (a custom theme).")
@RestController
@RequestMapping("/v1/weblogs/{handle}/themes")
@AdminScoped
public class WeblogThemesApi extends WeblogAdminApi {

    @Operation(operationId = "weblogThemesList", summary = "The shared themes this weblog may switch to.")
    @GetMapping("")
    public ThemeDtos.WeblogThemes list(HttpServletRequest request) throws WebloggerException {
        return view(weblogger, requireActionWeblog(request));
    }

    /** The Theme screen's facts for {@code weblog}: shared with the site theme route, which names no handle. */
    static ThemeDtos.WeblogThemes view(Weblogger weblogger, Weblog weblog) throws WebloggerException {
        ThemeManager themes = weblogger.getThemeManager();
        boolean custom = WeblogTheme.CUSTOM.equals(weblog.getEditorTheme());
        WeblogTheme current = SharedThemeRules.currentTheme(themes, weblog);
        String currentId = custom || current == null ? null : current.getId();
        boolean firstCustomization = weblogger.getWeblogManager()
                .getTemplateByAction(weblog, ComponentType.WEBLOG) == null;
        return new ThemeDtos.WeblogThemes(currentId, custom, SharedThemeRules.customThemeAllowed(weblog),
                SharedThemeRules.hasCustomisedSharedStylesheet(weblogger, weblog), firstCustomization,
                SharedThemeRules.offeredThemes(themes, weblog, currentId).stream()
                        .map(ThemeDtos::toChoice).toList());
    }

    /**
     * Puts the weblog on its own templates, importing a shared theme's templates when asked. There is no
     * way back. Refused as the Theme page refuses it: the site space and a site with custom themes off
     * ({@code theme.customisationDisabled}), and an import that is not one of the themes offered.
     */
    @Operation(operationId = "weblogThemesConvertToCustom",
            summary = "Switch the weblog to a custom theme, importing a shared theme's templates (session only).")
    @SessionOnly
    @PostMapping("/custom")
    public ThemeDtos.WeblogThemes convertToCustom(HttpServletRequest request,
            @RequestBody(required = false) ThemeDtos.CustomThemeRequest body) throws WebloggerException {
        Weblog weblog = requireActionWeblog(request);
        String importId = body == null ? null : body.importTheme();
        RuleViolation refusal = SharedThemeRules.customViolation(weblogger.getThemeManager(), weblog, importId, true);
        if (refusal != null) {
            throw RuleProblems.problem(refusal, "importTheme".equals(refusal.field()));
        }
        if (importId != null && !importId.isEmpty()) {
            SharedThemeRules.importTemplates(weblogger, weblog, importId);
        }
        SharedThemeRules.enableCustom(weblogger, weblog);
        return list(request);
    }
}
