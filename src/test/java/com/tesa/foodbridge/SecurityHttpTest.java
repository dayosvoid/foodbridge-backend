
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

@org.springframework.test.context.ActiveProfiles("demo")
@org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "foodbridge.demo.seed-enabled=false")
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

    @Autowired private org.springframework.test.web.servlet.MockMvc http;
    @Autowired private org.springframework.jdbc.core.JdbcTemplate jdbc;
    @Autowired private com.tesa.foodbridge.listing.api.ListingOperations listings;
    @Autowired private com.tesa.foodbridge.reservation.api.ReservationOperations requests;

    private JsonNode donorLogin(String name) throws Exception {
        var response = restTemplate.postForEntity(url("/api/auth/login"),
                new HttpEntity<>(java.util.Map.of("email", name+"@demo.foodbridge.invalid", "password", "donor123")), String.class);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        return objectMapper.readTree(response.getBody());
    }

    @Test void bothDonorTokensEnforceIndependentListingOwnership() throws Exception {
        var donor1=new com.tesa.foodbridge.shared.Actor(com.tesa.foodbridge.demo.DemoSeed.id("donor1"),com.tesa.foodbridge.shared.Actor.Role.DONOR);
        var donor2=new com.tesa.foodbridge.shared.Actor(com.tesa.foodbridge.demo.DemoSeed.id("donor2"),com.tesa.foodbridge.shared.Actor.Role.DONOR);
        var recipient=new com.tesa.foodbridge.shared.Actor(java.util.UUID.randomUUID(),com.tesa.foodbridge.shared.Actor.Role.RECIPIENT);
        for(var actor:java.util.List.of(donor1,donor2,recipient)) {
            String email=actor.equals(donor1)?"donor1@demo.foodbridge.invalid":actor.equals(donor2)?"donor2@demo.foodbridge.invalid":actor.id()+"@example.invalid";
            jdbc.update("insert into users(id,email,password_hash,display_name,role) values (?,?,?,'Existing demo account',?)",
                    actor.id(),email,"preserved-existing-hash",actor.role().name());
        }
        var usersBefore=jdbc.queryForList("select * from users order by id");
        JsonNode first=donorLogin("donor1"), second=donorLogin("donor2");
        String token1=first.get("token").asText(),token2=second.get("token").asText();
        assertTrue(token1.length()>=40);
        assertTrue(token2.length()>=40);
        assertNotEquals(token1,token2);
        for(var entry:java.util.List.of(java.util.Map.entry(first,donor1),java.util.Map.entry(second,donor2))) {
            assertEquals(entry.getValue().id().toString(),entry.getKey().get("id").asText());
            assertEquals("DONOR",entry.getKey().get("role").asText());
            var response=restTemplate.exchange(url("/api/auth/me"),HttpMethod.GET,
                    new HttpEntity<>(bearerAuth(entry.getKey().get("token").asText())),String.class);
            assertEquals(HttpStatus.OK,response.getStatusCode());
            JsonNode profile=objectMapper.readTree(response.getBody());
            assertEquals(entry.getValue().id().toString(),profile.get("id").asText());
            assertEquals("DONOR",profile.get("role").asText());
        }
        var command=new com.tesa.foodbridge.listing.api.ListingOperations.CreateListing("Demo food","Assessment donation","OTHER",java.math.BigDecimal.TEN,"ITEMS",null,"Lagos",java.time.Instant.now().plusSeconds(3600));
        var listing1=listings.create(donor1,command);
        var listing2=listings.create(donor2,command);
        var request1=requests.request(recipient,listing1.id(),null);
        var request2=requests.request(recipient,listing2.id(),null);
        for(String action:java.util.List.of("approve","decline")) {
            http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch("/api/requests/"+request2.id()+"/"+action).header("Authorization","Bearer "+token1))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isForbidden());
            http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch("/api/requests/"+request1.id()+"/"+action).header("Authorization","Bearer "+token2))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isForbidden());
        }
        for(var entry:java.util.List.of(java.util.Map.entry(request1,token1),java.util.Map.entry(request2,token2))) {
            assertEquals("PENDING",requests.get(recipient,entry.getKey().id()).status());
            http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch("/api/requests/"+entry.getKey().id()+"/approve").header("Authorization","Bearer "+entry.getValue()))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
            assertEquals("APPROVED",requests.get(recipient,entry.getKey().id()).status());
        }
        // Both can also decline eligible requests on their own listings.
        for(var entry:java.util.List.of(java.util.Map.entry(donor1,token1),java.util.Map.entry(donor2,token2))) {
            var listing=listings.create(entry.getKey(),command);
            var pending=requests.request(recipient,listing.id(),null);
            http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch("/api/requests/"+pending.id()+"/decline").header("Authorization","Bearer "+entry.getValue()))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
            assertEquals("DECLINED",requests.get(recipient,pending.id()).status());
        }
        assertEquals(usersBefore,jdbc.queryForList("select * from users order by id"));
    }

    @Test void bothDonorsRejectIncorrectPasswords() {
        for(String name:java.util.List.of("donor1","donor2")) {
            var response=restTemplate.postForEntity(url("/api/auth/login"),
                    new HttpEntity<>(java.util.Map.of("email",name+"@demo.foodbridge.invalid","password","wrong-password")),String.class);
            assertEquals(HttpStatus.UNAUTHORIZED,response.getStatusCode());
        }
    }

    @Test void allSeededRecipientsAuthenticateAndRequestOnlyAsThemselves() throws Exception {
        var identities=java.util.Map.of(
                "recipient1@demo.foodbridge.invalid","c8840d79-f188-3367-a470-d3dc9609a640",
                "recipient2@demo.foodbridge.invalid","668cefe8-0c95-34f2-9823-92ac376b8cf8",
                "recipient3@demo.foodbridge.invalid","034f933b-74f1-3e77-b2ac-a069fe91c637");
        var donor=new com.tesa.foodbridge.shared.Actor(java.util.UUID.randomUUID(),com.tesa.foodbridge.shared.Actor.Role.DONOR);
        jdbc.update("insert into users(id,email,password_hash,display_name,role) values (?,?,'existing-test-hash','Donor','DONOR')",donor.id(),donor.id()+"@example.invalid");
        for(var identity:identities.entrySet())
            jdbc.update("insert into users(id,email,password_hash,display_name,role) values (?,?,'preserved-existing-hash','Existing recipient','RECIPIENT')",java.util.UUID.fromString(identity.getValue()),identity.getKey());
        var usersBefore=jdbc.queryForList("select * from users order by id");
        var command=new com.tesa.foodbridge.listing.api.ListingOperations.CreateListing("Demo food","Recipient authentication test","OTHER",java.math.BigDecimal.TEN,"ITEMS",null,"Lagos",java.time.Instant.now().plusSeconds(3600));
        var listing=listings.create(donor,command);
        var tokens=new java.util.HashSet<String>();
        for(var identity:identities.entrySet()) {
            var login=restTemplate.postForEntity(url("/api/auth/login"),new HttpEntity<>(java.util.Map.of("email",identity.getKey(),"password","recipient123")),String.class);
            assertEquals(HttpStatus.OK,login.getStatusCode());
            var body=objectMapper.readTree(login.getBody());
            assertEquals(identity.getValue(),body.get("id").asText());
            assertEquals("RECIPIENT",body.get("role").asText());
            String token=body.get("token").asText();
            assertTrue(token.length()>=40);
            assertTrue(tokens.add(token));
            var profile=restTemplate.exchange(url("/api/auth/me"),HttpMethod.GET,new HttpEntity<>(bearerAuth(token)),String.class);
            assertEquals(HttpStatus.OK,profile.getStatusCode());
            var me=objectMapper.readTree(profile.getBody());
            assertEquals(identity.getValue(),me.get("id").asText());
            assertEquals(identity.getKey(),me.get("email").asText());
            assertEquals("RECIPIENT",me.get("role").asText());
            var result=http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/listings/"+listing.id()+"/requests")
                    .header("Authorization","Bearer "+token).header("Idempotency-Key",java.util.UUID.randomUUID().toString()))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isCreated()).andReturn();
            var request=objectMapper.readTree(result.getResponse().getContentAsString());
            assertEquals(identity.getValue(),request.get("recipientId").asText());
            assertEquals(identity.getValue(),jdbc.queryForObject("select requester_id::text from requests where id=?",String.class,java.util.UUID.fromString(request.get("id").asText())));
            http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch("/api/requests/"+request.get("id").asText()+"/approve").header("Authorization","Bearer "+token))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isForbidden());
            http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/listings/"+listing.id()).header("Authorization","Bearer "+token))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isForbidden());
            assertEquals("PENDING",jdbc.queryForObject("select status from requests where id=?",String.class,java.util.UUID.fromString(request.get("id").asText())));
        }
        assertEquals("AVAILABLE",listings.get(listing.id()).status());
        assertEquals(usersBefore,jdbc.queryForList("select * from users order by id"));
    }

    @Test void allRecipientsRejectIncorrectPasswords() {
        for(String email:java.util.List.of("recipient1@demo.foodbridge.invalid","recipient2@demo.foodbridge.invalid","recipient3@demo.foodbridge.invalid")) {
            var response=restTemplate.postForEntity(url("/api/auth/login"),new HttpEntity<>(java.util.Map.of("email",email,"password","wrong-password")),String.class);
            assertEquals(HttpStatus.UNAUTHORIZED,response.getStatusCode());
        }
    }

    @Test void corsPreflightAllowsVercelOriginMethodsHeadersAndExposesTotalCount() throws Exception {
        http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options("/api/requests/00000000-0000-0000-0000-000000000001/collected")
                .header("Origin", "https://food-bridge-ruby.vercel.app")
                .header("Access-Control-Request-Method", "PATCH")
                .header("Access-Control-Request-Headers", "Authorization, Content-Type, X-Request-Id, Idempotency-Key"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("Access-Control-Allow-Origin", "https://food-bridge-ruby.vercel.app"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("Access-Control-Allow-Credentials", "true"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("Access-Control-Expose-Headers", org.hamcrest.Matchers.containsString("X-Total-Count")))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("Access-Control-Allow-Methods", org.hamcrest.Matchers.containsString("PATCH")));
    }
}
