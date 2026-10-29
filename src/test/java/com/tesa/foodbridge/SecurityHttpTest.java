
package com.tesa.foodbridge;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SecurityHttpTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    private HttpHeaders basicAuth(String username, String password) {
        HttpHeaders headers = new HttpHeaders();

        String credentials = username + ":" + password;
        String encoded = Base64.getEncoder()
                .encodeToString(credentials.getBytes(StandardCharsets.UTF_8));

        headers.set("Authorization", "Basic " + encoded);
        return headers;
    }

    @Test
    void publicListingsCanBeViewedWithoutAuthentication() {
        ResponseEntity<String> response =
                restTemplate.getForEntity(url("/api/listings"), String.class);

        assertEquals(HttpStatus.OK, response.getStatusCode());
    }

    @Test
    void protectedEndpointRejectsUnauthenticatedRequest() {
        ResponseEntity<String> response =
                restTemplate.getForEntity(url("/api/auth/me"), String.class);

        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
    }

    @Test
    void validDonorCanAccessOwnProfile() {
        HttpEntity<Void> request = new HttpEntity<>(
                basicAuth("donor1", "donor123")
        );

        ResponseEntity<String> response = restTemplate.exchange(
                url("/api/auth/me"),
                HttpMethod.GET,
                request,
                String.class
        );

        assertEquals(HttpStatus.OK, response.getStatusCode());
    }

    @Test
    void invalidCredentialsAreRejected() {
        HttpEntity<Void> request = new HttpEntity<>(
                basicAuth("donor1", "wrong-password")
        );

        ResponseEntity<String> response = restTemplate.exchange(
                url("/api/auth/me"),
                HttpMethod.GET,
                request,
                String.class
        );

        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
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

    @Test
    void emptyImageUploadReturnsBadRequest() {
        HttpHeaders headers = basicAuth("donor1", "donor123");
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);

        ByteArrayResource emptyFile = new ByteArrayResource(new byte[0]) {
            @Override
            public String getFilename() {
                return "empty.png";
            }
        };

        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", emptyFile);

        HttpEntity<MultiValueMap<String, Object>> request =
                new HttpEntity<>(body, headers);

        ResponseEntity<String> response = restTemplate.postForEntity(
                url("/api/listings/images"),
                request,
                String.class
        );

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    }
}
