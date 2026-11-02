package com.tesa.foodbridge;

import io.swagger.v3.parser.OpenAPIV3Parser;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class OpenApiValidationTest {
    @Test void mergedContractIsValidAndDocumentsDeletion() {
        var result = new OpenAPIV3Parser().readLocation("contracts/openapi.yaml", null, null);
        assertTrue(result.getMessages().isEmpty(), () -> result.getMessages().toString());
        var api = result.getOpenAPI();
        assertNotNull(api);
        var detail = api.getPaths().get("/api/listings/{listingId}");
        assertNotNull(detail.getGet());
        assertNotNull(detail.getPut());
        assertNotNull(detail.getDelete().getResponses().get("409"));
        assertEquals("bearer",api.getComponents().getSecuritySchemes().get("bearerAuth").getScheme());
        assertTrue(((io.swagger.v3.oas.models.media.Schema<?>)api.getComponents().getSchemas().get("ListingResponse").getProperties().get("status")).getEnum().contains("DELETED"));
        assertNotNull(api.getPaths().get("/api/admin/requests"));
        assertNotNull(api.getPaths().get("/api/admin/requests").getGet());
        assertNotNull(api.getPaths().get("/api/listings").getGet().getResponses().get("200").getHeaders().get("X-Total-Count"));
        assertNotNull(api.getComponents().getSchemas().get("AdminDashboardResponse").getProperties().get("expiredListings"));
        assertNotNull(api.getComponents().getSchemas().get("AdminDashboardResponse").getProperties().get("estimatedPortionsSaved"));
    }
}
