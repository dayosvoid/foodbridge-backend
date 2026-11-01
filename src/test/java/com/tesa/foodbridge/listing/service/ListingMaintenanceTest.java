package com.tesa.foodbridge.listing.service;

import com.tesa.foodbridge.listing.api.ListingOperations;
import com.tesa.foodbridge.shared.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Testcontainers
class ListingMaintenanceTest {
    @Container static final PostgreSQLContainer<?> DB=new PostgreSQLContainer<>("postgres:17-alpine");
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",DB::getJdbcUrl);
        r.add("spring.datasource.username",DB::getUsername);
        r.add("spring.datasource.password",DB::getPassword);
        r.add("foodbridge.demo.seed-enabled",()->"false");
    }
    @Autowired JdbcTemplate jdbc;
    @Autowired DefaultListingOperations listings;
    ListingOperations.CreateListing command(BigDecimal quantity) {
        return new ListingOperations.CreateListing("Food","Description","OTHER",quantity,"ITEMS",null,"Lagos",Instant.now().plusSeconds(3600));
    }
    Actor donor() {
        UUID id=UUID.randomUUID();
        jdbc.update("insert into users(id,email,password_hash,display_name,role) values (?,?, 'disabled','Owner','DONOR')",id,id+"@example.invalid");
        return new Actor(id,Actor.Role.DONOR);
    }
    @Test void updatesAndExpirationPreserveDeletedRows() {
        Actor owner=donor();
        UUID editable=listings.create(owner,command(BigDecimal.TEN)).id();
        UUID deleted=listings.create(owner,command(BigDecimal.TEN)).id();
        listings.delete(owner,deleted);
        assertEquals(new BigDecimal("5.00"),listings.update(owner,editable,command(new BigDecimal("5"))).quantity());
        assertEquals("FORBIDDEN",assertThrows(RuleViolation.class,()->listings.update(donor(),editable,command(BigDecimal.ONE))).code());
        assertEquals("LISTING_NOT_EDITABLE",assertThrows(RuleViolation.class,()->listings.update(owner,deleted,command(BigDecimal.ONE))).code());
        jdbc.update("update listings set collection_deadline=clock_timestamp()-interval '1 second'");
        listings.expireListings();
        assertEquals("EXPIRED",listings.get(editable).status());
        assertEquals("DELETED",listings.get(deleted).status());
    }
}
