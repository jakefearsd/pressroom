package com.wikantik.pressroom.ui.restapi.v1;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.apache.commons.lang3.StringUtils;
import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.pojos.ThemeTemplate.ComponentType;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.pojos.WeblogTemplate;
import com.wikantik.pressroom.ui.controllers.CustomTemplates;
import com.wikantik.pressroom.ui.controllers.WeblogOwnership;
import com.wikantik.pressroom.ui.controllers.editor.TemplateEditBean;
import com.wikantik.pressroom.ui.restapi.ApiException;
import com.wikantik.pressroom.ui.restapi.ColumnLimits;
import com.wikantik.pressroom.ui.restapi.ProblemCode;
import com.wikantik.pressroom.ui.restapi.ProblemMessages;
import com.wikantik.pressroom.ui.restapi.RuleProblems;
import com.wikantik.pressroom.ui.restapi.auth.SessionOnly;
import com.wikantik.pressroom.ui.restapi.dto.TemplateDtos;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * A weblog's own templates: the Templates and Template Edit pages over the API. Session-only (SA-18): a
 * template's output is not sanitized, so a template write is script on every page of the blog, and the
 * reads are part of the same editing surface. The permission is the pages': the weblog's {@code ADMIN}.
 * The rules and the writes are {@link CustomTemplates}, the pages' own.
 */
@Tag(name = "Templates", description = "A weblog's own templates (session only: the admin app, never a token).")
@RestController
@RequestMapping("/v1/weblogs/{handle}/templates")
@SessionOnly
public class TemplatesApi extends WeblogAdminApi {

    @Operation(operationId = "templatesList", summary = "The weblog's templates and the kinds it may add.")
    @GetMapping("")
    public TemplateDtos.TemplateList list(HttpServletRequest request) throws WebloggerException {
        Weblog weblog = requireActionWeblog(request);
        List<WeblogTemplate> pages = CustomTemplates.pages(weblogger, weblog);
        List<TemplateDtos.TemplateView> items = new java.util.ArrayList<>();
        for (WeblogTemplate page : pages) {
            items.add(TemplateDtos.toView(page, false));
        }
        return new TemplateDtos.TemplateList(items, CustomTemplates.availableActions(weblog, pages).keySet().stream()
                .map(Enum::name).toList());
    }

    @Operation(operationId = "templatesGet")
    @GetMapping("/{id}")
    public TemplateDtos.TemplateView get(HttpServletRequest request, @PathVariable("id") String id)
            throws WebloggerException {
        return TemplateDtos.toView(requireTemplate(request, id), true);
    }

    @Operation(operationId = "templatesCreate", summary = "Add a template holding the starter markup.")
    @ApiResponse(responseCode = "201", description = "Created.")
    @PostMapping("")
    public ResponseEntity<TemplateDtos.TemplateView> create(HttpServletRequest request,
            @RequestBody TemplateDtos.TemplateCreate body) throws WebloggerException {
        Weblog weblog = requireActionWeblog(request);
        ColumnLimits.requireMaxLength("name", body.name(), ColumnLimits.TEMPLATE_NAME);
        ComponentType action = parseAction(body.action());
        RuleProblems.refuseViolations(CustomTemplates.newProblems(weblogger, weblog, body.name(), action));
        List<WeblogTemplate> pages = CustomTemplates.pages(weblogger, weblog);
        List<String> allowed = CustomTemplates.availableActions(weblog, pages).keySet().stream()
                .map(Enum::name).toList();
        if (!allowed.contains(action.name())) {
            throw ApiException.problemOnField("action", ProblemCode.TEMPLATE_ACTION_UNAVAILABLE,
                    String.join(", ", allowed));
        }
        WeblogTemplate created = CustomTemplates.add(weblogger, weblog, body.name(), action,
                ProblemMessages.applicationResources().getMessage("pageForm.newTemplateContent", null,
                        request.getLocale()));
        return ResponseEntity.status(HttpStatus.CREATED).body(TemplateDtos.toView(created, true));
    }

    @Operation(operationId = "templatesUpdate", summary = "Change a template's markup and, for a custom one, "
            + "its name, link and flags.")
    @PatchMapping("/{id}")
    public TemplateDtos.TemplateView update(HttpServletRequest request, @PathVariable("id") String id,
            @RequestBody TemplateDtos.TemplateWrite body) throws WebloggerException {
        Weblog weblog = requireActionWeblog(request);
        WeblogTemplate template = requireTemplate(request, id);

        // The edit form's own bean carries the change, so the form's copyTo stores it.
        TemplateEditBean bean = new TemplateEditBean();
        bean.copyFrom(template);
        if (body.name() != null) {
            ColumnLimits.requireMaxLength("name", body.name(), ColumnLimits.TEMPLATE_NAME);
            bean.setName(body.name());
        }
        if (body.description() != null) {
            ColumnLimits.requireMaxLength("description", body.description(), ColumnLimits.TEMPLATE_DESCRIPTION);
            bean.setDescription(body.description());
        }
        if (body.link() != null) {
            ColumnLimits.requireMaxLength("link", body.link(), ColumnLimits.TEMPLATE_LINK);
            bean.setLink(body.link());
        }
        if (body.navbar() != null) {
            bean.setNavbar(body.navbar());
        }
        if (body.hidden() != null) {
            bean.setHidden(body.hidden());
        }
        if (body.contents() != null) {
            bean.setContentsStandard(body.contents());
        }
        if (body.contentType() != null) {
            ColumnLimits.requireMaxLength("contentType", body.contentType(), ColumnLimits.TEMPLATE_OUTPUT_TYPE);
            bean.setAutoContentType(StringUtils.isEmpty(body.contentType()));
            bean.setManualContentType(StringUtils.isEmpty(body.contentType()) ? null : body.contentType());
        } else {
            bean.setAutoContentType(StringUtils.isEmpty(template.getOutputContentType()));
        }
        if (template.isCustom()) {
            RuleProblems.refuseViolations(CustomTemplates.editProblems(weblogger, weblog, template, bean.getName(),
                    bean.getLink()));
        }
        CustomTemplates.save(weblogger, template, bean);
        return TemplateDtos.toView(template, true);
    }

    @Operation(operationId = "templatesDelete")
    @ApiResponse(responseCode = "204", description = "Done; no body.")
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(HttpServletRequest request, @PathVariable("id") String id)
            throws WebloggerException {
        Weblog weblog = requireActionWeblog(request);
        WeblogTemplate template = requireTemplate(request, id);
        if (!CustomTemplates.remove(weblogger, weblog, template)) {
            throw ApiException.problem(ProblemCode.TEMPLATE_REQUIRED);
        }
        return ResponseEntity.noContent().build();
    }

    private WeblogTemplate requireTemplate(HttpServletRequest request, String id) {
        WeblogTemplate template = WeblogOwnership.template(weblogger, id, requireActionWeblog(request));
        if (template == null) {
            throw ApiException.problem(ProblemCode.TEMPLATE_NOT_FOUND);
        }
        return template;
    }

    private static ComponentType parseAction(String action) {
        if (action == null || action.isBlank()) {
            return null;
        }
        try {
            return ComponentType.valueOf(action);
        } catch (IllegalArgumentException e) {
            throw ApiException.problemOnField("action", ProblemCode.TEMPLATE_ACTION_UNAVAILABLE, e,
                    String.join(", ", java.util.Arrays.stream(ComponentType.values()).map(Enum::name).toList()));
        }
    }
}
