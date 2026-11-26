package com.wikantik.pressroom.ui.restapi;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import io.swagger.v3.core.converter.AnnotatedType;
import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.SpecVersion;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.parameters.PathParameter;
import io.swagger.v3.oas.models.responses.ApiResponse;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.stereotype.Component;

/**
 * What openapi.json says about failures (spec 2026-11-20-api-contract-design,
 * Task 4). Every operation gets a {@code default} response: an {@link
 * ApiProblem} served as {@code application/problem+json}. springdoc cannot see
 * that on its own, because {@code ApiExceptionHandler} answers a {@code
 * ResponseEntity} with no fixed status.
 *
 * <p>{@code code} stays a plain string. Its known values ride along as {@code
 * x-extensible-enum}, every {@link ProblemCode} when the document was served:
 * a generator that turned them into a closed enum would make an older client
 * refuse a body carrying a code added after it was built.
 */
@Component
public class ApiDocumentCustomizer implements OpenApiCustomizer {

    private static final Pattern PATH_VARIABLE = Pattern.compile("\\{([^}/]+)}");

    static final String PROBLEM_SCHEMA = "ApiProblem";

    static final String PROBLEM_JSON = "application/problem+json";

    static final String DEFAULT_DESCRIPTION = "The request failed: a problem detail (RFC 9457) whose code says why.";

    @Override
    @SuppressWarnings("rawtypes") // swagger's model API speaks raw Schema maps
    public void customise(OpenAPI openApi) {
        if (openApi.getComponents() == null) {
            openApi.setComponents(new Components());
        }
        Components components = openApi.getComponents();
        Map<String, Schema> problemSchemas = ModelConverters.getInstance(openApi.getSpecVersion() == SpecVersion.V31)
                .readAll(new AnnotatedType(ApiProblem.class));
        problemSchemas.forEach(components::addSchemas);
        List<String> codes = Arrays.stream(ProblemCode.values()).map(ProblemCode::code).toList();
        for (Schema<?> schema : problemSchemas.values()) {
            // the problem and its field error each carry code
            Map<String, Schema> properties = schema.getProperties();
            properties.get("code").addExtension("x-extensible-enum", codes);
        }
        describeBusinessMembers(components);
        if (openApi.getPaths() == null) {
            return;
        }
        openApi.getPaths().forEach((path, item) -> item.readOperations().forEach(operation -> {
            operation.getResponses().addApiResponse("default", problemResponse());
            declareTemplatePathParameters(path, operation);
        }));
    }

    /**
     * Declares every {@code {name}} of the path that no handler parameter
     * does. The action weblog's {@code {handle}} sits in a class-level
     * mapping and is resolved by the handler interceptor, so no method binds it
     * and springdoc cannot see it; a typed client needs it. All are strings.
     */
    private static void declareTemplatePathParameters(String path, Operation operation) {
        Matcher matcher = PATH_VARIABLE.matcher(path);
        while (matcher.find()) {
            String name = matcher.group(1);
            boolean declared = operation.getParameters() != null && operation.getParameters().stream()
                    .anyMatch(p -> "path".equals(p.getIn()) && name.equals(p.getName()));
            if (!declared) {
                operation.addParametersItem(new PathParameter().name(name).required(true).schema(new StringSchema()));
            }
        }
    }

    /**
     * Text for members of business-tier records, which import no swagger type
     * (BusinessTierImportsTest): the revision store's largest-items rows.
     */
    @SuppressWarnings("rawtypes") // swagger's model API speaks raw Schema maps
    private static void describeBusinessMembers(Components components) {
        Map<String, Schema> schemas = components.getSchemas();
        Schema<?> item = schemas == null ? null : schemas.get("ItemCount");
        if (item != null && item.getProperties() != null && item.getProperties().get("kind") != null) {
            item.getProperties().get("kind").setDescription("ENTRY or PAGE: whose revisions these are.");
        }
    }

    private static ApiResponse problemResponse() {
        Schema<?> problem = new Schema<>().$ref("#/components/schemas/" + PROBLEM_SCHEMA);
        return new ApiResponse().description(DEFAULT_DESCRIPTION)
                .content(new Content().addMediaType(PROBLEM_JSON, new MediaType().schema(problem)));
    }
}
