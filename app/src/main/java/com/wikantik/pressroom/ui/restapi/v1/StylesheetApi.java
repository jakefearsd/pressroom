package com.wikantik.pressroom.ui.restapi.v1;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.pojos.WeblogTemplate;
import com.wikantik.pressroom.ui.controllers.ThemeStylesheets;
import com.wikantik.pressroom.ui.restapi.ApiException;
import com.wikantik.pressroom.ui.restapi.ProblemCode;
import com.wikantik.pressroom.ui.restapi.auth.SessionOnly;
import com.wikantik.pressroom.ui.restapi.dto.TemplateDtos;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * A weblog's own stylesheet: the Stylesheet page over the API. Session-only (SA-18), for the reason of
 * {@link TemplatesApi}: stylesheet text is served as written, outside the sanitizer. The permission is
 * the page's, the weblog's {@code ADMIN}; the rules and writes are {@link ThemeStylesheets}, the page's own.
 *
 * <p>A weblog on a shared theme copies the theme's stylesheet to edit it ({@code copy-from-shared}), reverts
 * the copy to the theme's text, or deletes it to follow the theme again; a weblog on a custom theme owns
 * its stylesheet and only reads and saves it ({@code stylesheet.customTheme} for the other three).
 */
@Tag(name = "Stylesheet", description = "A weblog's own stylesheet (session only: the admin app, never a token).")
@RestController
@RequestMapping("/v1/weblogs/{handle}/stylesheet")
@SessionOnly
public class StylesheetApi extends WeblogAdminApi {

    @Operation(operationId = "stylesheetGet", summary = "The weblog's stylesheet and what can be done with it.")
    @GetMapping("")
    public TemplateDtos.StylesheetView get(HttpServletRequest request) throws WebloggerException {
        return view(requireActionWeblog(request));
    }

    @Operation(operationId = "stylesheetSave", summary = "Store the CSS of the weblog's own stylesheet.")
    @PutMapping("")
    public TemplateDtos.StylesheetView save(HttpServletRequest request,
            @RequestBody TemplateDtos.StylesheetWrite body) throws WebloggerException {
        Weblog weblog = requireActionWeblog(request);
        if (body.contents() == null) {
            throw ApiException.problemOnField("contents", ProblemCode.FIELD_REQUIRED, "contents");
        }
        ThemeStylesheets.save(weblogger, requireStylesheet(weblog), body.contents());
        return view(weblog);
    }

    @Operation(operationId = "stylesheetCopyFromShared", summary = "Copy the shared theme's stylesheet to edit it.")
    @PostMapping("/copy-from-shared")
    public TemplateDtos.StylesheetView copyFromShared(HttpServletRequest request) throws WebloggerException {
        Weblog weblog = requireActionWeblog(request);
        requireSharedTheme(weblog);
        if (!ThemeStylesheets.sharedThemeHasStylesheet(weblogger, weblog)) {
            throw ApiException.problem(ProblemCode.STYLESHEET_NOT_FOUND);
        }
        ThemeStylesheets.copyFromShared(weblogger, weblog);
        return view(weblog);
    }

    @Operation(operationId = "stylesheetRevert", summary = "Reset the copy to the shared theme's stylesheet text.")
    @PostMapping("/revert")
    public TemplateDtos.StylesheetView revert(HttpServletRequest request) throws WebloggerException {
        Weblog weblog = requireActionWeblog(request);
        requireSharedTheme(weblog);
        ThemeStylesheets.revert(weblogger, weblog, requireStylesheet(weblog));
        return view(weblog);
    }

    @Operation(operationId = "stylesheetDelete", summary = "Delete the copy, so the weblog follows its theme again.")
    @ApiResponse(responseCode = "204", description = "Done; no body.")
    @DeleteMapping("")
    public ResponseEntity<Void> delete(HttpServletRequest request) throws WebloggerException {
        Weblog weblog = requireActionWeblog(request);
        requireSharedTheme(weblog);
        ThemeStylesheets.remove(weblogger, weblog, requireStylesheet(weblog));
        return ResponseEntity.noContent().build();
    }

    private TemplateDtos.StylesheetView view(Weblog weblog) throws WebloggerException {
        WeblogTemplate own = ThemeStylesheets.find(weblogger, weblog);
        boolean shared = ThemeStylesheets.sharedThemeHasStylesheet(weblogger, weblog);
        return new TemplateDtos.StylesheetView(own != null, ThemeStylesheets.isCustomTheme(weblog), shared,
                own == null ? null : ThemeStylesheets.contents(own),
                own == null || own.getLastModified() == null ? null : own.getLastModified().toInstant());
    }

    private WeblogTemplate requireStylesheet(Weblog weblog) throws WebloggerException {
        WeblogTemplate own = ThemeStylesheets.find(weblogger, weblog);
        if (own == null) {
            throw ApiException.problem(ProblemCode.STYLESHEET_NOT_FOUND);
        }
        return own;
    }

    private static void requireSharedTheme(Weblog weblog) {
        if (ThemeStylesheets.isCustomTheme(weblog)) {
            throw ApiException.problem(ProblemCode.STYLESHEET_CUSTOM_THEME);
        }
    }
}
