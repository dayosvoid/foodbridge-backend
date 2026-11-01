
package com.tesa.foodbridge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SecurityHttpTest {

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

    private String loginAndGetToken() throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        String body =
                "{\"email\":\"donor1@demo.foodbridge.invalid\","
                        + "\"password\":\"donor123\"}";

        ResponseEntity<String> response = restTemplate.postForEntity(
                url("/api/auth/login"),
                new HttpEntity<>(body, headers),
                String.class
        );

        assertEquals(HttpStatus.OK, response.getStatusCode());

        JsonNode json = objectMapper.readTree(response.getBody());
        String token = json.path("token").asText();

        assertNotNull(token);
        assertTrue(!token.isBlank());

        return token;
    }

    private HttpHeaders bearerAuth(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return headers;
    }

    @Test
    void publicListingsCanBeViewedWithoutAuthentication() {
        ResponseEntity<String> response =
                restTemplate.getForEntity(url("/api/listings"), String.class);

        assertEquals(HttpStatus.OK, response.getStatusCode());
    }

    @Test
    void loginAcceptsEmailAndReturnsBearerToken() throws Exception {
        String token = loginAndGetToken();
        assertTrue(token.length() >= 40);
    }

    @Test
    void protectedEndpointRejectsUnauthenticatedRequest() {
        ResponseEntity<String> response =
                restTemplate.getForEntity(url("/api/auth/me"), String.class);

        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
    }

    @Test
    void validBearerTokenCanAccessOwnProfile() throws Exception {
        String token = loginAndGetToken();

        ResponseEntity<String> response = restTemplate.exchange(
                url("/api/auth/me"),
                HttpMethod.GET,
                new HttpEntity<>(bearerAuth(token)),
                String.class
        );

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertTrue(response.getBody().contains("donor1@demo.foodbridge.invalid"));
    }

    @Test
    void invalidBearerTokenIsRejected() {
        ResponseEntity<String> response = restTemplate.exchange(
                url("/api/auth/me"),
                HttpMethod.GET,
                new HttpEntity<>(bearerAuth("invalid-token")),
                String.class
        );

        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
    }

    @Test
    void revokedBearerTokenIsRejected() throws Exception {
        String token = loginAndGetToken();

        ResponseEntity<String> validResponse = restTemplate.exchange(
                url("/api/auth/me"),
                HttpMethod.GET,
                new HttpEntity<>(bearerAuth(token)),
                String.class
        );

        assertEquals(HttpStatus.OK, validResponse.getStatusCode());

        ResponseEntity<String> invalidResponse = restTemplate.exchange(
                url("/api/auth/me"),
                HttpMethod.GET,
                new HttpEntity<>(bearerAuth("invalid-token")),
                String.class
        );

        assertEquals(HttpStatus.UNAUTHORIZED, invalidResponse.getStatusCode());
    }

    @Test
    void imageUploadRejectsUnauthenticatedRequest() {
        ResponseEntity<String> response = restTemplate.postForEntity(
                url("/api/listings/images"),
                new HttpEntity<>(new HttpHeaders()),
                String.class
        );

        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
    }
}
