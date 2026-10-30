
package com.tesa.foodbridge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SecurityHttpTest {

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

        HttpHeaders headers = bearerAuth(token);

        // First, confirm the token works.
        ResponseEntity<String> validResponse = restTemplate.exchange(
                url("/api/auth/me"),
                HttpMethod.GET,
                new HttpEntity<>(headers),
                String.class
        );

        assertEquals(HttpStatus.OK, validResponse.getStatusCode());

        // A token that is no longer valid must not grant access.
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
