
package com.tesa.foodbridge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.parser.OpenAPIV3Parser;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.OffsetDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
@ActiveProfiles("demo")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "foodbridge.demo.seed-enabled=true",
                "DEMO_PASSWORD_HASH=test-only-hash"
        }
)
class OpenApiContractTest {

    @Container
    static final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("foodbridge_test")
                    .withUsername("postgres")
                    .withPassword("postgres");

    @DynamicPropertySource
    static void configureDatabase(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    private OpenAPI loadOpenApi() {
        return new OpenAPIV3Parser().read("contracts/openapi.yaml");
    }

    @Test
    void listingsEndpointMatchesDocumentedResponseStructure() throws Exception {

        OpenAPI openAPI = loadOpenApi();

        assertNotNull(openAPI, "OpenAPI document could not be loaded");

        PathItem listingPath =
                openAPI.getPaths().get("/api/listings");

        assertNotNull(listingPath,
                "Listings endpoint is missing from OpenAPI");

        assertNotNull(listingPath.getGet(),
                "GET method is missing from OpenAPI");

        assertNotNull(
                listingPath.getGet().getResponses().get("200"),
                "OpenAPI does not document a 200 response"
        );

        ResponseEntity<String> response =
                restTemplate.getForEntity(
                        url("/api/listings"),
                        String.class
                );

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());

        JsonNode listings =
                objectMapper.readTree(response.getBody());

        assertTrue(listings.isArray(),
                "GET /api/listings must return a JSON array");

        for (JsonNode listing : listings) {

            assertTrue(listing.hasNonNull("id"));
            UUID.fromString(listing.get("id").asText());

            assertTrue(listing.hasNonNull("foodName"));
            assertTrue(listing.get("foodName").isTextual());

            assertTrue(listing.hasNonNull("description"));
            assertTrue(listing.get("description").isTextual());

            assertTrue(listing.hasNonNull("category"));
            assertTrue(listing.get("category").isTextual());

            assertTrue(listing.hasNonNull("quantity"));
            assertTrue(listing.get("quantity").isNumber());

            assertTrue(listing.hasNonNull("unit"));
            assertTrue(listing.get("unit").isTextual());

            assertTrue(listing.hasNonNull("pickupArea"));
            assertTrue(listing.get("pickupArea").isTextual());

            assertTrue(listing.hasNonNull("collectionDeadline"));
            OffsetDateTime.parse(
                    listing.get("collectionDeadline").asText()
            );

            assertTrue(listing.hasNonNull("status"));

            assertTrue(listing.hasNonNull("donorId"));
            UUID.fromString(listing.get("donorId").asText());

            assertTrue(listing.hasNonNull("donorName"));
            assertTrue(listing.get("donorName").isTextual());

            assertTrue(listing.hasNonNull("createdAt"));
            OffsetDateTime.parse(
                    listing.get("createdAt").asText()
            );

            assertTrue(listing.hasNonNull("pendingRequestCount"));
            assertTrue(
                    listing.get("pendingRequestCount").isIntegralNumber()
            );

            if (listing.has("imageReference")
                    && !listing.get("imageReference").isNull()) {

                assertTrue(
                        listing.get("imageReference").isTextual()
                );
            }
        }
    }

    @Test
    void loginEndpointMatchesDocumentedResponseStructure() throws Exception {

        OpenAPI openAPI = loadOpenApi();

        assertNotNull(openAPI);

        PathItem loginPath =
                openAPI.getPaths().get("/api/auth/login");

        assertNotNull(loginPath,
                "Login endpoint is missing from OpenAPI");

        assertNotNull(loginPath.getPost(),
                "POST login is not documented");

        assertNotNull(
                loginPath.getPost().getResponses().get("200"),
                "Login success response is not documented"
        );

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        String requestBody =
                "{\"email\":\"donor1@demo.foodbridge.invalid\","
                        + "\"password\":\"donor123\"}";

        ResponseEntity<String> response =
                restTemplate.postForEntity(
                        url("/api/auth/login"),
                        new HttpEntity<>(requestBody, headers),
                        String.class
                );

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());

        JsonNode login =
                objectMapper.readTree(response.getBody());

        assertTrue(login.hasNonNull("token"));
        assertTrue(login.get("token").isTextual());
        assertFalse(login.get("token").asText().isBlank());

        assertTrue(login.hasNonNull("id"));
        assertTrue(login.get("id").isTextual());

        assertTrue(login.hasNonNull("name"));
        assertTrue(login.get("name").isTextual());

        assertTrue(login.hasNonNull("role"));

        String role = login.get("role").asText();

        assertTrue(
                role.equals("DONOR")
                        || role.equals("RECIPIENT")
                        || role.equals("ADMIN"),
                "Login returned an invalid user role"
        );

