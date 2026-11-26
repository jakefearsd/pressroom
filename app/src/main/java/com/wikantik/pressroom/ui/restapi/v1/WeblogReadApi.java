package com.wikantik.pressroom.ui.restapi.v1;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import com.wikantik.pressroom.pojos.GlobalPermission;
import com.wikantik.pressroom.pojos.WeblogPermission;
import com.wikantik.pressroom.ui.controllers.UISecurityEnforced;
import com.wikantik.pressroom.ui.restapi.dto.AdminDtos;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Reading one weblog, by handle: its name, time zone, locale and address, which the admin frame and the entry
 * editor ask for as <em>any</em> member. It declares {@code WeblogPermission.EDIT_DRAFT}, the contributor's
 * level, as {@link EntriesApi} does. It used to share {@link WeblogSettingsApi}'s {@code ADMIN} declaration,
 * and an Author or Limited member's editor stayed blank on that 403 (found by {@code MembersIT}).
 * Declarations are per controller, hence a class of its own; changing a weblog stays {@code ADMIN}'s, in
 * {@link WeblogSettingsApi}. A caller with no role on the weblog is still refused 404.
 */
@Tag(name = "Weblog", description = "One weblog's name, time zone, locale and address, readable by any member.")
@RestController
@RequestMapping("/v1/weblogs")
public class WeblogReadApi extends BaseApiController implements UISecurityEnforced {

    @Operation(operationId = "weblogsGet")
    @GetMapping("/{handle}")
    public AdminDtos.WeblogView get(HttpServletRequest request) {
        return weblogView(requireActionWeblog(request));
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
        return List.of(GlobalPermission.LOGIN);
    }
}
