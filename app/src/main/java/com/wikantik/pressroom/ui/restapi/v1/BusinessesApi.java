package com.wikantik.pressroom.ui.restapi.v1;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.business.BusinessManager;
import com.wikantik.pressroom.pojos.Business;
import com.wikantik.pressroom.pojos.GlobalPermission;
import com.wikantik.pressroom.ui.controllers.BusinessRules;
import com.wikantik.pressroom.ui.controllers.UISecurityEnforced;
import com.wikantik.pressroom.ui.restapi.ApiException;
import com.wikantik.pressroom.ui.restapi.Paging;
import com.wikantik.pressroom.ui.restapi.ProblemCode;
import com.wikantik.pressroom.ui.restapi.RuleProblems;
import com.wikantik.pressroom.ui.restapi.auth.AdminScoped;
import com.wikantik.pressroom.ui.restapi.dto.BusinessDtos;
import com.wikantik.pressroom.ui.restapi.dto.ClearFields;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The site's business profiles: the shared records blogs point at with {@code businessId}, shown
 * as schema.org data on every blog that uses one (spec 2026-11-20-api-gap-resources-design,
 * Task 5). What the Businesses admin screen does, over the API.
 *
 * <p>The screen's permission, {@code GlobalPermission.ADMIN} with no weblog
 * ({@code BusinessesController}'s), enforced by {@code PressroomHandlerInterceptor}; an
 * {@code ADMIN}-role token ({@code @AdminScoped}). A write is judged and stored by the screen's
 * own {@link BusinessRules}, so the two cannot store a value differently, and a save drops the
 * site-wide cache of the blogs using the business, as the screen's does.
 */
@Tag(name = "Business profiles", description = "The site's shared business profiles, which blogs show as "
        + "schema.org data (site admins).")
@RestController
@RequestMapping("/v1/admin/businesses")
@AdminScoped
public class BusinessesApi extends BaseApiController implements UISecurityEnforced {

    /**
     * Every business profile, sorted by name ignoring case, then id, each with how many weblogs use
     * it, as the Businesses screen lists them. {@code total} counts every profile, not the page.
     */
    @Operation(operationId = "businessesList", summary = "The site's business profiles.")
    @GetMapping("")
    public BusinessDtos.BusinessPage list(
            @Parameter(description = "How many profiles to skip. Default 0.")
            @RequestParam(value = "offset", defaultValue = "0") int offset,
            @Parameter(description = "The page size: default 50, at most 200.")
            @RequestParam(value = "limit", defaultValue = "50") int limit) throws WebloggerException {
        Paging.requireRange(limit, offset);
        int bounded = Math.min(limit, Paging.MAX_LIMIT);
        BusinessManager manager = weblogger.getBusinessManager();
        List<Business> all = BusinessDtos.sorted(manager.getBusinesses());
        int from = Math.min(offset, all.size());
        int to = (int) Math.min((long) from + bounded, all.size());
        List<BusinessDtos.BusinessView> items = new ArrayList<>();
        for (Business business : all.subList(from, to)) {
            items.add(BusinessDtos.toView(business, manager.countWeblogsUsing(business)));
        }
        return new BusinessDtos.BusinessPage(items, offset, bounded, to < all.size(), all.size());
    }

    @Operation(operationId = "businessesGet", summary = "One business profile.")
    @GetMapping("/{id}")
    public BusinessDtos.BusinessView get(@PathVariable("id") String id) throws WebloggerException {
        Business business = require(id);
        return BusinessDtos.toView(business, weblogger.getBusinessManager().countWeblogsUsing(business));
    }

    /**
     * Creates a business profile. {@code name} is required; {@code businessType} defaults to
     * {@code Organization}. Every rule it breaks is named in one 400.
     */
    @Operation(operationId = "businessesCreate", summary = "Create a business profile.")
    @ApiResponse(responseCode = "201", description = "Created.")
    @PostMapping("")
    public ResponseEntity<BusinessDtos.BusinessView> create(@RequestBody BusinessDtos.BusinessWrite body)
            throws WebloggerException {
        if (body.clear() != null && !body.clear().isEmpty()) {
            throw ApiException.problemOnField("clear", ProblemCode.FIELD_UPDATE_ONLY, "clear");
        }
        BusinessRules.Values base = new BusinessRules.Values(null, Business.BusinessType.Organization.name(), null,
                null, null, null, null, null, null, null);
        Business business = new Business();
        save(business, BusinessDtos.overlay(base, body, Set.of()));
        return ResponseEntity.status(HttpStatus.CREATED).body(BusinessDtos.toView(business, 0));
    }

    /**
     * Changes the fields sent; a field named in {@code clear} is removed. The business is judged
     * as it will be after the change, and nothing is stored unless every rule passes.
     */
    @Operation(operationId = "businessesUpdate", summary = "Change a business profile.")
    @PatchMapping("/{id}")
    public BusinessDtos.BusinessView update(@PathVariable("id") String id,
            @RequestBody BusinessDtos.BusinessWrite body) throws WebloggerException {
        Business business = require(id);
        Set<String> cleared = ClearFields.validate(body.clear(), body, BusinessDtos.CLEARABLE, List.of());
        save(business, BusinessDtos.overlay(BusinessRules.Values.of(business), body, cleared));
        return BusinessDtos.toView(business, weblogger.getBusinessManager().countWeblogsUsing(business));
    }

    /**
     * Deletes a business profile no weblog uses; one in use is a 409 saying how many weblogs use
     * it, and nothing is removed. A count that cannot be taken is a 500, never a delete.
     */
    @Operation(operationId = "businessesDelete", summary = "Delete an unused business profile.")
    @ApiResponse(responseCode = "204", description = "Done; no body.")
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> remove(@PathVariable("id") String id) throws WebloggerException {
        Business business = require(id);
        BusinessManager manager = weblogger.getBusinessManager();
        long uses = manager.countWeblogsUsing(business);
        if (uses > 0) {
            throw ApiException.problem(ProblemCode.BUSINESS_IN_USE, Long.toString(uses));
        }
        manager.removeBusiness(business);
        weblogger.flush();
        return ResponseEntity.noContent().build();
    }

    /** Judges {@code values}, refusing every broken rule in one 400; then stores them as the screen does. */
    private void save(Business business, BusinessRules.Values values) throws WebloggerException {
        RuleProblems.refuseViolations(BusinessRules.problems(values));
        BusinessRules.apply(values, business);
        BusinessManager manager = weblogger.getBusinessManager();
        manager.saveBusiness(business);
        weblogger.flush();
        BusinessRules.invalidateUsers(business, manager);
    }

    /** The business with this id; 404 when there is none. A lookup that cannot run is a 500. */
    private Business require(String id) throws WebloggerException {
        Business business = weblogger.getBusinessManager().getBusiness(id);
        if (business == null) {
            throw ApiException.problem(ProblemCode.BUSINESS_NOT_FOUND);
        }
        return business;
    }

    @Override
    public boolean isUserRequired() {
        return true;
    }

    @Override
    public boolean isWeblogRequired() {
        return false;
    }

    @Override
    public List<String> requiredWeblogPermissionActions() {
        return List.of();
    }

    @Override
    public List<String> requiredGlobalPermissionActions() {
        return List.of(GlobalPermission.ADMIN);
    }
}
