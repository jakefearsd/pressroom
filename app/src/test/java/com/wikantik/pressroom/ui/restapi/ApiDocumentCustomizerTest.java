package com.wikantik.pressroom.ui.restapi;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Paths;
import io.swagger.v3.oas.models.SpecVersion;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * {@link ApiDocumentCustomizer} on documents springdoc did not build:
 * OpenApiContractTest holds it to the real one. A document with no
 * components or paths yet still gains the problem schemas and does not fail.
 */
class ApiDocumentCustomizerTest {

    @Test
    void aBareDocumentGainsTheProblemSchemas() {
        OpenAPI openApi = new OpenAPI(SpecVersion.V31);

        new ApiDocumentCustomizer().customise(openApi);

        assertNotNull(openApi.getComponents().getSchemas().get("ApiProblem"));
        assertNotNull(openApi.getComponents().getSchemas().get("ProblemFieldError"));
        assertNull(openApi.getPaths());
    }

    @Test
    void everyOperationGainsTheDefaultProblemAndKeepsItsOwnResponses() {
        Operation operation = new Operation().responses(new ApiResponses()
                .addApiResponse("200", new ApiResponse().description("OK")));
        OpenAPI openApi = new OpenAPI().paths(new Paths().addPathItem("/v1/x", new PathItem().get(operation)));

        new ApiDocumentCustomizer().customise(openApi);

        assertEquals("OK", operation.getResponses().get("200").getDescription());
        ApiResponse problem = operation.getResponses().get("default");
        assertEquals(ApiDocumentCustomizer.DEFAULT_DESCRIPTION, problem.getDescription());
        assertEquals("#/components/schemas/ApiProblem",
                problem.getContent().get("application/problem+json").getSchema().get$ref());
    }
}
