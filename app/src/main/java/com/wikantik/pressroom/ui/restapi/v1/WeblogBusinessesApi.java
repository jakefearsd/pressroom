package com.wikantik.pressroom.ui.restapi.v1;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.pojos.Business;
import com.wikantik.pressroom.pojos.WeblogPermission;
import com.wikantik.pressroom.ui.controllers.UISecurityEnforced;
import com.wikantik.pressroom.ui.restapi.Paging;
import com.wikantik.pressroom.ui.restapi.auth.AdminScoped;
import com.wikantik.pressroom.ui.restapi.dto.BusinessDtos;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The business profiles a weblog may point at: what its settings form's picker offers, so an owner
 * can learn the id to send as {@code businessId} on {@code PATCH /v1/weblogs/{handle}} (spec
 * 2026-11-20-api-gap-resources-design, Task 5). Read only; profiles are changed by a site admin.
 *
 * <p>The settings form's permission, {@code WeblogPermission.ADMIN} on the weblog, as
 * {@link WeblogSettingsApi} declares it, enforced by {@code PressroomHandlerInterceptor}, with an
 * {@code ADMIN}-role token ({@code @AdminScoped}, as the settings routes the ids are used on). Its
 * own controller because declarations are per class and {@link BusinessesApi} declares no weblog.
 */
@Tag(name = "Weblog business profiles", description = "The business profiles a weblog's owner may point the "
        + "weblog at.")
@RestController
@RequestMapping("/v1/weblogs/{handle}/businesses")
@AdminScoped
public class WeblogBusinessesApi extends BaseApiController implements UISecurityEnforced {

    /** Every business profile as a choice, sorted by name ignoring case, then id. */
    @Operation(operationId = "weblogBusinessesList", summary = "The business profiles this weblog may point at.")
    @GetMapping("")
    public BusinessDtos.BusinessChoicePage list(HttpServletRequest request,
            @Parameter(description = "How many profiles to skip. Default 0.")
            @RequestParam(value = "offset", defaultValue = "0") int offset,
            @Parameter(description = "The page size: default 50, at most 200.")
            @RequestParam(value = "limit", defaultValue = "50") int limit) throws WebloggerException {
        Paging.requireRange(limit, offset);
        requireActionWeblog(request);
        int bounded = Math.min(limit, Paging.MAX_LIMIT);
        List<Business> all = BusinessDtos.sorted(weblogger.getBusinessManager().getBusinesses());
        int from = Math.min(offset, all.size());
        int to = (int) Math.min((long) from + bounded, all.size());
        List<BusinessDtos.BusinessChoice> items = all.subList(from, to).stream().map(BusinessDtos::toChoice).toList();
        return new BusinessDtos.BusinessChoicePage(items, offset, bounded, to < all.size(), all.size());
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
        return List.of(WeblogPermission.ADMIN);
    }

    @Override
    public List<String> requiredGlobalPermissionActions() {
        return List.of();
    }
}