        assertTrue(login.hasNonNull("organisationName"));
        assertTrue(login.get("organisationName").isTextual());

        assertTrue(login.hasNonNull("email"));
        assertTrue(login.get("email").isTextual());

        assertEquals(
                "donor1@demo.foodbridge.invalid",
                login.get("email").asText()
        );
    }

    @Test
    void myRequestsEndpointRejectsUnauthenticatedAccess() {

        OpenAPI openAPI = loadOpenApi();

        assertNotNull(openAPI);

        PathItem requestsPath =
                openAPI.getPaths().get("/api/requests");

        assertNotNull(requestsPath);
        assertNotNull(requestsPath.getGet());

        assertNotNull(
                requestsPath.getGet().getResponses().get("401")
        );

        ResponseEntity<String> response =
                restTemplate.getForEntity(
                        url("/api/requests?mine=true"),
                        String.class
                );

        assertEquals(
                HttpStatus.UNAUTHORIZED,
                response.getStatusCode()
        );
    }

    @Test
    void listingRequestsEndpointRejectsUnauthenticatedAccess() {

        OpenAPI openAPI = loadOpenApi();

        assertNotNull(openAPI);

        PathItem requestsPath =
                openAPI.getPaths().get("/api/listings/{listingId}/requests");

        assertNotNull(requestsPath);
        assertNotNull(requestsPath.getGet());

        assertNotNull(
                requestsPath.getGet().getResponses().get("401")
        );

        String listingId = UUID.randomUUID().toString();

        ResponseEntity<String> response =
                restTemplate.getForEntity(
                        url("/api/listings/" + listingId + "/requests"),
                        String.class
                );

        assertEquals(
                HttpStatus.UNAUTHORIZED,
                response.getStatusCode()
        );
    }

    @Test
    void authenticatedRecipientCanAccessOwnRequests() throws Exception {

        OpenAPI openAPI = loadOpenApi();

        assertNotNull(openAPI);

        PathItem requestsPath =
                openAPI.getPaths().get("/api/requests");

        assertNotNull(requestsPath);
        assertNotNull(requestsPath.getGet());

        assertNotNull(
                requestsPath.getGet().getResponses().get("200")
        );

        // Log in as recipient
        HttpHeaders loginHeaders = new HttpHeaders();
        loginHeaders.setContentType(MediaType.APPLICATION_JSON);

        String loginBody =
                "{\"email\":\"recipient1@demo.foodbridge.invalid\","
                        + "\"password\":\"recipient123\"}";

        ResponseEntity<String> loginResponse =
                restTemplate.postForEntity(
                        url("/api/auth/login"),
                        new HttpEntity<>(loginBody, loginHeaders),
                        String.class
                );

        assertEquals(HttpStatus.OK, loginResponse.getStatusCode());
        assertNotNull(loginResponse.getBody());

        JsonNode login =
                objectMapper.readTree(loginResponse.getBody());

        assertEquals("RECIPIENT", login.get("role").asText());

        String token = login.get("token").asText();

        assertFalse(token.isBlank());

        // Access own requests using bearer token
        HttpHeaders requestHeaders = new HttpHeaders();
        requestHeaders.setBearerAuth(token);

        ResponseEntity<String> requestsResponse =
                restTemplate.exchange(
                        url("/api/requests?mine=true"),
                        org.springframework.http.HttpMethod.GET,
                        new HttpEntity<>(requestHeaders),
                        String.class
                );

        assertEquals(
                HttpStatus.OK,
                requestsResponse.getStatusCode()
        );

        assertNotNull(requestsResponse.getBody());

        JsonNode requests =
                objectMapper.readTree(requestsResponse.getBody());

        assertTrue(
                requests.isArray(),
                "My requests endpoint must return a JSON array"
        );

        // Validate returned requests, if any exist
        for (JsonNode request : requests) {

            assertTrue(request.hasNonNull("id"));
            UUID.fromString(request.get("id").asText());

            assertTrue(request.hasNonNull("listingId"));
            UUID.fromString(request.get("listingId").asText());


            assertTrue(request.hasNonNull("recipientId"));
            UUID.fromString(request.get("recipientId").asText());

            assertTrue(request.hasNonNull("recipientName"));
            assertTrue(request.get("recipientName").isTextual());

            assertTrue(request.hasNonNull("status"));

            assertTrue(request.hasNonNull("createdAt"));
            OffsetDateTime.parse(
                    request.get("createdAt").asText()
            );

            // Every returned request must belong to this recipient
            assertEquals(
                    login.get("id").asText(),
                    request.get("recipientId").asText()
            );
        }
    }
}
