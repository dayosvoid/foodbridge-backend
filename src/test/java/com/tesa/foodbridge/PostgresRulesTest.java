package com.tesa.foodbridge;

import com.tesa.foodbridge.listing.api.ListingOperations;
import com.tesa.foodbridge.reservation.api.ReservationOperations;
import com.tesa.foodbridge.shared.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Testcontainers
class PostgresRulesTest {
    @Container static final PostgreSQLContainer<?> DB=new PostgreSQLContainer<>("postgres:17-alpine");
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",DB::getJdbcUrl);
        r.add("spring.datasource.username",DB::getUsername);
        r.add("spring.datasource.password",DB::getPassword);
    }
    @Autowired JdbcTemplate jdbc;
    @Autowired ListingOperations listings;
    @Autowired ReservationOperations requests;
    Actor donor, recipient;
    @BeforeEach void setup() {
        jdbc.execute("truncate activity,handovers,requests,listings,users");
        donor=user(Actor.Role.DONOR); recipient=user(Actor.Role.RECIPIENT);
    }
    Actor user(Actor.Role role) {
        UUID id=UUID.randomUUID();
        jdbc.update("insert into users(id,email,password_hash,display_name,role) values (?,?,?,?,?)",id,id+"@example.invalid","disabled","Simulated",role.name());
        return new Actor(id,role);
    }
    UUID listing() { return listings.create(donor,command(BigDecimal.TEN)).id(); }
    ListingOperations.CreateListing command(BigDecimal quantity) {
        return new ListingOperations.CreateListing("Simulated food","Test listing","BAKERY",quantity,"ITEMS",null,"Lagos",Instant.now().plusSeconds(3600));
    }
    UUID request(UUID listing) { return requests.request(recipient,listing,null).id(); }
    int count(String sql) { return jdbc.queryForObject(sql,Integer.class); }
    void code(String code,org.junit.jupiter.api.function.Executable call) {
        assertEquals(code,assertThrows(RuleViolation.class,call).code());
    }
    @Test void concurrentApprovalsHaveOneWinnerAndAtomicState() throws Exception {
        UUID listing=listing();
        List<UUID> ids=new ArrayList<>();
        for(int i=0;i<3;i++) ids.add(requests.request(user(Actor.Role.RECIPIENT),listing,null).id());
        var results=parallel(ids,id->{
            try { requests.approve(donor,id); return "SUCCESS"; }
            catch(RuleViolation e) { return e.code(); }
        });
        assertEquals(1,Collections.frequency(results,"SUCCESS"));
        assertEquals(2,Collections.frequency(results,"LISTING_ALREADY_RESERVED"));
        assertEquals(1,count("select count(*) from requests where status='APPROVED'"));
        assertEquals(2,count("select count(*) from requests where status='PENDING' and decided_at is null"));
        assertEquals(1,count("select count(*) from handovers where status='AWAITING_COLLECTION'"));
        assertEquals(1,count("select count(*) from activity where type='REQUEST_APPROVED'"));
        assertEquals(1,count("select count(*) from listings where status='RESERVED'"));
        System.out.println("SLIDE4 SERVICE: approvals=1 conflicts=2 handovers=1 atomic state verified");
    }
    @Test void duplicateApprovalIsConflict() {
        UUID id=request(listing()); requests.approve(donor,id);
        code("LISTING_ALREADY_RESERVED",()->requests.approve(donor,id));
        assertEquals(1,count("select count(*) from handovers"));
    }
    @Test void expiredListingsRejectRequestsAndApprovals() {
        UUID listing=listing(), id=request(listing);
        jdbc.update("update listings set collection_deadline=clock_timestamp()-interval '1 second' where id=?",listing);
        code("LISTING_EXPIRED",()->request(listing));
        code("LISTING_EXPIRED",()->requests.approve(donor,id));
        assertEquals(0,count("select count(*) from handovers"));
    }
    @Test void approvalChecksDeadlineAfterWaitingForListingLock() throws Exception {
        UUID listing=listing(), id=request(listing);
        try(var conn=DB.createConnection("")) {
            conn.setAutoCommit(false);
            try(var stmt=conn.prepareStatement("update listings set collection_deadline=clock_timestamp()+interval '1 second' where id=?")) {
                stmt.setObject(1,listing); stmt.executeUpdate();
            }
            try(var pool=Executors.newSingleThreadExecutor()) {
                var task=pool.submit(()->{ requests.approve(donor,id); return "SUCCESS"; });
                // Hold the lock until the deadline is in the past, then let approval acquire it.
                try(var stmt=conn.createStatement()) { stmt.execute("select pg_sleep(1.2)"); }
                conn.commit();
                var failure=assertThrows(ExecutionException.class,()->task.get(15,TimeUnit.SECONDS));
                assertEquals("LISTING_EXPIRED",((RuleViolation)failure.getCause()).code());
            }
        }
    }
    @Test void unauthorizedApprovalsAreRejected() {
        UUID id=request(listing());
        Actor stranger=user(Actor.Role.DONOR);
        code("FORBIDDEN",()->requests.approve(stranger,id));
        code("FORBIDDEN",()->requests.approve(recipient,id));
        code("FORBIDDEN",()->requests.approve(new Actor(recipient.id(),Actor.Role.ADMIN),id));
        requests.approve(user(Actor.Role.ADMIN),id);
    }
    @Test void invalidQuantitiesRejectedByServiceAndDatabase() {
        code("INVALID_LISTING",()->listings.create(donor,command(BigDecimal.ZERO)));
        UUID id=listing();
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,
            ()->jdbc.update("update listings set quantity=0 where id=?",id));
    }
    @Test void moduleContractsEnforceCreationAndReadAccess() {
        code("FORBIDDEN",()->listings.create(recipient,command(BigDecimal.ONE)));
        UUID listing=listing();
        code("FORBIDDEN",()->requests.request(donor,listing,null));
        UUID id=request(listing);
        assertEquals(id,requests.get(donor,id).id());
        Actor other=user(Actor.Role.RECIPIENT);
        code("FORBIDDEN",()->requests.get(other,id));
        code("INVALID_PAGE",()->listings.browse(0,101));
        assertEquals(1,listings.browse(0,20).size());
    }
    @Test void idempotencyIsAtomicAndBoundToActorAndListing() throws Exception {
        UUID listing=listing();
        var ids=parallel(List.of(1,2,3),i->requests.request(recipient,listing,"same-key").id());
        assertEquals(1,new HashSet<>(ids).size());
        assertEquals(1,count("select count(*) from requests"));
        Actor other=user(Actor.Role.RECIPIENT);
        code("IDEMPOTENCY_KEY_REUSED",()->requests.request(other,listing,"same-key"));
        UUID another=listing();
        code("IDEMPOTENCY_KEY_REUSED",()->requests.request(recipient,another,"same-key"));
    }
    @Test void failedHandoverRollsBackApprovalAndListing() {
        UUID listing=listing(), id=request(listing);
        // Deliberately inject a downstream failure; the entire approval must roll back.
        jdbc.update("insert into handovers(id,request_id) values (?,?)",UUID.randomUUID(),id);
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->requests.approve(donor,id));
        assertEquals("PENDING",requests.get(recipient,id).status());
        assertEquals("AVAILABLE",listings.get(listing).status());
        assertEquals(0,count("select count(*) from activity where type='REQUEST_APPROVED'"));
    }
    @Test void demoSeedIsRepeatableAndHasRequiredStates() {
        var seed=new com.tesa.foodbridge.demo.DemoSeed(jdbc,
            new org.springframework.mock.env.MockEnvironment().withProperty("DEMO_PASSWORD_HASH","disabled-test-hash"));
        seed.run(new org.springframework.boot.DefaultApplicationArguments());
        seed.run(new org.springframework.boot.DefaultApplicationArguments());
        assertEquals(6,count("select count(*) from users where email like '%@demo.foodbridge.invalid'"));
        assertEquals(12,count("select count(*) from listings"));
        assertEquals(2,count("select count(*) from listings where status='EXPIRED'"));
        assertEquals(3,jdbc.queryForObject("select count(*) from requests where listing_id=? and status='PENDING'",Integer.class,
            com.tesa.foodbridge.demo.DemoSeed.id("listing3")));
        assertEquals(3,count("select count(*) from handovers"));
    }
    static <T,R> List<R> parallel(List<T> values,java.util.function.Function<T,R> operation) throws Exception {
        var ready=new CountDownLatch(values.size()); var start=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(values.size())) {
            List<Future<R>> futures=new ArrayList<>();
            for(T value:values) futures.add(pool.submit(()->{
                ready.countDown(); if(!start.await(10,TimeUnit.SECONDS)) throw new AssertionError("start timeout");
                return operation.apply(value);
            }));
            assertTrue(ready.await(10,TimeUnit.SECONDS)); start.countDown();
            List<R> results=new ArrayList<>();
            for(var future:futures) results.add(future.get(30,TimeUnit.SECONDS));
            return results;
        } finally { start.countDown(); }
    }
}
