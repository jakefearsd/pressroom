package com.wikantik.pressroom.ui.restapi.v1;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.pojos.ApiToken;
import com.wikantik.pressroom.pojos.GlobalPermission;
import com.wikantik.pressroom.pojos.User;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.ui.restapi.auth.ApiPrincipal;
import org.springframework.security.core.context.SecurityContextHolder;
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

    @Operation(operationId = "weblogsGet", description = "One weblog, readable by any member and by read-role "
            + "tokens. For a caller below weblog admin (or with a token below the ADMIN role) the settings-only "
            + "fields are absent: emailAddress, creatorUserName, businessId, placeType, placeLocality, "
            + "placeRegion, placeCountry, placeLat, placeLng and bookingUrl.")
    @GetMapping("/{handle}")
    public AdminDtos.WeblogView get(HttpServletRequest request) {
        Weblog weblog = requireActionWeblog(request);
        AdminDtos.WeblogView view = weblogView(weblog);
        return mayReadAdminFields(weblog, request.getAttribute("authenticatedUser")) ? view : view.withoutAdminOnlyFields();
    }

    /**
     * Whether the caller holds weblog {@code ADMIN} (a site admin implies it) and, if they came with a
     * token, an {@code ADMIN}-role one: the contact email, creator, business, place and booking fields are
     * the settings page's, so a READ or POST token never carries them out, whoever holds it. A check that
     * cannot be answered is a denial.
     */
    private boolean mayReadAdminFields(Weblog weblog, Object caller) {
        if (!(caller instanceof User user)) {
            return false;
        }
        if (SecurityContextHolder.getContext().getAuthentication() != null
                && SecurityContextHolder.getContext().getAuthentication().getDetails() instanceof ApiPrincipal token
                && token.scopeRole() != ApiToken.Role.ADMIN) {
            return false;
        }
        try {
            return weblogger.getUserManager().checkPermission(
                    new WeblogPermission(weblog, user, WeblogPermission.ADMIN), user);
        } catch (WebloggerException e) {
            return false;
        }
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
