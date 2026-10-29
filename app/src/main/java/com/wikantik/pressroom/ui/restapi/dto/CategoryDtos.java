package com.wikantik.pressroom.ui.restapi.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.wikantik.pressroom.pojos.TagStat;
import com.wikantik.pressroom.pojos.WeblogCategory;

/**
 * Views of a weblog category and of a weblog's tags, for the automation API.
 */
public final class CategoryDtos {

    private CategoryDtos() {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record CategoryView(String id, String name, String description, int entryCount) {
    }

    /**
     * The body of a create/update request. Both fields are optional on a
     * PATCH -- a null field leaves the corresponding property unchanged --
     * but {@code name} is required on create; {@link
     * com.wikantik.pressroom.ui.restapi.v1.CategoriesApi} enforces that,
     * not this record.
     */
    public record CategoryWrite(String name, String description) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record TagView(String name, int count) {
    }

    public static CategoryView toView(WeblogCategory category, int entryCount) {
        return new CategoryView(category.getId(), category.getName(),
                category.getDescription(), entryCount);
    }

    public static TagView toView(TagStat stat) {
        return new TagView(stat.getName(), stat.getCount());
    }
}
