package com.wikantik.pressroom.ui.restapi.dto;

import io.swagger.v3.oas.annotations.media.Schema;
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
    public record CategoryView(String id, String name, String description, int entryCount,
            @Schema(description = "The category's icon: an absolute http or https URL; absent when it has none.")
            String image) {
    }

    /**
     * The body of a create/update request. Both fields are optional on a
     * PATCH -- a null field leaves the corresponding property unchanged --
     * but {@code name} is required on create; {@link
     * com.wikantik.pressroom.ui.restapi.v1.CategoriesApi} enforces that,
     * not this record.
     */
    public record CategoryWrite(
            @Schema(description = "The category's name, unique in the weblog. Required on create.")
            String name,
            @Schema(description = "What the category is for.")
            String description,
            @Schema(description = "An icon for the category: an absolute http or https URL, at most 255 "
                    + "characters. Blank on update removes it.")
            String image) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record TagView(String name, int count) {
    }

    public static CategoryView toView(WeblogCategory category, int entryCount) {
        return new CategoryView(category.getId(), category.getName(),
                category.getDescription(), entryCount, category.getImage());
    }

    public static TagView toView(TagStat stat) {
        return new TagView(stat.getName(), stat.getCount());
    }
}
